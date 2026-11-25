package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT_GROUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.rabauer.ledger.AccountGroupService.CurrencyTotal;
import dev.rabauer.ledger.AccountGroupService.GroupRollup;

class AccountGroupServiceTest extends AbstractLedgerTest {

    @Autowired
    AccountGroupService groups;
    @Autowired
    TransferService transfers;

    private long bank;
    private long retail;
    private long zurich;
    private long geneva;
    private long corporate;

    /** Bank / Retail / Zurich, Bank / Retail / Geneva (no accounts), Bank / Corporate; accounts on several levels. */
    private void buildTree() {
        bank = group("Bank", null);
        retail = group("Retail", bank);
        zurich = group("Zurich", retail);
        geneva = group("Geneva", retail);
        corporate = group("Corporate", bank);
        long customer = customer("tree customer");
        deposit(transfers, account(customer, "CHF", bank), "CHF", "10");
        deposit(transfers, account(customer, "CHF", retail), "CHF", "20");
        deposit(transfers, account(customer, "EUR", retail), "EUR", "5");
        deposit(transfers, account(customer, "CHF", zurich), "CHF", "100");
        deposit(transfers, account(customer, "CHF", zurich), "CHF", "1");
        deposit(transfers, account(customer, "EUR", corporate), "EUR", "7");
        // an account of another tree must not leak in
        deposit(transfers, account(customer, "CHF", group("Other bank", null)), "CHF", "999");
    }

    private static Map<String, GroupRollup> byPath(List<GroupRollup> rollup) {
        return rollup.stream().collect(Collectors.toMap(GroupRollup::path, g -> g));
    }

    private static CurrencyTotal total(GroupRollup g, String currency) {
        return g.totals().stream().filter(t -> t.currency().equals(currency)).findFirst().orElseThrow();
    }

    @Test
    void rollupOverThreeLevelsWithDepthPathAndPerCurrencySums() {
        buildTree();
        List<GroupRollup> rollup = groups.rollup(bank);
        assertThat(rollup).hasSize(5);
        Map<String, GroupRollup> g = byPath(rollup);
        assertThat(g.keySet()).containsExactlyInAnyOrder("Bank", "Bank / Retail", "Bank / Retail / Zurich",
                "Bank / Retail / Geneva", "Bank / Corporate");
        assertThat(g.get("Bank").depth()).isZero();
        assertThat(g.get("Bank / Retail").depth()).isEqualTo(1);
        assertThat(g.get("Bank / Retail / Zurich").depth()).isEqualTo(2);
        assertThat(g.get("Bank / Retail / Zurich").groupId()).isEqualTo(zurich);
        assertThat(g.get("Bank / Retail / Zurich").parentId()).isEqualTo(retail);

        // root: own CHF 10 only; the whole tree holds CHF 131 and EUR 12, never summed together
        GroupRollup root = g.get("Bank");
        assertThat(root.totals()).hasSize(2);
        assertThat(total(root, "CHF").own()).isEqualByComparingTo("10");
        assertThat(total(root, "CHF").subtree()).isEqualByComparingTo("131");
        assertThat(total(root, "EUR").own()).isEqualByComparingTo("0");
        assertThat(total(root, "EUR").subtree()).isEqualByComparingTo("12");

        GroupRollup r = g.get("Bank / Retail");
        assertThat(total(r, "CHF").own()).isEqualByComparingTo("20");
        assertThat(total(r, "CHF").subtree()).isEqualByComparingTo("121");
        assertThat(total(r, "EUR").own()).isEqualByComparingTo("5");
        assertThat(total(r, "EUR").subtree()).isEqualByComparingTo("5");

        GroupRollup z = g.get("Bank / Retail / Zurich");
        assertThat(z.totals()).hasSize(1);
        assertThat(total(z, "CHF").own()).isEqualByComparingTo("101");
        assertThat(total(z, "CHF").subtree()).isEqualByComparingTo("101");

        assertThat(g.get("Bank / Retail / Geneva").totals()).isEmpty();

        GroupRollup c = g.get("Bank / Corporate");
        assertThat(c.totals()).hasSize(1);
        assertThat(total(c, "EUR").subtree()).isEqualByComparingTo("7");
    }

    @Test
    void rollupOfAnInnerGroupStartsAtDepthZero() {
        buildTree();
        List<GroupRollup> rollup = groups.rollup(retail);
        assertThat(rollup).extracting(GroupRollup::path)
                .containsExactlyInAnyOrder("Retail", "Retail / Zurich", "Retail / Geneva");
        assertThat(byPath(rollup).get("Retail").depth()).isZero();
        assertThat(total(byPath(rollup).get("Retail"), "CHF").subtree()).isEqualByComparingTo("121");
    }

    @Test
    void moveRejectsCycles() {
        buildTree();
        assertThatThrownBy(() -> groups.moveGroup(bank, zurich)).isInstanceOf(GroupCycleException.class);
        assertThatThrownBy(() -> groups.moveGroup(retail, geneva)).isInstanceOf(GroupCycleException.class);
        assertThatThrownBy(() -> groups.moveGroup(retail, retail)).isInstanceOf(GroupCycleException.class);
        // nothing changed
        assertThat(parentOf(bank)).isNull();
        assertThat(parentOf(retail)).isEqualTo(bank);
    }

    @Test
    void moveRewritesPathsAndSums() {
        buildTree();
        groups.moveGroup(retail, corporate);
        Map<String, GroupRollup> g = byPath(groups.rollup(bank));
        assertThat(g).containsKeys("Bank / Corporate / Retail", "Bank / Corporate / Retail / Zurich");
        assertThat(g.get("Bank / Corporate / Retail / Zurich").depth()).isEqualTo(3);
        assertThat(total(g.get("Bank / Corporate"), "CHF").subtree()).isEqualByComparingTo("121");
        assertThat(total(g.get("Bank / Corporate"), "EUR").subtree()).isEqualByComparingTo("12");

        groups.moveGroup(zurich, null);
        assertThat(parentOf(zurich)).isNull();
        assertThatThrownBy(() -> groups.moveGroup(zurich, 987654321L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentOppositeMovesNeverCreateACycle() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                long a = group("A" + round, null);
                long b = group("B" + round, null);
                CyclicBarrier barrier = new CyclicBarrier(2);
                List<Future<Boolean>> results = new ArrayList<>();
                results.add(pool.submit(() -> move(barrier, a, b)));
                results.add(pool.submit(() -> move(barrier, b, a)));
                int succeeded = 0;
                for (Future<Boolean> f : results) {
                    if (f.get()) {
                        succeeded++;
                    }
                }
                assertThat(succeeded).as("round %d", round).isEqualTo(1);
                // exactly one of them is still a root, so there is no cycle
                assertThat(java.util.Arrays.asList(parentOf(a), parentOf(b)).stream().filter(p -> p == null).count()).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean move(CyclicBarrier barrier, long group, long newParent) throws Exception {
        barrier.await();
        try {
            groups.moveGroup(group, newParent);
            return true;
        } catch (GroupCycleException e) {
            return false;
        }
    }

    private Long parentOf(long groupId) {
        return dsl.select(ACCOUNT_GROUP.PARENT_ID).from(ACCOUNT_GROUP).where(ACCOUNT_GROUP.ID.eq(groupId))
                .fetchSingle(ACCOUNT_GROUP.PARENT_ID);
    }
}
