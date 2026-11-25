package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.BANK_LINE;
import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.abs;
import static org.jooq.impl.DSL.dateDiff;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.rowNumber;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.jooq.CommonTableExpression;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationService {

    static final int MAX_DAYS_APART = 2;

    public record UnmatchedLine(long lineId, String externalRef, LocalDate valueDate, BigDecimal amount) {
    }

    public record UnmatchedPosting(long postingId, long transactionId, OffsetDateTime bookedAt, BigDecimal amount,
            String description) {
    }

    public record Unreconciled(List<UnmatchedLine> bankLinesWithoutPosting,
            List<UnmatchedPosting> postingsWithoutBankLine) {
    }

    private static final Field<Long> R_LINE = field(name("ranked", "line_id"), Long.class);
    private static final Field<Long> R_POSTING = field(name("ranked", "posting_id"), Long.class);
    private static final Field<Integer> R_LINE_RANK = field(name("ranked", "line_rank"), Integer.class);
    private static final Field<Integer> R_POSTING_RANK = field(name("ranked", "posting_rank"), Integer.class);

    private final DSLContext dsl;

    public ReconciliationService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Calendar day (UTC) of the booking. Postgres syntax ({@code AT TIME ZONE}); it does not depend on the session
     * time zone, which a plain cast to DATE would.
     */
    private static Field<LocalDate> bookingDayUtc() {
        return field("{0} at time zone 'UTC'", SQLDataType.LOCALDATETIME, LEDGER_TRANSACTION.BOOKED_AT)
                .cast(SQLDataType.LOCALDATE);
    }

    /** Matches the unmatched bank lines of all accounts. Returns the number of lines matched by this run. */
    public int reconcile() {
        return reconcile(noCondition());
    }

    public int reconcile(long accountId) {
        return reconcile(BANK_LINE.ACCOUNT_ID.eq(accountId));
    }

    /**
     * A line matches an unmatched posting of the same account and amount booked (UTC date) at most
     * {@value #MAX_DAYS_APART} days from the line's value date; the closest date wins, ties go to the lower line id
     * and then the lower posting id. Everything happens in SQL. Greedy "closest first" is done in rounds: each
     * UPDATE matches all pairs that are the best choice for both sides (the globally closest pair always is, so
     * these are exactly the pairs a sequential closest-first pass would take), and rounds repeat until nothing
     * changes. Running it again therefore changes nothing. Meant for one run at a time: two concurrent runs can
     * collide on the unique constraint of matched_posting_id (and fail) instead of matching twice.
     */
    private int reconcile(Condition lineFilter) {
        return dsl.transactionResult(cfg -> {
            DSLContext tx = cfg.dsl();
            Field<Integer> distance = abs(dateDiff(bookingDayUtc().cast(SQLDataType.DATE),
                    BANK_LINE.VALUE_DATE.cast(SQLDataType.DATE)));

            // Every possible pair of a free line and a free posting.
            CommonTableExpression<?> candidates = name("candidates").fields("line_id", "posting_id", "distance").as(
                    select(BANK_LINE.ID, POSTING.ID, distance)
                            .from(BANK_LINE)
                            .join(POSTING).on(POSTING.ACCOUNT_ID.eq(BANK_LINE.ACCOUNT_ID)
                                    .and(POSTING.AMOUNT.eq(BANK_LINE.AMOUNT)))
                            .join(LEDGER_TRANSACTION).on(LEDGER_TRANSACTION.ID.eq(POSTING.TRANSACTION_ID))
                            .where(BANK_LINE.MATCHED_POSTING_ID.isNull())
                            .and(lineFilter)
                            .and(notExists(selectOne().from(BANK_LINE.as("taken"))
                                    .where(BANK_LINE.as("taken").MATCHED_POSTING_ID.eq(POSTING.ID))))
                            .and(distance.le(MAX_DAYS_APART)));

            Field<Long> cLine = field(name("candidates", "line_id"), Long.class);
            Field<Long> cPosting = field(name("candidates", "posting_id"), Long.class);
            Field<Integer> cDistance = field(name("candidates", "distance"), Integer.class);
            CommonTableExpression<?> ranked = name("ranked").fields("line_id", "posting_id", "line_rank", "posting_rank").as(
                    select(cLine, cPosting,
                            rowNumber().over(partitionBy(cLine).orderBy(cDistance, cPosting)),
                            rowNumber().over(partitionBy(cPosting).orderBy(cDistance, cLine)))
                            .from(table(name("candidates"))));

            int total = 0;
            int matched;
            do {
                matched = tx.with(candidates).with(ranked)
                        .update(BANK_LINE)
                        .set(BANK_LINE.MATCHED_POSTING_ID, R_POSTING)
                        .from(table(name("ranked")))
                        .where(BANK_LINE.ID.eq(R_LINE))
                        .and(R_LINE_RANK.eq(1))
                        .and(R_POSTING_RANK.eq(1))
                        .execute();
                total += matched;
            } while (matched > 0);
            return total;
        });
    }

    /**
     * For one account and a date range (inclusive, UTC days): bank lines by value date without a posting, and
     * postings by booking day without a bank line.
     */
    public Unreconciled unreconciled(long accountId, LocalDate from, LocalDate to) {
        List<UnmatchedLine> lines = dsl
                .select(BANK_LINE.ID, BANK_LINE.EXTERNAL_REF, BANK_LINE.VALUE_DATE, BANK_LINE.AMOUNT)
                .from(BANK_LINE)
                .where(BANK_LINE.ACCOUNT_ID.eq(accountId))
                .and(BANK_LINE.MATCHED_POSTING_ID.isNull())
                .and(BANK_LINE.VALUE_DATE.between(from, to))
                .orderBy(BANK_LINE.VALUE_DATE, BANK_LINE.ID)
                .fetch(r -> new UnmatchedLine(r.value1(), r.value2(), r.value3(), r.value4()));

        List<UnmatchedPosting> postings = dsl
                .select(POSTING.ID, POSTING.TRANSACTION_ID, LEDGER_TRANSACTION.BOOKED_AT, POSTING.AMOUNT,
                        LEDGER_TRANSACTION.DESCRIPTION)
                .from(POSTING)
                .join(LEDGER_TRANSACTION).on(LEDGER_TRANSACTION.ID.eq(POSTING.TRANSACTION_ID))
                .where(POSTING.ACCOUNT_ID.eq(accountId))
                .and(bookingDayUtc().between(from, to))
                .and(notExists(selectOne().from(BANK_LINE).where(BANK_LINE.MATCHED_POSTING_ID.eq(POSTING.ID))))
                .orderBy(LEDGER_TRANSACTION.BOOKED_AT, POSTING.ID)
                .fetch(r -> new UnmatchedPosting(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
        return new Unreconciled(lines, postings);
    }
}
