package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static dev.rabauer.ledger.jooq.Tables.EXCHANGE_RATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TransferServiceTest extends AbstractLedgerTest {

    @Autowired
    TransferService transfers;

    @Test
    void transferMovesMoneyAndBalances() {
        long c = customer("alice");
        long a = account(c, "EUR", false);
        long b = account(c, "EUR", false);
        deposit(transfers, a, "EUR", "100.00");

        transfers.transfer(a, b, new BigDecimal("30.25"), "rent");

        assertThat(balance(a)).isEqualByComparingTo("69.75");
        assertThat(balance(b)).isEqualByComparingTo("30.25");
        assertAllTransactionsBalanced();
    }

    @Test
    void overdrawingAnAccountThatForbidsItIsRejectedAndWritesNothing() {
        long c = customer("bob");
        long a = account(c, "EUR", false);
        long b = account(c, "EUR", false);
        deposit(transfers, a, "EUR", "10.00");
        long before = postingCount(a) + postingCount(b);

        assertThatThrownBy(() -> transfers.transfer(a, b, new BigDecimal("10.01"), "too much"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balance(a)).isEqualByComparingTo("10.00");
        assertThat(postingCount(a) + postingCount(b)).isEqualTo(before);
        // spending exactly the balance is fine
        transfers.transfer(a, b, new BigDecimal("10.00"), "all of it");
        assertThat(balance(a)).isEqualByComparingTo("0");
    }

    @Test
    void overdraftAccountMayGoNegative() {
        long c = customer("carol");
        long a = account(c, "EUR", true);
        long b = account(c, "EUR", false);

        transfers.transfer(a, b, new BigDecimal("5.00"), "credit");

        assertThat(balance(a)).isEqualByComparingTo("-5.00");
    }

    @Test
    void rejectsInvalidTransfers() {
        long c = customer("dave");
        long eur = account(c, "EUR", true);
        long eur2 = account(c, "EUR", true);
        long usd = account(c, "USD", true);

        assertThatThrownBy(() -> transfers.transfer(eur, eur2, BigDecimal.ZERO, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfers.transfer(eur, eur2, new BigDecimal("-1"), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfers.transfer(eur, eur, BigDecimal.ONE, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfers.transfer(eur, usd, BigDecimal.ONE, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfers.transfer(eur, -42L, BigDecimal.ONE, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manyConcurrentTransfersKeepTotalAndNeverOverdraw() throws Exception {
        long c = customer("concurrent");
        List<Long> accounts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long id = account(c, "EUR", false);
            deposit(transfers, id, "EUR", "100.00");
            accounts.add(id);
        }
        BigDecimal totalBefore = accounts.stream().map(this::balance).reduce(BigDecimal.ZERO, BigDecimal::add);

        int tasks = 400;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            long seed = i;
            Callable<Boolean> task = () -> {
                Random r = new Random(seed);
                int from = r.nextInt(accounts.size());
                int to = (from + 1 + r.nextInt(accounts.size() - 1)) % accounts.size();
                // amounts large enough that many transfers hit the overdraft rule
                BigDecimal amount = BigDecimal.valueOf(1 + r.nextInt(60));
                try {
                    transfers.transfer(accounts.get(from), accounts.get(to), amount, "concurrent " + seed);
                    return true;
                } catch (InsufficientFundsException e) {
                    return false;
                }
            };
            results.add(pool.submit(task));
        }
        long succeeded = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isPositive().isLessThan(tasks);
        BigDecimal totalAfter = accounts.stream().map(this::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalAfter).isEqualByComparingTo(totalBefore);
        accounts.forEach(id -> assertThat(balance(id)).isGreaterThanOrEqualTo(BigDecimal.ZERO));
        assertAllTransactionsBalanced();
    }

    @Test
    void crossCurrencyUsesTheRateValidAtBookingTime() {
        OffsetDateTime t0 = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime t1 = t0.plusDays(10);
        OffsetDateTime t2 = t0.plusDays(20);
        // use an exotic pair so other tests are unaffected: CHF -> JPY
        dsl.insertInto(EXCHANGE_RATE).set(EXCHANGE_RATE.BASE_CURRENCY, "CHF").set(EXCHANGE_RATE.QUOTE_CURRENCY, "JPY")
                .set(EXCHANGE_RATE.VALID_FROM, t0).set(EXCHANGE_RATE.RATE, new BigDecimal("100.00000000")).execute();
        dsl.insertInto(EXCHANGE_RATE).set(EXCHANGE_RATE.BASE_CURRENCY, "CHF").set(EXCHANGE_RATE.QUOTE_CURRENCY, "JPY")
                .set(EXCHANGE_RATE.VALID_FROM, t1).set(EXCHANGE_RATE.RATE, new BigDecimal("110.50000000")).execute();
        dsl.insertInto(EXCHANGE_RATE).set(EXCHANGE_RATE.BASE_CURRENCY, "CHF").set(EXCHANGE_RATE.QUOTE_CURRENCY, "JPY")
                .set(EXCHANGE_RATE.VALID_FROM, t2).set(EXCHANGE_RATE.RATE, new BigDecimal("999.00000000")).execute();

        long c = customer("fx");
        long chf = account(c, "CHF", false);
        long jpy = account(c, "JPY", false);
        deposit(transfers, chf, "CHF", "1000.00");

        transfers.transferCrossCurrency(chf, jpy, new BigDecimal("10.00"), "early", t0.plusDays(5));
        assertThat(balance(jpy)).isEqualByComparingTo("1000");

        transfers.transferCrossCurrency(chf, jpy, new BigDecimal("10.00"), "later", t1.plusDays(5));
        assertThat(balance(jpy)).isEqualByComparingTo("2105"); // 1000 + 1105
        assertThat(balance(chf)).isEqualByComparingTo("980.00");

        // rate valid exactly from t1 counts (valid_from <= booking time)
        transfers.transferCrossCurrency(chf, jpy, new BigDecimal("1.00"), "boundary", t1);
        // 1.00 * 110.5 = 110.5, rounded HALF_EVEN to JPY's 0 decimals = 110
        assertThat(balance(jpy)).isEqualByComparingTo("2215");
        assertAllTransactionsBalanced();
    }

    @Test
    void crossCurrencyRejectsOverdraftAndMissingRate() {
        long c = customer("fx2");
        long eur = account(c, "EUR", false);
        long usd = account(c, "USD", false);
        OffsetDateTime now = OffsetDateTime.now();
        dsl.insertInto(EXCHANGE_RATE).set(EXCHANGE_RATE.BASE_CURRENCY, "EUR").set(EXCHANGE_RATE.QUOTE_CURRENCY, "USD")
                .set(EXCHANGE_RATE.VALID_FROM, now.minusYears(30)).set(EXCHANGE_RATE.RATE, new BigDecimal("1.1"))
                .onConflictDoNothing().execute();
        deposit(transfers, eur, "EUR", "5.00");

        assertThatThrownBy(() -> transfers.transferCrossCurrency(eur, usd, new BigDecimal("6.00"), "x", now))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(balance(eur)).isEqualByComparingTo("5.00");

        // before any rate was valid
        assertThatThrownBy(() -> transfers.transferCrossCurrency(eur, usd, BigDecimal.ONE, "x", now.minusYears(40)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No exchange rate");
        assertThat(dsl.fetchCount(ACCOUNT, ACCOUNT.ID.eq(eur))).isEqualTo(1);
    }
}
