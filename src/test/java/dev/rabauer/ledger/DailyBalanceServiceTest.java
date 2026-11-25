package dev.rabauer.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.rabauer.ledger.DailyBalanceService.AccountDailyBalances;
import dev.rabauer.ledger.DailyBalanceService.DayBalance;

class DailyBalanceServiceTest extends AbstractLedgerTest {

    // March 2026 contains the European DST change (29 March): day boundaries must stay UTC.
    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    @Autowired
    DailyBalanceService balances;
    @Autowired
    TransferService transfers;

    private static OffsetDateTime utc(String iso) {
        return OffsetDateTime.parse(iso + "Z");
    }

    private AccountDailyBalances report(long accountId) {
        List<AccountDailyBalances> result = balances.report(MARCH, List.of(accountId));
        assertThat(result).hasSize(1);
        return result.getFirst();
    }

    private static BigDecimal balanceOn(AccountDailyBalances r, int dayOfMonth) {
        return r.days().stream().filter(d -> d.day().equals(LocalDate.of(2026, 3, dayOfMonth)))
                .map(DayBalance::endOfDayBalance).findFirst().orElseThrow();
    }

    @Test
    void dayEndBalancesCoverEveryDayAverageAndMinimum() {
        long account = account(customer("daily"), "CHF", false);
        bookAt(transfers, account, "CHF", "100", utc("2026-02-20T10:00:00"));  // opening balance
        bookAt(transfers, account, "CHF", "50", utc("2026-03-03T12:00:00"));
        bookAt(transfers, account, "CHF", "-30", utc("2026-03-10T00:00:00"));  // exactly midnight: counts for the 10th
        bookAt(transfers, account, "CHF", "-20", utc("2026-03-31T23:00:00"));
        bookAt(transfers, account, "CHF", "1000", utc("2026-04-01T00:00:00")); // next month, must not count

        AccountDailyBalances r = report(account);
        assertThat(r.currency()).isEqualTo("CHF");
        assertThat(r.days()).hasSize(31);
        assertThat(r.days().getFirst().day()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(r.days().getLast().day()).isEqualTo(LocalDate.of(2026, 3, 31));

        assertThat(balanceOn(r, 1)).isEqualByComparingTo("100");   // opening balance, no posting that day
        assertThat(balanceOn(r, 2)).isEqualByComparingTo("100");
        assertThat(balanceOn(r, 3)).isEqualByComparingTo("150");
        assertThat(balanceOn(r, 9)).isEqualByComparingTo("150");   // day without postings
        assertThat(balanceOn(r, 10)).isEqualByComparingTo("120");  // midnight posting belongs to the 10th
        assertThat(balanceOn(r, 29)).isEqualByComparingTo("120");  // DST day
        assertThat(balanceOn(r, 30)).isEqualByComparingTo("120");
        assertThat(balanceOn(r, 31)).isEqualByComparingTo("100");

        // 2*100 + 7*150 + 21*120 + 1*100 = 3870 over 31 days
        assertThat(r.average()).isCloseTo(new BigDecimal("3870").divide(new BigDecimal("31"), 10,
                java.math.RoundingMode.HALF_EVEN), org.assertj.core.data.Offset.offset(new BigDecimal("0.0001")));
        assertThat(r.lowest()).isEqualByComparingTo("100");
    }

    @Test
    void accountWithoutPostingsHasZeroEveryDay() {
        long account = account(customer("quiet"), "EUR", false);
        AccountDailyBalances r = report(account);
        assertThat(r.days()).hasSize(31);
        assertThat(r.days()).allSatisfy(d -> assertThat(d.endOfDayBalance()).isEqualByComparingTo("0"));
        assertThat(r.average()).isEqualByComparingTo("0");
        assertThat(r.lowest()).isEqualByComparingTo("0");
    }

    @Test
    void lowestCanBeNegativeAndMonthLengthFollowsTheMonth() {
        long account = account(customer("overdrawn"), "USD", true);
        bookAt(transfers, account, "USD", "-50", utc("2026-02-05T08:00:00"));
        bookAt(transfers, account, "USD", "80", utc("2026-02-14T08:00:00"));
        List<AccountDailyBalances> result = balances.report(YearMonth.of(2026, 2), List.of(account));
        AccountDailyBalances r = result.getFirst();
        assertThat(r.days()).hasSize(28);
        assertThat(r.lowest()).isEqualByComparingTo("-50");
        // 4 days at 0, 9 days at -50, 15 days at 30 = 0 - 450 + 450
        assertThat(r.average()).isEqualByComparingTo("0");
    }

    @Test
    void withoutFilterEveryAccountAppears() {
        long a = account(customer("one"), "CHF", false);
        long b = account(customer("two"), "JPY", false);
        List<Long> ids = balances.report(MARCH).stream().map(AccountDailyBalances::accountId).toList();
        assertThat(ids).contains(a, b);
    }
}
