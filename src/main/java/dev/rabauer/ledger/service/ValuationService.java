package dev.rabauer.ledger.service;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static dev.rabauer.ledger.jooq.Tables.EXCHANGE_RATE;
import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.sum;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record7;
import org.jooq.Records;
import org.jooq.ResultQuery;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

/**
 * Valuation of a customer's accounts in the reporting currency CHF.
 *
 * <p>Rates: the row of {@code exchange_rate} with base = account currency and quote = CHF and the latest
 * {@code valid_from} that is not after the point in time. Inverse rates (CHF to X, 1/rate) are NOT used: an account
 * whose currency has no direct X to CHF row has no rate. CHF accounts need no rate (factor 1). Amounts of different
 * currencies are never added; every account is valued on its own.
 */
@Service
public class ValuationService {

    private static final String REPORTING_CURRENCY = "CHF";

    private final DSLContext dsl;

    public ValuationService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * balance is in the account currency. valueAtBookingRates is the sum over the postings of amount times the rate
     * valid when the posting was booked; valueAtLatestRate is balance times the rate valid at the valuation time;
     * difference is valueAtLatestRate minus valueAtBookingRates. All three CHF values are null when the account
     * currency has no rate at the valuation time, and valueAtBookingRates and difference are also null when one of
     * the postings was booked before the first rate existed. Values are not rounded.
     */
    public record AccountValuation(long accountId, Iban iban, String currency, BigDecimal balance,
                                   BigDecimal valueAtBookingRates, BigDecimal valueAtLatestRate,
                                   BigDecimal difference) {
    }

    /** Only postings booked up to and including asOf count, and the "latest" rate is the one valid at asOf. */
    public List<AccountValuation> valuation(long customerId, OffsetDateTime asOf) {
        return dsl.fetch(valuationQuery(customerId, asOf)).map(Records.mapping(AccountValuation::new));
    }

    /** The valuation SQL as rendered for the given dialect (bind variables stay placeholders). */
    public String valuationSql(SQLDialect dialect, long customerId, OffsetDateTime asOf) {
        return DSL.using(dialect).render(valuationQuery(customerId, asOf));
    }

    /** Rate of currency to CHF valid at the given time, null if there is none. CHF itself is 1. */
    private static Field<BigDecimal> rateAt(Field<String> currency, Field<OffsetDateTime> at) {
        return when(currency.eq(REPORTING_CURRENCY), inline(BigDecimal.ONE)).otherwise(
                select(EXCHANGE_RATE.RATE)
                        .from(EXCHANGE_RATE)
                        .where(EXCHANGE_RATE.BASE_CURRENCY.eq(currency))
                        .and(EXCHANGE_RATE.QUOTE_CURRENCY.eq(REPORTING_CURRENCY))
                        .and(EXCHANGE_RATE.VALID_FROM.le(at))
                        .orderBy(EXCHANGE_RATE.VALID_FROM.desc())
                        .limit(1)
                        .asField());
    }

    ResultQuery<Record7<Long, Iban, String, BigDecimal, BigDecimal, BigDecimal, BigDecimal>> valuationQuery(
            long customerId, OffsetDateTime asOf) {
        // One row per posting with the rate valid when it was booked.
        Table<?> posted = select(
                POSTING.ACCOUNT_ID.as("account_id"),
                POSTING.AMOUNT.as("amount"),
                rateAt(ACCOUNT.CURRENCY, LEDGER_TRANSACTION.BOOKED_AT).as("rate"))
                .from(POSTING)
                .join(LEDGER_TRANSACTION).on(LEDGER_TRANSACTION.ID.eq(POSTING.TRANSACTION_ID))
                .join(ACCOUNT).on(ACCOUNT.ID.eq(POSTING.ACCOUNT_ID))
                .where(ACCOUNT.CUSTOMER_ID.eq(customerId))
                .and(LEDGER_TRANSACTION.BOOKED_AT.le(asOf))
                .asTable("posted");
        Field<Long> pAccount = posted.field("account_id", Long.class);
        Field<BigDecimal> pAmount = posted.field("amount", BigDecimal.class);
        Field<BigDecimal> pRate = posted.field("rate", BigDecimal.class);

        Field<BigDecimal> latestRate = rateAt(ACCOUNT.CURRENCY, val(asOf));
        Field<BigDecimal> balance = coalesce(sum(pAmount), BigDecimal.ZERO);
        Field<Integer> postingsWithoutRate = sum(when(pRate.isNull().and(pAmount.isNotNull()), 1).otherwise(0))
                .cast(Integer.class);

        Table<?> perAccount = select(
                ACCOUNT.ID.as("account_id"),
                ACCOUNT.IBAN.as("iban"),
                ACCOUNT.CURRENCY.as("currency"),
                balance.as("balance"),
                when(latestRate.isNull().or(postingsWithoutRate.gt(0)), inline((BigDecimal) null))
                        .otherwise(coalesce(sum(pAmount.times(pRate)), BigDecimal.ZERO)).as("at_booking"),
                when(latestRate.isNull(), inline((BigDecimal) null))
                        .otherwise(balance.times(latestRate)).as("at_latest"))
                .from(ACCOUNT)
                .leftJoin(posted).on(pAccount.eq(ACCOUNT.ID))
                .where(ACCOUNT.CUSTOMER_ID.eq(customerId))
                .groupBy(ACCOUNT.ID, ACCOUNT.IBAN, ACCOUNT.CURRENCY)
                .asTable("per_account");

        Field<Long> accountId = perAccount.field("account_id", Long.class);
        Field<Iban> iban = perAccount.field("iban", Iban.class);
        Field<String> currency = perAccount.field("currency", String.class);
        Field<BigDecimal> bal = perAccount.field("balance", BigDecimal.class);
        Field<BigDecimal> atBooking = perAccount.field("at_booking", BigDecimal.class);
        Field<BigDecimal> atLatest = perAccount.field("at_latest", BigDecimal.class);

        return dsl.select(accountId, iban, currency, bal, atBooking, atLatest, atLatest.minus(atBooking))
                .from(perAccount)
                .orderBy(accountId);
    }
}
