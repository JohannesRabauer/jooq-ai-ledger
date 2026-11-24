package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static dev.rabauer.ledger.jooq.Tables.CUSTOMER;
import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Records;
import org.springframework.stereotype.Service;

@Service
public class CustomerReportService {

    public record AccountMonth(long accountId, String iban, String currency, BigDecimal credits,
            BigDecimal debits, BigDecimal closingBalance) {
    }

    public record CustomerMonth(long customerId, String name, List<AccountMonth> accounts) {
    }

    private final DSLContext dsl;

    public CustomerReportService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Per customer and account: credits, debits (negative) and closing balance of {@code month} (UTC), in a
     * single statement with a nested MULTISET. Debits are reported as a negative sum.
     */
    public List<CustomerMonth> report(YearMonth month) {
        OffsetDateTime start = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        // Postings booked after the month are excluded by the join; their transaction columns are then null.
        Field<BigDecimal> credits = coalesce(sum(POSTING.AMOUNT)
                .filterWhere(POSTING.AMOUNT.gt(BigDecimal.ZERO).and(LEDGER_TRANSACTION.BOOKED_AT.ge(start))),
                BigDecimal.ZERO);
        Field<BigDecimal> debits = coalesce(sum(POSTING.AMOUNT)
                .filterWhere(POSTING.AMOUNT.lt(BigDecimal.ZERO).and(LEDGER_TRANSACTION.BOOKED_AT.ge(start))),
                BigDecimal.ZERO);
        Field<BigDecimal> closing = coalesce(sum(POSTING.AMOUNT)
                .filterWhere(LEDGER_TRANSACTION.ID.isNotNull()), BigDecimal.ZERO);

        return dsl
                .select(CUSTOMER.ID, CUSTOMER.NAME,
                        multiset(
                                select(ACCOUNT.ID, ACCOUNT.IBAN, ACCOUNT.CURRENCY, credits, debits, closing)
                                        .from(ACCOUNT)
                                        .leftJoin(POSTING).on(POSTING.ACCOUNT_ID.eq(ACCOUNT.ID))
                                        .leftJoin(LEDGER_TRANSACTION)
                                        .on(LEDGER_TRANSACTION.ID.eq(POSTING.TRANSACTION_ID))
                                        .and(LEDGER_TRANSACTION.BOOKED_AT.lt(end))
                                        .where(ACCOUNT.CUSTOMER_ID.eq(CUSTOMER.ID))
                                        .groupBy(ACCOUNT.ID, ACCOUNT.IBAN, ACCOUNT.CURRENCY)
                                        .orderBy(ACCOUNT.ID))
                                .convertFrom(r -> r.map(Records.mapping(AccountMonth::new))))
                .from(CUSTOMER)
                .orderBy(CUSTOMER.NAME, CUSTOMER.ID)
                .fetch(Records.mapping(CustomerMonth::new));
    }
}
