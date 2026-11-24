package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.orderBy;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Records;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

@Service
public class StatementService {

    /** Position in the statement: the last line of the previous page. */
    public record Cursor(OffsetDateTime bookedAt, long postingId) {
    }

    public record StatementLine(long postingId, long transactionId, OffsetDateTime bookedAt, String description,
            BigDecimal amount, BigDecimal balanceAfter) {

        public Cursor cursor() {
            return new Cursor(bookedAt, postingId);
        }
    }

    private final DSLContext dsl;

    public StatementService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * One page of an account's statement in booking order (booked_at, posting id), each line with the running
     * balance after it. Keyset pagination: pass the cursor of the last line of the previous page ({@code null}
     * for the first page).
     */
    public List<StatementLine> statement(long accountId, Cursor after, int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        // The window only sees the rows of this page, so the balance before the page is added as an offset.
        Field<BigDecimal> opening = after == null
                ? DSL.inline(BigDecimal.ZERO)
                : field(select(coalesce(sum(POSTING.AMOUNT), BigDecimal.ZERO))
                        .from(POSTING)
                        .join(LEDGER_TRANSACTION).on(LEDGER_TRANSACTION.ID.eq(POSTING.TRANSACTION_ID))
                        .where(POSTING.ACCOUNT_ID.eq(accountId))
                        .and(row(LEDGER_TRANSACTION.BOOKED_AT, POSTING.ID).le(row(after.bookedAt(), after.postingId()))));
        Field<BigDecimal> running = opening.plus(
                sum(POSTING.AMOUNT).over(orderBy(LEDGER_TRANSACTION.BOOKED_AT, POSTING.ID)));

        var query = dsl
                .select(POSTING.ID, POSTING.TRANSACTION_ID, LEDGER_TRANSACTION.BOOKED_AT,
                        LEDGER_TRANSACTION.DESCRIPTION, POSTING.AMOUNT, running)
                .from(POSTING)
                .join(LEDGER_TRANSACTION).on(LEDGER_TRANSACTION.ID.eq(POSTING.TRANSACTION_ID))
                .where(POSTING.ACCOUNT_ID.eq(accountId))
                .orderBy(LEDGER_TRANSACTION.BOOKED_AT, POSTING.ID);
        if (after == null) {
            return query.limit(pageSize).fetch(Records.mapping(StatementLine::new));
        }
        return query.seek(after.bookedAt(), after.postingId()).limit(pageSize)
                .fetch(Records.mapping(StatementLine::new));
    }
}
