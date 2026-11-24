package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record4;
import org.jooq.Records;
import org.jooq.Select;
import org.springframework.stereotype.Service;

@Service
public class TransactionSearchService {

    /**
     * All filters are optional (null = not applied). The amount of a transaction is its largest absolute
     * posting; the account filter matches transactions with a posting on that account.
     */
    public record Filter(OffsetDateTime from, OffsetDateTime to, BigDecimal minAmount, BigDecimal maxAmount,
            String text, Long accountId) {
    }

    public record TransactionRow(long id, OffsetDateTime bookedAt, String description, BigDecimal amount) {
    }

    private final DSLContext dsl;

    public TransactionSearchService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Date range is [from, to): from inclusive, to exclusive. Text is a case-insensitive substring match. */
    public List<TransactionRow> search(Filter f) {
        return query(f).fetch(Records.mapping(TransactionRow::new));
    }

    Select<Record4<Long, OffsetDateTime, String, BigDecimal>> query(Filter f) {
        Condition condition = noCondition();
        if (f.from() != null) {
            condition = condition.and(LEDGER_TRANSACTION.BOOKED_AT.ge(f.from()));
        }
        if (f.to() != null) {
            condition = condition.and(LEDGER_TRANSACTION.BOOKED_AT.lt(f.to()));
        }
        if (f.text() != null && !f.text().isBlank()) {
            condition = condition.and(LEDGER_TRANSACTION.DESCRIPTION.containsIgnoreCase(f.text()));
        }
        if (f.accountId() != null) {
            condition = condition.and(exists(selectOne().from(POSTING)
                    .where(POSTING.TRANSACTION_ID.eq(LEDGER_TRANSACTION.ID))
                    .and(POSTING.ACCOUNT_ID.eq(f.accountId()))));
        }

        var amount = max(POSTING.AMOUNT.abs());
        Condition having = noCondition();
        if (f.minAmount() != null) {
            having = having.and(amount.ge(f.minAmount()));
        }
        if (f.maxAmount() != null) {
            having = having.and(amount.le(f.maxAmount()));
        }

        return dsl
                .select(LEDGER_TRANSACTION.ID, LEDGER_TRANSACTION.BOOKED_AT, LEDGER_TRANSACTION.DESCRIPTION, amount)
                .from(LEDGER_TRANSACTION)
                .join(POSTING).on(POSTING.TRANSACTION_ID.eq(LEDGER_TRANSACTION.ID))
                .where(condition)
                .groupBy(LEDGER_TRANSACTION.ID, LEDGER_TRANSACTION.BOOKED_AT, LEDGER_TRANSACTION.DESCRIPTION)
                .having(having)
                .orderBy(LEDGER_TRANSACTION.BOOKED_AT, LEDGER_TRANSACTION.ID);
    }
}
