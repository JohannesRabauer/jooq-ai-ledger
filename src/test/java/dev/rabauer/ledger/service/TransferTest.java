package dev.rabauer.ledger.service;

import static dev.rabauer.ledger.jooq.Tables.EXCHANGE_RATE;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TransferTest extends AbstractLedgerTest {

    @Autowired
    StatementService statements;

    @Test
    void transferMovesMoneyAndPostingsSumToZero() {
        long a = newAccount("EUR", false);
        long b = newAccount("EUR", false);
        deposit(a, "EUR", "100.00");

        long tx = ledger.transfer(a, b, new BigDecimal("30.50"), "rent");

        assertThat(balance(a)).isEqualByComparingTo("69.50");
        assertThat(balance(b)).isEqualByComparingTo("30.50");
        BigDecimal sum = dsl.select(org.jooq.impl.DSL.sum(POSTING.AMOUNT)).from(POSTING)
                .where(POSTING.TRANSACTION_ID.eq(tx)).fetchSingle().value1();
        assertThat(sum).isEqualByComparingTo("0");
        assertThat(unbalancedTransactions()).isZero();
    }

    @Test
    void rejectsOverdrawAndLeavesNoTrace() {
        long a = newAccount("EUR", false);
        long b = newAccount("EUR", false);
        deposit(a, "EUR", "10.00");
        int postingsBefore = dsl.fetchCount(POSTING);

        assertThatThrownBy(() -> ledger.transfer(a, b, new BigDecimal("10.01"), "too much"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balance(a)).isEqualByComparingTo("10.00");
        assertThat(balance(b)).isEqualByComparingTo("0");
        assertThat(dsl.fetchCount(POSTING)).isEqualTo(postingsBefore);
    }

    @Test
    void overdraftAccountMayGoNegative() {
        long a = newAccount("EUR", true);
        long b = newAccount("EUR", false);
        ledger.transfer(a, b, new BigDecimal("5.00"), "credit line");
        assertThat(balance(a)).isEqualByComparingTo("-5.00");
    }

    @Test
    void rejectsBadInput() {
        long a = newAccount("EUR", true);
        long b = newAccount("EUR", true);
        long usd = newAccount("USD", true);
        assertThatThrownBy(() -> ledger.transfer(a, a, BigDecimal.ONE, "x")).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> ledger.transfer(a, b, BigDecimal.ZERO, "x")).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> ledger.transfer(a, b, new BigDecimal("-1"), "x")).isInstanceOf(LedgerException.class);
        // different currencies do not sum to zero per currency
        assertThatThrownBy(() -> ledger.transfer(a, usd, BigDecimal.ONE, "x")).isInstanceOf(LedgerException.class);
    }

    @Test
    void concurrentTransfersKeepTotalAndNeverOverdraw() throws Exception {
        int accountCount = 8;
        List<Long> accounts = new ArrayList<>();
        for (int i = 0; i < accountCount; i++) {
            long id = newAccount("EUR", false);
            deposit(id, "EUR", "100.00");
            accounts.add(id);
        }
        BigDecimal totalBefore = total(accounts);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            futures.add(pool.submit((Callable<Void>) () -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                int from = r.nextInt(accountCount);
                int to = (from + 1 + r.nextInt(accountCount - 1)) % accountCount;
                BigDecimal amount = BigDecimal.valueOf(r.nextInt(1, 6000), 2); // 0.01 .. 59.99
                try {
                    ledger.transfer(accounts.get(from), accounts.get(to), amount, "concurrent");
                    ok.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(); // propagates deadlocks or any other exception
        }
        pool.shutdown();

        assertThat(ok.get() + rejected.get()).isEqualTo(600);
        assertThat(ok.get()).isPositive();
        assertThat(total(accounts)).isEqualByComparingTo(totalBefore);
        for (long id : accounts) {
            assertThat(balance(id)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
        assertThat(unbalancedTransactions()).isZero();
    }

    @Test
    void concurrentTransfersOutOfOneAccountCannotOverdrawIt() throws Exception {
        long source = newAccount("EUR", false);
        long target = newAccount("EUR", false);
        deposit(source, "EUR", "100.00");

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            futures.add(pool.submit(() -> {
                try {
                    ledger.transfer(source, target, new BigDecimal("10.00"), "race");
                    return true;
                } catch (InsufficientFundsException e) {
                    return false;
                }
            }));
        }
        int succeeded = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(10);
        assertThat(balance(source)).isEqualByComparingTo("0");
        assertThat(balance(target)).isEqualByComparingTo("100.00");
    }

    @Test
    void crossCurrencyUsesTheRateValidAtBookingTime() {
        long eur = newAccount("EUR", false);
        long chf = newAccount("CHF", false);
        deposit(eur, "EUR", "1000.00");
        OffsetDateTime now = OffsetDateTime.now();
        // an old rate, a current rate and a future rate: only the current one may be used
        insertRate("EUR", "CHF", now.minusDays(10), "0.90000000");
        insertRate("EUR", "CHF", now.minusDays(1), "0.95123456");
        insertRate("EUR", "CHF", now.plusDays(1), "5.00000000");

        ledger.transferCrossCurrency(eur, chf, new BigDecimal("100.00"), "fx");

        assertThat(balance(eur)).isEqualByComparingTo("900.00");
        assertThat(balance(chf)).isEqualByComparingTo("95.12"); // 95.123456 rounded to 2 decimals
        assertThat(unbalancedTransactions()).isZero();
    }

    @Test
    void crossCurrencyRespectsOverdraftAndMissingRate() {
        long eur = newAccount("EUR", false);
        long jpy = newAccount("JPY", false);
        deposit(eur, "EUR", "10.00");
        insertRate("EUR", "JPY", OffsetDateTime.now().minusHours(1), "160.00000000");

        assertThatThrownBy(() -> ledger.transferCrossCurrency(eur, jpy, new BigDecimal("10.01"), "fx"))
                .isInstanceOf(InsufficientFundsException.class);

        long usd = newAccount("USD", false);
        assertThatThrownBy(() -> ledger.transferCrossCurrency(eur, usd, new BigDecimal("1.00"), "fx"))
                .isInstanceOf(LedgerException.class).hasMessageContaining("No exchange rate");

        ledger.transferCrossCurrency(eur, jpy, new BigDecimal("1.50"), "fx");
        assertThat(balance(jpy)).isEqualByComparingTo("240"); // JPY has no minor units
    }

    @Test
    void statementHasRunningBalanceAndKeysetPages() {
        long a = newAccount("EUR", false);
        long b = newAccount("EUR", false);
        OffsetDateTime t0 = OffsetDateTime.now().minusDays(5);
        deposit(a, "EUR", "100.00", t0, "d1");
        deposit(a, "EUR", "50.00", t0.plusHours(1), "d2");
        ledger.transfer(a, b, new BigDecimal("30.00"), "out1");
        ledger.transfer(a, b, new BigDecimal("20.00"), "out2");
        ledger.transfer(b, a, new BigDecimal("5.00"), "back");

        StatementService.Page p1 = statements.statement(a, null, 2);
        assertThat(p1.lines()).extracting(StatementService.Line::description).containsExactly("d1", "d2");
        assertThat(p1.lines()).extracting(StatementService.Line::balanceAfter)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("100.00"), new BigDecimal("150.00"));
        assertThat(p1.next()).isNotNull();

        StatementService.Page p2 = statements.statement(a, p1.next(), 2);
        assertThat(p2.lines()).extracting(StatementService.Line::description).containsExactly("out1", "out2");
        // running balance continues across pages
        assertThat(p2.lines()).extracting(StatementService.Line::balanceAfter)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("120.00"), new BigDecimal("100.00"));

        StatementService.Page p3 = statements.statement(a, p2.next(), 2);
        assertThat(p3.lines()).extracting(StatementService.Line::description).containsExactly("back");
        assertThat(p3.lines().getFirst().balanceAfter()).isEqualByComparingTo("105.00");
        assertThat(p3.next()).isNull();
        assertThat(balance(a)).isEqualByComparingTo("105.00");
    }

    private BigDecimal total(List<Long> accounts) {
        return dsl.select(org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.sum(POSTING.AMOUNT), BigDecimal.ZERO))
                .from(POSTING).where(POSTING.ACCOUNT_ID.in(accounts)).fetchSingle().value1();
    }

    private void insertRate(String base, String quote, OffsetDateTime from, String rate) {
        dsl.insertInto(EXCHANGE_RATE)
                .set(EXCHANGE_RATE.BASE_CURRENCY, base)
                .set(EXCHANGE_RATE.QUOTE_CURRENCY, quote)
                .set(EXCHANGE_RATE.VALID_FROM, from)
                .set(EXCHANGE_RATE.RATE, new BigDecimal(rate))
                .onConflictDoNothing()
                .execute();
    }
}
