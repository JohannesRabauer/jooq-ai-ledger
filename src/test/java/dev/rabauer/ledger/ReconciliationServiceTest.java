package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.BANK_LINE;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.rabauer.ledger.ReconciliationService.Unreconciled;

class ReconciliationServiceTest extends AbstractLedgerTest {

    @Autowired
    ReconciliationService reconciliation;
    @Autowired
    TransferService transfers;

    private static OffsetDateTime utc(String iso) {
        return OffsetDateTime.parse(iso + "Z");
    }

    private long newAccount() {
        return account(customer("recon"), "CHF", true);
    }

    /** Books the amount on the account and returns the id of the posting on that account. */
    private long post(long account, String amount, String bookedAtUtc) {
        bookAt(transfers, account, "CHF", amount, utc(bookedAtUtc));
        return dsl.select(POSTING.ID).from(POSTING).where(POSTING.ACCOUNT_ID.eq(account))
                .orderBy(POSTING.ID.desc()).limit(1).fetchSingle(POSTING.ID);
    }

    private long line(long account, String ref, String valueDate, String amount) {
        return dsl.insertInto(BANK_LINE).set(BANK_LINE.ACCOUNT_ID, account).set(BANK_LINE.EXTERNAL_REF, ref)
                .set(BANK_LINE.VALUE_DATE, LocalDate.parse(valueDate)).set(BANK_LINE.AMOUNT, new BigDecimal(amount))
                .returningResult(BANK_LINE.ID).fetchSingle(BANK_LINE.ID);
    }

    private Long matched(long lineId) {
        return dsl.select(BANK_LINE.MATCHED_POSTING_ID).from(BANK_LINE).where(BANK_LINE.ID.eq(lineId))
                .fetchSingle(BANK_LINE.MATCHED_POSTING_ID);
    }

    @Test
    void matchesSameAmountWithinTwoDaysOnly() {
        long account = newAccount();
        long posting = post(account, "100", "2026-03-10T12:00:00");
        long onTime = line(account, "ok", "2026-03-12", "100");        // 2 days: matches
        long account2 = newAccount();
        post(account2, "100", "2026-03-10T12:00:00");
        long tooFar = line(account2, "far", "2026-03-13", "100");      // 3 days: no
        long account3 = newAccount();
        post(account3, "100", "2026-03-10T12:00:00");
        long otherAmount = line(account3, "amount", "2026-03-10", "100.01");

        assertThat(reconciliation.reconcile(account)).isEqualTo(1);
        assertThat(matched(onTime)).isEqualTo(posting);
        assertThat(reconciliation.reconcile(account2)).isZero();
        assertThat(matched(tooFar)).isNull();
        assertThat(reconciliation.reconcile(account3)).isZero();
        assertThat(matched(otherAmount)).isNull();
    }

    @Test
    void postingsOfAnotherAccountNeverMatch() {
        long a = newAccount();
        long b = newAccount();
        post(a, "42", "2026-03-10T12:00:00");
        long lineOnB = line(b, "x", "2026-03-10", "42");
        assertThat(reconciliation.reconcile()).isGreaterThanOrEqualTo(0);
        assertThat(matched(lineOnB)).isNull();
    }

    @Test
    void twoLinesCompetingForOnePostingTheClosestWins() {
        long account = newAccount();
        long posting = post(account, "50", "2026-03-10T12:00:00");
        long far = line(account, "far", "2026-03-12", "50");    // 2 days
        long near = line(account, "near", "2026-03-10", "50");  // 0 days
        long mid = line(account, "mid", "2026-03-09", "50");    // 1 day

        assertThat(reconciliation.reconcile(account)).isEqualTo(1);
        assertThat(matched(near)).isEqualTo(posting);
        assertThat(matched(mid)).isNull();
        assertThat(matched(far)).isNull();
        assertThat(dsl.fetchCount(BANK_LINE, BANK_LINE.MATCHED_POSTING_ID.eq(posting))).isEqualTo(1);
    }

    @Test
    void closestFirstAcrossSeveralRoundsUsesEveryPostingAndLineOnce() {
        long account = newAccount();
        long p1 = post(account, "70", "2026-03-10T12:00:00");
        long p2 = post(account, "70", "2026-03-12T12:00:00");
        long la = line(account, "a", "2026-03-11", "70");   // 1 day to p1 and to p2
        long lb = line(account, "b", "2026-03-12", "70");   // 2 days to p1, 0 days to p2

        assertThat(reconciliation.reconcile(account)).isEqualTo(2);
        // lb takes p2 (distance 0) first, la is left with p1 in the next round
        assertThat(matched(lb)).isEqualTo(p2);
        assertThat(matched(la)).isEqualTo(p1);
    }

    @Test
    void ties_goToTheLowerIdsAndNothingIsUsedTwice() {
        long account = newAccount();
        long p1 = post(account, "9", "2026-03-10T12:00:00");
        long p2 = post(account, "9", "2026-03-10T13:00:00");
        long l1 = line(account, "1", "2026-03-10", "9");
        long l2 = line(account, "2", "2026-03-10", "9");
        long l3 = line(account, "3", "2026-03-10", "9");

        assertThat(reconciliation.reconcile(account)).isEqualTo(2);
        assertThat(matched(l1)).isEqualTo(p1);
        assertThat(matched(l2)).isEqualTo(p2);
        assertThat(matched(l3)).isNull();
    }

    @Test
    void bookingDateIsUtcNotSessionTimeZone() {
        long account = newAccount();
        // 23:30 UTC on the 9th: UTC day is the 9th (local Berlin time would already be the 10th)
        long posting = post(account, "5", "2026-03-09T23:30:00");
        long inRange = line(account, "in", "2026-03-07", "5");   // 2 days from the 9th, 3 days from the 10th
        // exactly midnight UTC belongs to the 10th
        long midnight = post(account, "6", "2026-03-10T00:00:00");
        long outOfRange = line(account, "out", "2026-03-07", "6"); // 3 days from the 10th

        assertThat(reconciliation.reconcile(account)).isEqualTo(1);
        assertThat(matched(inRange)).isEqualTo(posting);
        assertThat(matched(outOfRange)).isNull();
        assertThat(midnight).isNotEqualTo(posting);
    }

    @Test
    void secondRunChangesNothing() {
        long account = newAccount();
        post(account, "10", "2026-03-10T12:00:00");
        post(account, "20", "2026-03-11T12:00:00");
        post(account, "30", "2026-03-20T12:00:00");
        long l1 = line(account, "1", "2026-03-10", "10");
        long l2 = line(account, "2", "2026-03-11", "20");
        long l3 = line(account, "3", "2026-03-11", "99");

        assertThat(reconciliation.reconcile(account)).isEqualTo(2);
        Map<Long, Long> before = snapshot(l1, l2, l3);
        assertThat(reconciliation.reconcile(account)).isZero();
        assertThat(snapshot(l1, l2, l3)).isEqualTo(before);

        // a later line with the same amount cannot take an already matched posting
        long late = line(account, "late", "2026-03-10", "10");
        assertThat(reconciliation.reconcile(account)).isZero();
        assertThat(matched(late)).isNull();
    }

    private Map<Long, Long> snapshot(long... ids) {
        Map<Long, Long> m = new java.util.HashMap<>();
        for (long id : ids) {
            m.put(id, matched(id));
        }
        return m;
    }

    @Test
    void reportsUnmatchedLinesAndPostingsInRange() {
        long account = newAccount();
        long matchedPosting = post(account, "10", "2026-03-10T12:00:00");
        long lonelyPosting = post(account, "11", "2026-03-15T12:00:00");
        post(account, "12", "2026-04-15T12:00:00");                       // outside the range
        post(account, "13", "2026-03-31T23:30:00");                       // 31 March UTC, inside
        line(account, "matched", "2026-03-10", "10");
        long lonelyLine = line(account, "lonely", "2026-03-20", "77");
        line(account, "outside", "2026-04-02", "78");
        reconciliation.reconcile(account);

        Unreconciled u = reconciliation.unreconciled(account, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        assertThat(u.bankLinesWithoutPosting()).extracting(l -> l.lineId()).containsExactly(lonelyLine);
        assertThat(u.postingsWithoutBankLine()).extracting(p -> p.amount().intValue()).containsExactly(11, 13);
        assertThat(u.postingsWithoutBankLine().getFirst().postingId()).isEqualTo(lonelyPosting);
        assertThat(u.postingsWithoutBankLine()).extracting(p -> p.postingId()).doesNotContain(matchedPosting);
        assertThat(List.of(u.postingsWithoutBankLine().getFirst().description())).containsExactly("booking");
    }
}
