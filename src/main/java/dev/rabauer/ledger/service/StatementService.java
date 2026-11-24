package dev.rabauer.ledger.service;

import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.Table;
import org.springframework.stereotype.Service;

/** Account statement with running balance and keyset pagination. */
@Service
public class StatementService {

    private final DSLContext dsl;

    public StatementService(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record Line(long postingId, long transactionId, OffsetDateTime bookedAt, String description,
                       BigDecimal amount, BigDecimal balanceAfter) {
    }

    /** Position after which the next page starts. */
    public record Cursor(OffsetDateTime bookedAt, long postingId) {
    }

    public record Page(List<Line> lines, Cursor next) {
    }

    /** First page when after is null. next is null on the last page. */
    public Page statement(long accountId, Cursor after, int pageSize) {
        // The running balance must see all earlier postings, so the window function runs in an inner query
        // and the keyset predicate is applied outside of it.
        Field<BigDecimal> balance = sum(POSTING.AMOUNT)
                .over().orderBy(LEDGER_TRANSACTION.BOOKED_AT, POSTING.ID)
                .as("balance_after");
        Table<?> inner = dsl.select(POSTING.ID, POSTING.TRANSACTION_ID, LEDGER_TRANSACTION.BOOKED_AT,
                        LEDGER_TRANSACTION.DESCRIPTION, POSTING.AMOUNT, balance)
                .from(POSTING)
                .join(LEDGER_TRANSACTION).on(LEDGER_TRANSACTION.ID.eq(POSTING.TRANSACTION_ID))
                .where(POSTING.ACCOUNT_ID.eq(accountId))
                .asTable("s");

        Field<Long> id = inner.field(POSTING.ID);
        Field<OffsetDateTime> bookedAt = inner.field(LEDGER_TRANSACTION.BOOKED_AT);
        Result<Record> rows = dsl.select(inner.fields())
                .from(inner)
                .where(after == null
                        ? noCondition()
                        : row(bookedAt, id).gt(row(after.bookedAt(), after.postingId())))
                .orderBy(bookedAt, id)
                .limit(pageSize + 1)
                .fetch();

        List<Line> lines = rows.stream().limit(pageSize).map(r -> new Line(
                r.get(inner.field(POSTING.ID)),
                r.get(inner.field(POSTING.TRANSACTION_ID)),
                r.get(inner.field(LEDGER_TRANSACTION.BOOKED_AT)),
                r.get(inner.field(LEDGER_TRANSACTION.DESCRIPTION)),
                r.get(inner.field(POSTING.AMOUNT)),
                r.get("balance_after", BigDecimal.class))).toList();
        Cursor next = rows.size() > pageSize
                ? new Cursor(lines.getLast().bookedAt(), lines.getLast().postingId())
                : null;
        return new Page(lines, next);
    }
}
