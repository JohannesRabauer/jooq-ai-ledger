package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static dev.rabauer.ledger.jooq.Tables.EXCHANGE_RATE;
import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.case_;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.name;
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
import org.jooq.Select;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

import dev.rabauer.ledger.jooq.tables.ExchangeRate;

/**
 * Valuation of a customer's accounts in the reporting currency CHF.
 * <p>
 * Rates: {@code exchange_rate} says 1 base = rate quote, so an account in currency X is valued with the row
 * (base = X, quote = CHF). Inverse rates (CHF -> X) are deliberately NOT used here: a valuation must not silently
 * invent a rate by dividing, so a currency that only has the inverse pair is reported with null values.
 * (This differs from the cross-currency transfer, which falls back to the inverse.) Values are not rounded.
 */
@Service
public class ValuationService {

    static final String REPORTING_CURRENCY = "CHF";

    /**
     * {@code valueAtBookingRates} = sum over the postings of amount * rate valid when the posting was booked,
     * {@code valueAtLatestRate} = balance * latest rate, {@code difference} = latest minus booking. The three
     * values are null when the currency has no rate (at booking time for some posting, or at all as of the
     * valuation time); CHF needs no rate.
     */
    public record AccountValuation(long accountId, Iban iban, String currency, BigDecimal balance,
            BigDecimal valueAtBookingRates, BigDecimal valueAtLatestRate, BigDecimal difference) {
    }

    private final DSLContext dsl;

    public ValuationService(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<AccountValuation> valuate(long customerId) {
        return valuate(customerId, OffsetDateTime.now());
    }

    /** "Latest rate" means the latest row with valid_from <= {@code asOf}. */
    public List<AccountValuation> valuate(long customerId, OffsetDateTime asOf) {
        return valuationQuery(dsl, customerId, asOf).fetch(r -> new AccountValuation(
                r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7()));
    }

    /** The valuation SQL as rendered for {@code dialect} (bind variables as placeholders), nothing is executed. */
    public String valuationSql(SQLDialect dialect, long customerId, OffsetDateTime asOf) {
        return valuationQuery(DSL.using(dialect), customerId, asOf).getSQL();
    }

    private static Select<Record7<Long, Iban, String, BigDecimal, BigDecimal, BigDecimal, BigDecimal>> valuationQuery(
            DSLContext ctx, long customerId, OffsetDateTime asOf) {
        // Per posting (or one row for an account without postings) with the rate valid at booking time and the latest
        // rate. The rate lookups are scalar subqueries, kept out of the aggregation below (not every database
        // allows a subquery inside an aggregate).
        Table<?> pv = select(ACCOUNT.ID.as("account_id"), ACCOUNT.IBAN.as("iban"), ACCOUNT.CURRENCY.as("currency"),
                POSTING.AMOUNT.as("amount"),
                rateAt(ACCOUNT.CURRENCY, LEDGER_TRANSACTION.BOOKED_AT).as("booking_rate"),
                rateAt(ACCOUNT.CURRENCY, val(asOf)).as("latest_rate"))
                .from(ACCOUNT)
                .leftJoin(POSTING).on(POSTING.ACCOUNT_ID.eq(ACCOUNT.ID))
                .leftJoin(LEDGER_TRANSACTION).on(LEDGER_TRANSACTION.ID.eq(POSTING.TRANSACTION_ID))
                .where(ACCOUNT.CUSTOMER_ID.eq(customerId))
                .asTable("pv");

        Field<Long> accountId = pv.field("account_id", Long.class);
        Field<Iban> iban = pv.field("iban", ACCOUNT.IBAN.getDataType());
        Field<String> currency = pv.field("currency", String.class);
        Field<BigDecimal> amount = pv.field("amount", BigDecimal.class);
        Field<BigDecimal> bookingRate = pv.field("booking_rate", BigDecimal.class);
        Field<BigDecimal> latestRate = pv.field("latest_rate", BigDecimal.class);

        Field<BigDecimal> balance = coalesce(sum(amount), BigDecimal.ZERO);
        // Only when every posting has a rate (count of amounts = count of converted amounts) and the currency has a
        // rate at all is the booking-rate value defined; an empty account is worth 0.
        Field<BigDecimal> atBooking = case_()
                .when(count(amount).eq(count(amount.times(bookingRate))).and(latestRate.isNotNull()),
                        coalesce(sum(amount.times(bookingRate)), BigDecimal.ZERO))
                .else_((BigDecimal) null);
        Field<BigDecimal> atLatest = balance.times(latestRate);
        Field<BigDecimal> difference = atLatest.minus(atBooking);

        return ctx.select(accountId, iban, currency, balance, atBooking, atLatest, difference)
                .from(pv)
                .groupBy(accountId, iban, currency, latestRate)
                .orderBy(accountId);
    }

    /**
     * Rate to CHF valid at {@code at}: 1 for CHF, otherwise the row of (currency -> CHF) with the greatest valid_from
     * not after {@code at}; null when there is none. Written with max(valid_from) instead of ORDER BY .. LIMIT 1 so
     * it renders the same way in every dialect.
     */
    private static Field<BigDecimal> rateAt(Field<String> currency, Field<OffsetDateTime> at) {
        ExchangeRate r = EXCHANGE_RATE.as("r");
        ExchangeRate m = EXCHANGE_RATE.as("m");
        return when(currency.eq(REPORTING_CURRENCY), inline(BigDecimal.ONE))
                .otherwise(field(select(r.RATE).from(r)
                        .where(r.BASE_CURRENCY.eq(currency))
                        .and(r.QUOTE_CURRENCY.eq(REPORTING_CURRENCY))
                        .and(r.VALID_FROM.eq(select(max(m.VALID_FROM)).from(m)
                                .where(m.BASE_CURRENCY.eq(currency))
                                .and(m.QUOTE_CURRENCY.eq(REPORTING_CURRENCY))
                                .and(m.VALID_FROM.le(at))))));
    }
}
