package dev.rabauer.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DailyBalanceServiceTest extends AbstractLedgerTest {

    @Autowired
    DailyBalanceService daily;

    private static OffsetDateTime utc(int year, int month, int day, int hour, int minute, int second, int nano) {
        return OffsetDateTime.of(year, month, day, hour, minute, second, nano, ZoneOffset.UTC);
    }

    private DailyBalanceService.AccountDailyBalances of(List<DailyBalanceService.AccountDailyBalances> all, long id) {
        return all.stream().filter(a -> a.accountId() == id).findFirst().orElseThrow();
    }

    @Test
    void dayEndBalancesIncludeDaysWithoutPostingsAndMidnight() {
        YearMonth march = YearMonth.of(2032, 3);
        long account = newAccount("EUR", true);
        deposit(account, "EUR", "100.00", utc(2032, 2, 20, 12, 0, 0, 0), "opening, before the month");
        ledger.book("day 5 out", utc(2032, 3, 5, 12, 0, 0, 0), null,
                List.of(new PostingSpec(account, new BigDecimal("-30.00")),
                        new PostingSpec(newAccount("EUR", true), new BigDecimal("30.00"))));
        deposit(account, "EUR", "50.00", utc(2032, 3, 10, 0, 0, 0, 0), "exactly at midnight of day 10");
        ledger.book("last moment of March", utc(2032, 3, 31, 23, 59, 59, 999_000_000), null,
                List.of(new PostingSpec(account, new BigDecimal("-20.00")),
                        new PostingSpec(newAccount("EUR", true), new BigDecimal("20.00"))));
        deposit(account, "EUR", "1000.00", utc(2032, 4, 1, 0, 0, 0, 0), "April, must not count");

        DailyBalanceService.AccountDailyBalances r = of(daily.dailyBalances(march), account);

        assertThat(r.days()).hasSize(31);
        assertThat(r.days().getFirst().day()).isEqualTo(march.atDay(1));
        assertThat(r.days().getLast().day()).isEqualTo(march.atEndOfMonth());
        assertThat(balanceOn(r, 1)).isEqualByComparingTo("100.00");
        assertThat(balanceOn(r, 4)).isEqualByComparingTo("100.00"); // days without postings
        assertThat(balanceOn(r, 5)).isEqualByComparingTo("70.00");
        assertThat(balanceOn(r, 9)).isEqualByComparingTo("70.00"); // midnight posting not yet in day 9
        assertThat(balanceOn(r, 10)).isEqualByComparingTo("120.00"); // but in day 10
        assertThat(balanceOn(r, 30)).isEqualByComparingTo("120.00");
        assertThat(balanceOn(r, 31)).isEqualByComparingTo("100.00"); // 23:59:59.999 is still day 31
        // (4*100 + 5*70 + 21*120 + 100) / 31
        assertThat(r.average()).isCloseTo(new BigDecimal("3370").divide(new BigDecimal("31"), 10,
                java.math.RoundingMode.HALF_EVEN), within(new BigDecimal("0.0001")));
        assertThat(r.lowest()).isEqualByComparingTo("70.00");
    }

    @Test
    void midnightOfFirstDayCountsForDayOneAndQuietAccountsStayAtZero() {
        YearMonth feb = YearMonth.of(2033, 2); // 28 days
        long account = newAccount("EUR", true);
        deposit(account, "EUR", "10.00", utc(2033, 2, 1, 0, 0, 0, 0), "first instant of the month");
        deposit(account, "EUR", "5.00", utc(2033, 1, 31, 23, 59, 59, 999_000_000), "last instant of January");
        long quiet = newAccount("USD", false);

        List<DailyBalanceService.AccountDailyBalances> all = daily.dailyBalances(feb);

        DailyBalanceService.AccountDailyBalances r = of(all, account);
        assertThat(r.days()).hasSize(28);
        assertThat(balanceOn(r, 1)).isEqualByComparingTo("15.00");
        assertThat(r.lowest()).isEqualByComparingTo("15.00");
        assertThat(r.average()).isEqualByComparingTo("15.00");

        DailyBalanceService.AccountDailyBalances q = of(all, quiet);
        assertThat(q.currency()).isEqualTo("USD");
        assertThat(q.days()).hasSize(28).allSatisfy(d -> assertThat(d.balance()).isEqualByComparingTo("0"));
        assertThat(q.average()).isEqualByComparingTo("0");
        assertThat(q.lowest()).isEqualByComparingTo("0");
    }

    @Test
    void lowestDayEndBalanceCanBeNegativeAndIntradayDipsDoNotCount() {
        YearMonth month = YearMonth.of(2034, 6);
        long account = newAccount("EUR", true);
        long other = newAccount("EUR", true);
        // day 3: goes down to -40 and back to +10 within the day, only +10 is the day end
        ledger.book("out", utc(2034, 6, 3, 8, 0, 0, 0), null, List.of(
                new PostingSpec(account, new BigDecimal("-40.00")), new PostingSpec(other, new BigDecimal("40.00"))));
        ledger.book("in", utc(2034, 6, 3, 20, 0, 0, 0), null, List.of(
                new PostingSpec(account, new BigDecimal("50.00")), new PostingSpec(other, new BigDecimal("-50.00"))));
        // day 7: ends at -5
        ledger.book("out2", utc(2034, 6, 7, 9, 0, 0, 0), null, List.of(
                new PostingSpec(account, new BigDecimal("-15.00")), new PostingSpec(other, new BigDecimal("15.00"))));

        DailyBalanceService.AccountDailyBalances r = of(daily.dailyBalances(month), account);

        assertThat(r.days()).hasSize(30);
        assertThat(balanceOn(r, 2)).isEqualByComparingTo("0");
        assertThat(balanceOn(r, 3)).isEqualByComparingTo("10.00");
        assertThat(balanceOn(r, 7)).isEqualByComparingTo("-5.00");
        assertThat(r.lowest()).isEqualByComparingTo("-5.00");
        // 2 days at 0, 4 days at 10, 24 days at -5
        assertThat(r.average()).isCloseTo(new BigDecimal("-80").divide(new BigDecimal("30"), 10,
                java.math.RoundingMode.HALF_EVEN), within(new BigDecimal("0.0001")));
    }

    private static BigDecimal balanceOn(DailyBalanceService.AccountDailyBalances r, int day) {
        return r.days().get(day - 1).balance();
    }
}
