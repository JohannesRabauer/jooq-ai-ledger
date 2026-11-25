package dev.rabauer.ledger.service;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static dev.rabauer.ledger.jooq.Tables.ACCOUNT_GROUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GroupServiceTest extends AbstractLedgerTest {

    @Autowired
    GroupService groups;

    long newGroup(Long parent, String name) {
        return dsl.insertInto(ACCOUNT_GROUP)
                .set(ACCOUNT_GROUP.PARENT_ID, parent)
                .set(ACCOUNT_GROUP.NAME, name)
                .returningResult(ACCOUNT_GROUP.ID).fetchSingle().value1();
    }

    /** An account in the group, funded with the given amount (nothing is booked for a null amount). */
    long accountIn(long group, String currency, String funding) {
        long customer = newCustomer("Group customer");
        long account = dsl.insertInto(ACCOUNT)
                .set(ACCOUNT.CUSTOMER_ID, customer)
                .set(ACCOUNT.IBAN, randomIban())
                .set(ACCOUNT.CURRENCY, currency)
                .set(ACCOUNT.GROUP_ID, group)
                .returningResult(ACCOUNT.ID).fetchSingle().value1();
        if (funding != null) {
            deposit(account, currency, funding);
        }
        return account;
    }

    private static GroupService.GroupBalance row(List<GroupService.GroupBalance> rows, String path, String currency) {
        return rows.stream()
                .filter(r -> r.path().equals(path) && java.util.Objects.equals(r.currency(), currency))
                .findFirst().orElseThrow(() -> new AssertionError("no row " + path + " " + currency + " in " + rows));
    }

    @Test
    void rollupOverThreeLevelsWithAccountsAtSeveralLevelsAndCurrencies() {
        long bank = newGroup(null, "Bank");
        long retail = newGroup(bank, "Retail");
        long zurich = newGroup(retail, "Zurich");
        long corporate = newGroup(bank, "Corporate");
        long emptyBranch = newGroup(retail, "Bern");
        // a second tree that must not show up
        long other = newGroup(null, "Other");
        accountIn(other, "CHF", "999999.00");

        accountIn(bank, "CHF", "10.00");
        accountIn(retail, "CHF", "20.00");
        accountIn(retail, "EUR", "5.00");
        accountIn(zurich, "CHF", "100.00");
        accountIn(zurich, "CHF", "1.00");
        accountIn(zurich, "EUR", "7.00");
        accountIn(corporate, "EUR", "1000.00");
        accountIn(corporate, "EUR", null); // account without postings

        List<GroupService.GroupBalance> rows = groups.rollup(bank);

        assertThat(rows).extracting(GroupService.GroupBalance::path).doesNotContain("Other");
        assertThat(rows).extracting(GroupService.GroupBalance::groupId)
                .containsOnly(bank, retail, zurich, corporate, emptyBranch);

        GroupService.GroupBalance bankChf = row(rows, "Bank", "CHF");
        assertThat(bankChf.depth()).isZero();
        assertThat(bankChf.ownBalance()).isEqualByComparingTo("10.00");
        assertThat(bankChf.subtreeBalance()).isEqualByComparingTo("131.00");
        GroupService.GroupBalance bankEur = row(rows, "Bank", "EUR");
        assertThat(bankEur.ownBalance()).isEqualByComparingTo("0");
        assertThat(bankEur.subtreeBalance()).isEqualByComparingTo("1012.00"); // never mixed with CHF

        GroupService.GroupBalance retailChf = row(rows, "Bank / Retail", "CHF");
        assertThat(retailChf.depth()).isEqualTo(1);
        assertThat(retailChf.ownBalance()).isEqualByComparingTo("20.00");
        assertThat(retailChf.subtreeBalance()).isEqualByComparingTo("121.00");
        assertThat(row(rows, "Bank / Retail", "EUR").subtreeBalance()).isEqualByComparingTo("12.00");

        GroupService.GroupBalance zurichChf = row(rows, "Bank / Retail / Zurich", "CHF");
        assertThat(zurichChf.depth()).isEqualTo(2);
        assertThat(zurichChf.ownBalance()).isEqualByComparingTo("101.00");
        assertThat(zurichChf.subtreeBalance()).isEqualByComparingTo("101.00");
        assertThat(row(rows, "Bank / Retail / Zurich", "EUR").ownBalance()).isEqualByComparingTo("7.00");

        assertThat(row(rows, "Bank / Corporate", "EUR").subtreeBalance()).isEqualByComparingTo("1000.00");
        assertThat(rows.stream().filter(r -> r.path().equals("Bank / Corporate"))).hasSize(1); // no CHF row

        // a group without any account in its subtree still shows up, without currency
        GroupService.GroupBalance bern = row(rows, "Bank / Retail / Bern", null);
        assertThat(bern.depth()).isEqualTo(2);
        assertThat(bern.subtreeBalance()).isEqualByComparingTo("0");

        // starting from a sub-group gives the subtree only, with the path relative to it
        List<GroupService.GroupBalance> sub = groups.rollup(retail);
        assertThat(sub).extracting(GroupService.GroupBalance::path)
                .contains("Retail", "Retail / Zurich").doesNotContain("Bank");
        assertThat(sub.stream().filter(r -> r.groupId() == retail).findFirst().orElseThrow().depth()).isZero();
    }

    @Test
    void moveChangesPathsAndRollup() {
        long bank = newGroup(null, "Bank");
        long retail = newGroup(bank, "Retail");
        long zurich = newGroup(retail, "Zurich");
        long corporate = newGroup(bank, "Corporate");
        accountIn(zurich, "CHF", "50.00");

        groups.moveGroup(zurich, corporate);

        List<GroupService.GroupBalance> rows = groups.rollup(bank);
        assertThat(row(rows, "Bank / Corporate / Zurich", "CHF").depth()).isEqualTo(2);
        assertThat(row(rows, "Bank / Corporate", "CHF").subtreeBalance()).isEqualByComparingTo("50.00");
        assertThat(rows.stream().filter(r -> r.path().equals("Bank / Retail")).findFirst().orElseThrow()
                .subtreeBalance()).isEqualByComparingTo("0");

        groups.moveGroup(zurich, null); // becomes a root
        assertThat(groups.rollup(zurich)).extracting(GroupService.GroupBalance::path).containsOnly("Zurich");
    }

    @Test
    void rejectsMovesThatCreateACycle() {
        long a = newGroup(null, "A");
        long b = newGroup(a, "B");
        long c = newGroup(b, "C");

        assertThatThrownBy(() -> groups.moveGroup(a, c)).isInstanceOf(LedgerException.class)
                .hasMessageContaining("cycle");
        assertThatThrownBy(() -> groups.moveGroup(a, b)).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> groups.moveGroup(b, b)).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> groups.moveGroup(a, -1L)).isInstanceOf(LedgerException.class);

        // nothing changed
        assertThat(dsl.select(ACCOUNT_GROUP.PARENT_ID).from(ACCOUNT_GROUP).where(ACCOUNT_GROUP.ID.eq(a))
                .fetchSingle().value1()).isNull();
        assertThat(groups.rollup(a)).extracting(GroupService.GroupBalance::groupId).containsOnly(a, b, c);
    }

    @Test
    void concurrentOppositeMovesCannotCreateACycle() throws Exception {
        int rounds = 25;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                long a = newGroup(null, "RA" + i);
                long b = newGroup(null, "RB" + i);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger ok = new AtomicInteger();
                List<Future<?>> futures = new ArrayList<>();
                futures.add(pool.submit(() -> move(start, ok, a, b)));
                futures.add(pool.submit(() -> move(start, ok, b, a)));
                start.countDown();
                for (Future<?> f : futures) {
                    f.get();
                }
                // exactly one of the two moves may win; the other would have closed the cycle
                assertThat(ok.get()).as("round %d", i).isEqualTo(1);
                // and the tree can still be walked from both ends without looping
                assertThat(groups.rollup(a)).isNotEmpty();
                assertThat(groups.rollup(b)).isNotEmpty();
            }
        } finally {
            pool.shutdown();
        }
    }

    private void move(CountDownLatch start, AtomicInteger ok, long group, long parent) {
        try {
            start.await();
            groups.moveGroup(group, parent);
            ok.incrementAndGet();
        } catch (LedgerException e) {
            // rejected: expected for one of the two
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
