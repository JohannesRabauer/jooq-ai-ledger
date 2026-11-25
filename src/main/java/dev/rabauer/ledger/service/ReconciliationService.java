package dev.rabauer.ledger.service;

import static dev.rabauer.ledger.jooq.Tables.BANK_LINE;
import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.extract;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.floor;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.rowNumber;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.DatePart;
import org.jooq.Field;
import org.jooq.Records;
import org.jooq.Table;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Matches external bank statement lines with our postings, in SQL. */
@Service
public class ReconciliationService {

    /** A line may match a posting booked at most this many (UTC) days before or after its value date. */
    static final int MAX_DAYS_APART = 2;

    private static final long RECONCILE_LOCK = 7_003L;
    private static final long SECONDS_PER_DAY = 86_400L;

    private final DSLContext dsl;

    public ReconciliationService(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record OpenLine(long lineId, String externalRef, LocalDate valueDate, BigDecimal amount) {
    }

    public record OpenPosting(long postingId, long transactionId, OffsetDateTime bookedAt, BigDecimal amount,
                              String description) {
    }

    public record Differences(List<OpenLine> linesWithoutPosting, List<OpenPosting> postingsWithoutLine) {
    }

    /** Matches all unmatched bank lines. Returns the number of lines matched by this run (0 on a second run). */
    @Transactional
    public int reconcile() {
        return reconcile(null);
    }

    /**
     * Greedy matching, closest date first, ties broken by lower line id then lower posting id. Done in rounds of one
     * UPDATE each: a candidate pair is taken in a round when it is the best pair for its line and the best pair for
     * its posting among the lines and postings still free. Such a pair is exactly what a global greedy pass over all
     * pairs sorted by (distance, line, posting) would take, so repeating until a round changes nothing gives the
     * greedy result. The number of rounds depends on how entangled the candidates are, not on the number of rows.
     */
    @Transactional
    public int reconcile(Long accountId) {
        // No two reconciliations at the same time; the unique constraint on matched_posting_id is the last guard.
        dsl.execute("select pg_advisory_xact_lock(?)", RECONCILE_LOCK);
        int total = 0;
        int changed;
        do {
            changed = matchRound(accountId);
            total += changed;
        } while (changed > 0);
        return total;
    }

    private int matchRound(Long accountId) {
        // Whole days since 1970-01-01 (UTC) of the booking and of the value date. Calendar arithmetic on the epoch
        // keeps this independent of the database session time zone.
        Field<BigDecimal> bookedDay = floor(extract(LEDGER_TRANSACTION.BOOKED_AT, DatePart.EPOCH)
                .cast(BigDecimal.class).div(BigDecimal.valueOf(SECONDS_PER_DAY)));
        Field<BigDecimal> valueDay = extract(BANK_LINE.VALUE_DATE, DatePart.EPOCH)
                .cast(BigDecimal.class).div(BigDecimal.valueOf(SECONDS_PER_DAY));
        Field<BigDecimal> distance = bookedDay.minus(valueDay).abs();

        Condition scope = accountId == null ? noCondition() : BANK_LINE.ACCOUNT_ID.eq(accountId);

        Table<?> candidates = select(
                BANK_LINE.ID.as("line_id"),
                POSTING.ID.as("posting_id"),
                distance.as("distance"))
                .from(BANK_LINE)
                .join(POSTING).on(POSTING.ACCOUNT_ID.eq(BANK_LINE.ACCOUNT_ID).and(POSTING.AMOUNT.eq(BANK_LINE.AMOUNT)))
                .join(LEDGER_TRANSACTION).on(LEDGER_TRANSACTION.ID.eq(POSTING.TRANSACTION_ID))
                .where(BANK_LINE.MATCHED_POSTING_ID.isNull())
                .and(scope)
                .and(notExists(selectOne().from(BANK_LINE.as("used"))
                        .where(BANK_LINE.as("used").MATCHED_POSTING_ID.eq(POSTING.ID))))
                .and(distance.le(BigDecimal.valueOf(MAX_DAYS_APART)))
                .asTable("candidates");
        Field<Long> lineId = candidates.field("line_id", Long.class);
        Field<Long> postingId = candidates.field("posting_id", Long.class);
        Field<BigDecimal> dist = candidates.field("distance", BigDecimal.class);

        Table<?> ranked = select(lineId, postingId,
                rowNumber().over().partitionBy(lineId).orderBy(dist, postingId).as("line_rank"),
                rowNumber().over().partitionBy(postingId).orderBy(dist, lineId).as("posting_rank"))
                .from(candidates)
                .asTable("ranked");
        Field<Long> rLine = ranked.field("line_id", Long.class);
        Field<Long> rPosting = ranked.field("posting_id", Long.class);

        Table<?> mutual = select(rLine, rPosting)
                .from(ranked)
                .where(ranked.field("line_rank", Integer.class).eq(1))
                .and(ranked.field("posting_rank", Integer.class).eq(1))
                .asTable("mutual");

        return dsl.update(BANK_LINE)
                .set(BANK_LINE.MATCHED_POSTING_ID, mutual.field("posting_id", Long.class))
                .from(mutual)
                .where(BANK_LINE.ID.eq(mutual.field("line_id", Long.class)))
                .execute();
    }

    /**
     * Bank lines of the account with a value date in [from, to] that have no posting, and postings of the account
     * booked (UTC date) in [from, to] that no bank line points at.
     */
    public Differences differences(long accountId, LocalDate from, LocalDate to) {
        OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        List<OpenLine> lines = dsl.select(BANK_LINE.ID, BANK_LINE.EXTERNAL_REF, BANK_LINE.VALUE_DATE, BANK_LINE.AMOUNT)
                .from(BANK_LINE)
                .where(BANK_LINE.ACCOUNT_ID.eq(accountId))
                .and(BANK_LINE.MATCHED_POSTING_ID.isNull())
                .and(BANK_LINE.VALUE_DATE.between(from, to))
                .orderBy(BANK_LINE.VALUE_DATE, BANK_LINE.ID)
                .fetch(Records.mapping(OpenLine::new));

        List<OpenPosting> postings = dsl.select(POSTING.ID, POSTING.TRANSACTION_ID, LEDGER_TRANSACTION.BOOKED_AT,
                        POSTING.AMOUNT, LEDGER_TRANSACTION.DESCRIPTION)
                .from(POSTING)
                .join(LEDGER_TRANSACTION).on(LEDGER_TRANSACTION.ID.eq(POSTING.TRANSACTION_ID))
                .where(POSTING.ACCOUNT_ID.eq(accountId))
                .and(LEDGER_TRANSACTION.BOOKED_AT.ge(start))
                .and(LEDGER_TRANSACTION.BOOKED_AT.lt(end))
                .and(notExists(selectOne().from(BANK_LINE).where(BANK_LINE.MATCHED_POSTING_ID.eq(POSTING.ID))))
                .orderBy(LEDGER_TRANSACTION.BOOKED_AT, POSTING.ID)
                .fetch(Records.mapping(OpenPosting::new));

        return new Differences(lines, postings);
    }
}
