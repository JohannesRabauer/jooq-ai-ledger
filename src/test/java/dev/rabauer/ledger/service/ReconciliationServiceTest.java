package dev.rabauer.ledger.service;

import static dev.rabauer.ledger.jooq.Tables.BANK_LINE;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationServiceTest extends AbstractLedgerTest {

    @Autowired
    ReconciliationService reconciliation;

    private static OffsetDateTime utc(int year, int month, int day, int hour, int minute) {
        return OffsetDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC);
    }

    private long posting(long account, String amount, OffsetDateTime at) {
        deposit(account, "EUR", amount, at, "p " + amount);
        return dsl.select(dev.rabauer.ledger.jooq.Tables.POSTING.ID)
                .from(dev.rabauer.ledger.jooq.Tables.POSTING)
                .join(dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION)
                .on(dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION.ID
                        .eq(dev.rabauer.ledger.jooq.Tables.POSTING.TRANSACTION_ID))
                .where(dev.rabauer.ledger.jooq.Tables.POSTING.ACCOUNT_ID.eq(account))
                .orderBy(dev.rabauer.ledger.jooq.Tables.POSTING.ID.desc())
                .limit(1).fetchSingle().value1();
    }

    private long line(long account, String ref, LocalDate valueDate, String amount) {
        return dsl.insertInto(BANK_LINE)
                .set(BANK_LINE.ACCOUNT_ID, account)
                .set(BANK_LINE.EXTERNAL_REF, ref)
                .set(BANK_LINE.VALUE_DATE, valueDate)
                .set(BANK_LINE.AMOUNT, new BigDecimal(amount))
                .returningResult(BANK_LINE.ID).fetchSingle().value1();
    }

    private Long matched(long lineId) {
        return dsl.select(BANK_LINE.MATCHED_POSTING_ID).from(BANK_LINE).where(BANK_LINE.ID.eq(lineId))
                .fetchSingle().value1();
    }

    @Test
    void closestDateWinsWhenTwoLinesCompeteForOnePosting() {
        long account = newAccount("EUR", true);
        long p = posting(account, "50.00", utc(2036, 5, 10, 12, 0));
        // the second line is created first (lower id) but is further away
        long far = line(account, "far", LocalDate.of(2036, 5, 12), "50.00");
        long near = line(account, "near", LocalDate.of(2036, 5, 11), "50.00");

        int matchedLines = reconciliation.reconcile(account);

        assertThat(matchedLines).isEqualTo(1);
        assertThat(matched(near)).isEqualTo(p);
        assertThat(matched(far)).isNull();
    }

    @Test
    void sameDistanceIsBrokenByLowerLineIdAndFreedPostingsAreUsedInLaterRounds() {
        long account = newAccount("EUR", true);
        long p1 = posting(account, "7.00", utc(2036, 6, 10, 9, 0));
        long p2 = posting(account, "7.00", utc(2036, 6, 11, 9, 0));
        long a = line(account, "a", LocalDate.of(2036, 6, 10), "7.00");
        long b = line(account, "b", LocalDate.of(2036, 6, 10), "7.00");

        int matchedLines = reconciliation.reconcile(account);

        // a and b both prefer p1 (distance 0); a wins on id, b takes p2 (distance 1) in the next round
        assertThat(matchedLines).isEqualTo(2);
        assertThat(matched(a)).isEqualTo(p1);
        assertThat(matched(b)).isEqualTo(p2);
    }

    @Test
    void globalGreedyOrderBeatsLineOrder() {
        long account = newAccount("EUR", true);
        long early = posting(account, "3.00", utc(2036, 7, 10, 9, 0));
        long late = posting(account, "3.00", utc(2036, 7, 12, 9, 0));
        // line x (created first) is 1 day from both postings, line y is exactly on the late posting.
        long x = line(account, "x", LocalDate.of(2036, 7, 11), "3.00");
        long y = line(account, "y", LocalDate.of(2036, 7, 12), "3.00");

        reconciliation.reconcile(account);

        assertThat(matched(y)).isEqualTo(late);
        assertThat(matched(x)).isEqualTo(early);
    }

    @Test
    void windowIsTwoUtcDaysAndOnlySameAccountAndAmountCount() {
        long account = newAccount("EUR", true);
        long other = newAccount("EUR", true);
        long exactlyTwoDays = posting(account, "11.00", utc(2036, 8, 1, 23, 59)); // Aug 1 UTC
        long threeDays = posting(account, "12.00", utc(2036, 8, 1, 0, 0));        // Aug 1 UTC
        posting(account, "13.00", utc(2036, 8, 5, 12, 0));
        posting(other, "14.00", utc(2036, 8, 5, 12, 0));
        long l1 = line(account, "two", LocalDate.of(2036, 8, 3), "11.00");
        long l2 = line(account, "three", LocalDate.of(2036, 8, 4), "12.00");
        long l3 = line(account, "amount differs", LocalDate.of(2036, 8, 5), "13.01");
        long l4 = line(account, "wrong account", LocalDate.of(2036, 8, 5), "14.00");
        // midnight: a posting at 00:00 UTC belongs to its own date; Aug 8 00:00 is 2 days from the Aug 10 line
        long midnight = posting(account, "15.00", utc(2036, 8, 8, 0, 0));
        long l5 = line(account, "midnight", LocalDate.of(2036, 8, 10), "15.00");
        // and 23:59 on Aug 7 is 3 days away from Aug 10
        posting(account, "16.00", utc(2036, 8, 7, 23, 59));
        long l6 = line(account, "late evening", LocalDate.of(2036, 8, 10), "16.00");

        int matchedLines = reconciliation.reconcile(account);

        assertThat(matchedLines).isEqualTo(2);
        assertThat(matched(l1)).isEqualTo(exactlyTwoDays);
        assertThat(matched(l2)).isNull();
        assertThat(matched(l3)).isNull();
        assertThat(matched(l4)).isNull();
        assertThat(matched(l5)).isEqualTo(midnight);
        assertThat(matched(l6)).isNull();
        assertThat(threeDays).isPositive();
    }

    @Test
    void secondRunChangesNothingAndReportListsTheRest() {
        long account = newAccount("EUR", true);
        long p1 = posting(account, "21.00", utc(2036, 9, 10, 10, 0));
        long p2 = posting(account, "22.00", utc(2036, 9, 11, 10, 0));
        long unmatchedPosting = posting(account, "23.00", utc(2036, 9, 12, 10, 0));
        long outsideRange = posting(account, "24.00", utc(2036, 10, 20, 10, 0));
        long l1 = line(account, "l1", LocalDate.of(2036, 9, 10), "21.00");
        long l2 = line(account, "l2", LocalDate.of(2036, 9, 12), "22.00");
        long lonelyLine = line(account, "lonely", LocalDate.of(2036, 9, 14), "99.00");

        assertThat(reconciliation.reconcile(account)).isEqualTo(2);
        Map<Long, Long> afterFirst = Map.of(l1, matched(l1), l2, matched(l2), lonelyLine,
                java.util.Optional.ofNullable(matched(lonelyLine)).orElse(-1L));
        assertThat(afterFirst.get(l1)).isEqualTo(p1);
        assertThat(afterFirst.get(l2)).isEqualTo(p2);

        assertThat(reconciliation.reconcile(account)).isZero();
        assertThat(reconciliation.reconcile()).isZero(); // all accounts
        assertThat(matched(l1)).isEqualTo(p1);
        assertThat(matched(l2)).isEqualTo(p2);
        assertThat(matched(lonelyLine)).isNull();

        ReconciliationService.Differences diff = reconciliation.differences(account,
                LocalDate.of(2036, 9, 1), LocalDate.of(2036, 9, 30));
        assertThat(diff.linesWithoutPosting()).extracting(ReconciliationService.OpenLine::lineId)
                .containsExactly(lonelyLine);
        assertThat(diff.postingsWithoutLine()).extracting(ReconciliationService.OpenPosting::postingId)
                .containsExactly(unmatchedPosting);

        // the range bounds are inclusive and by UTC date
        ReconciliationService.Differences wide = reconciliation.differences(account,
                LocalDate.of(2036, 9, 12), LocalDate.of(2036, 10, 20));
        assertThat(wide.postingsWithoutLine()).extracting(ReconciliationService.OpenPosting::postingId)
                .containsExactly(unmatchedPosting, outsideRange);
        assertThat(wide.linesWithoutPosting()).extracting(ReconciliationService.OpenLine::lineId)
                .containsExactly(lonelyLine);
        assertThat(reconciliation.differences(account, LocalDate.of(2036, 9, 13), LocalDate.of(2036, 9, 13))
                .postingsWithoutLine()).isEmpty();
    }

    @Test
    void aPostingAlreadyMatchedIsNotTakenAgainByALaterLine() {
        long account = newAccount("EUR", true);
        long p = posting(account, "31.00", utc(2036, 11, 10, 10, 0));
        long first = line(account, "first", LocalDate.of(2036, 11, 10), "31.00");
        assertThat(reconciliation.reconcile(account)).isEqualTo(1);
        long later = line(account, "later", LocalDate.of(2036, 11, 10), "31.00");

        assertThat(reconciliation.reconcile(account)).isZero();

        assertThat(matched(first)).isEqualTo(p);
        assertThat(matched(later)).isNull();
        assertThat(List.of(first, later)).hasSize(2);
    }
}
