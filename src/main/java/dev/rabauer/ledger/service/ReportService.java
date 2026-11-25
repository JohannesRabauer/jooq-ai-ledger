package dev.rabauer.ledger.service;

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

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Records;
import org.springframework.stereotype.Service;

/** Monthly customer report in a single query. */
@Service
public class ReportService {

    private final DSLContext dsl;

    public ReportService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Debits are reported as a positive magnitude. Months are in UTC. */
    public record AccountSummary(long accountId, Iban iban, String currency, BigDecimal credits,
                                 BigDecimal debits, BigDecimal closingBalance) {
    }

    public record CustomerReport(long customerId, String name, List<AccountSummary> accounts) {
    }

    public List<CustomerReport> monthlyReport(YearMonth month) {
        OffsetDateTime start = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        Condition inMonth = LEDGER_TRANSACTION.BOOKED_AT.ge(start).and(LEDGER_TRANSACTION.BOOKED_AT.lt(end));
        Field<BigDecimal> credits = coalesce(
                sum(POSTING.AMOUNT).filterWhere(inMonth.and(POSTING.AMOUNT.gt(BigDecimal.ZERO))), BigDecimal.ZERO);
        Field<BigDecimal> debits = coalesce(
                sum(POSTING.AMOUNT.neg()).filterWhere(inMonth.and(POSTING.AMOUNT.lt(BigDecimal.ZERO))),
                BigDecimal.ZERO);
        Field<BigDecimal> closing = coalesce(
                sum(POSTING.AMOUNT).filterWhere(LEDGER_TRANSACTION.BOOKED_AT.lt(end)), BigDecimal.ZERO);

        return dsl.select(
                        CUSTOMER.ID,
                        CUSTOMER.NAME,
                        multiset(
                                select(ACCOUNT.ID, ACCOUNT.IBAN, ACCOUNT.CURRENCY, credits, debits, closing)
                                        .from(ACCOUNT)
                                        .leftJoin(POSTING).on(POSTING.ACCOUNT_ID.eq(ACCOUNT.ID))
                                        .leftJoin(LEDGER_TRANSACTION)
                                        .on(LEDGER_TRANSACTION.ID.eq(POSTING.TRANSACTION_ID))
                                        .where(ACCOUNT.CUSTOMER_ID.eq(CUSTOMER.ID))
                                        .groupBy(ACCOUNT.ID, ACCOUNT.IBAN, ACCOUNT.CURRENCY)
                                        .orderBy(ACCOUNT.ID)
                        ).convertFrom(r -> r.map(Records.mapping(AccountSummary::new))))
                .from(CUSTOMER)
                .orderBy(CUSTOMER.ID)
                .fetch(Records.mapping(CustomerReport::new));
    }
}
