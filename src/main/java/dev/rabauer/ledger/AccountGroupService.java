package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static dev.rabauer.ledger.jooq.Tables.ACCOUNT_GROUP;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.sum;
import static org.jooq.impl.DSL.table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jooq.CommonTableExpression;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

@Service
public class AccountGroupService {

    /** Own = balance of the accounts directly in the group, subtree = including all descendant groups. */
    public record CurrencyTotal(String currency, BigDecimal own, BigDecimal subtree) {
    }

    /** A group of the subtree. {@code totals} has one entry per currency that occurs in its subtree. */
    public record GroupRollup(long groupId, Long parentId, int depth, String path, List<CurrencyTotal> totals) {
    }

    private static final String SEPARATOR = " / ";
    /** Guards the recursion against a (never expected) cycle in the data. */
    private static final int MAX_DEPTH = 100;

    private static final Table<Record> TREE = table(name("tree"));
    private static final Field<Long> T_ID = field(name("tree", "id"), Long.class);
    private static final Field<Long> T_PARENT = field(name("tree", "parent_id"), Long.class);
    private static final Field<Integer> T_DEPTH = field(name("tree", "depth"), Integer.class);
    private static final Field<String> T_PATH = field(name("tree", "path"), String.class);

    private static final Table<Record> CLOSURE = table(name("closure"));
    private static final Field<Long> C_ANCESTOR = field(name("closure", "ancestor_id"), Long.class);
    private static final Field<Long> C_ID = field(name("closure", "id"), Long.class);

    private static final Field<Long> A_GROUP = field(name("acct", "group_id"), Long.class);
    private static final Field<String> A_CURRENCY = field(name("acct", "currency"), String.class);
    private static final Field<BigDecimal> A_OWN = field(name("acct", "own"), BigDecimal.class);

    private static final Field<Long> S_ANCESTOR = field(name("subtree", "ancestor_id"), Long.class);
    private static final Field<String> S_CURRENCY = field(name("subtree", "currency"), String.class);
    private static final Field<BigDecimal> S_AMOUNT = field(name("subtree", "amount"), BigDecimal.class);

    private final DSLContext dsl;

    public AccountGroupService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Every group in the subtree of {@code rootGroupId} (the root included) with depth, path and per currency
     * the balance of its own accounts and of its whole subtree. One statement; ordered by path, then currency.
     * Amounts of different currencies are never added.
     */
    public List<GroupRollup> rollup(long rootGroupId) {
        Map<Long, GroupRollup> byGroup = new LinkedHashMap<>();
        for (Record r : rollupQuery(dsl, rootGroupId).fetch()) {
            long groupId = r.get(T_ID);
            GroupRollup group = byGroup.computeIfAbsent(groupId, id -> new GroupRollup(id, r.get(T_PARENT),
                    r.get(T_DEPTH), r.get(T_PATH), new ArrayList<>()));
            String currency = r.get(S_CURRENCY);
            if (currency != null) {
                group.totals().add(new CurrencyTotal(currency, r.get(A_OWN), r.get(S_AMOUNT)));
            }
        }
        return List.copyOf(byGroup.values());
    }

    /** The roll-up SQL as rendered for {@code dialect} (bind variables as placeholders), nothing is executed. */
    public String rollupSql(SQLDialect dialect, long rootGroupId) {
        return rollupQuery(DSL.using(dialect), rootGroupId).getSQL();
    }

    private static ResultQuery<? extends Record> rollupQuery(DSLContext ctx, long rootGroupId) {
        // tree: the subtree of the root with depth and path
        CommonTableExpression<?> tree = name("tree").fields("id", "parent_id", "name", "depth", "path").as(
                select(ACCOUNT_GROUP.ID, ACCOUNT_GROUP.PARENT_ID, ACCOUNT_GROUP.NAME, inline(0), ACCOUNT_GROUP.NAME)
                        .from(ACCOUNT_GROUP)
                        .where(ACCOUNT_GROUP.ID.eq(rootGroupId))
                        .unionAll(select(ACCOUNT_GROUP.ID, ACCOUNT_GROUP.PARENT_ID, ACCOUNT_GROUP.NAME,
                                T_DEPTH.plus(1), T_PATH.concat(inline(SEPARATOR)).concat(ACCOUNT_GROUP.NAME))
                                .from(TREE)
                                .join(ACCOUNT_GROUP).on(ACCOUNT_GROUP.PARENT_ID.eq(T_ID))
                                .where(T_DEPTH.lt(MAX_DEPTH))));

        // closure: (ancestor, descendant) for every group of the tree, the group itself included
        CommonTableExpression<?> closure = name("closure").fields("ancestor_id", "id").as(
                select(T_ID, T_ID).from(TREE)
                        .unionAll(select(C_ANCESTOR, ACCOUNT_GROUP.ID)
                                .from(CLOSURE)
                                .join(ACCOUNT_GROUP).on(ACCOUNT_GROUP.PARENT_ID.eq(C_ID))));

        // acct: balance per group and currency of the accounts directly in the group (empty accounts count as 0)
        CommonTableExpression<?> acct = name("acct").fields("group_id", "currency", "own").as(
                select(ACCOUNT.GROUP_ID, ACCOUNT.CURRENCY, coalesce(sum(POSTING.AMOUNT), BigDecimal.ZERO))
                        .from(ACCOUNT)
                        .leftJoin(POSTING).on(POSTING.ACCOUNT_ID.eq(ACCOUNT.ID))
                        .where(ACCOUNT.GROUP_ID.in(select(T_ID).from(TREE)))
                        .groupBy(ACCOUNT.GROUP_ID, ACCOUNT.CURRENCY));

        // subtree: per ancestor and currency the sum of the own balances of all its descendants
        CommonTableExpression<?> subtree = name("subtree").fields("ancestor_id", "currency", "amount").as(
                select(C_ANCESTOR, A_CURRENCY, sum(A_OWN))
                        .from(CLOSURE)
                        .join(table(name("acct"))).on(A_GROUP.eq(C_ID))
                        .groupBy(C_ANCESTOR, A_CURRENCY));

        return ctx.withRecursive(tree, closure).with(acct).with(subtree)
                .select(T_ID, T_PARENT, T_DEPTH, T_PATH, S_CURRENCY, coalesce(A_OWN, BigDecimal.ZERO).as("own"), S_AMOUNT)
                .from(TREE)
                .leftJoin(table(name("subtree"))).on(S_ANCESTOR.eq(T_ID))
                .leftJoin(table(name("acct"))).on(A_GROUP.eq(T_ID).and(A_CURRENCY.eq(S_CURRENCY)))
                .orderBy(T_PATH, S_CURRENCY);
    }

    /**
     * Moves {@code groupId} under {@code newParentId} (null makes it a root). Rejects a move that would create a
     * cycle. All group rows are locked in id order first, so two concurrent moves (A under B, B under A) are
     * serialized and the second one re-checks against the committed result of the first.
     */
    public void moveGroup(long groupId, Long newParentId) {
        dsl.transaction(cfg -> {
            DSLContext tx = cfg.dsl();
            // Row locks in id order: no deadlock. The tree is small (an organisation chart).
            tx.select(ACCOUNT_GROUP.ID).from(ACCOUNT_GROUP).orderBy(ACCOUNT_GROUP.ID).forUpdate().fetch();

            if (!tx.fetchExists(ACCOUNT_GROUP, ACCOUNT_GROUP.ID.eq(groupId))) {
                throw new IllegalArgumentException("Unknown group " + groupId);
            }
            if (newParentId != null) {
                if (!tx.fetchExists(ACCOUNT_GROUP, ACCOUNT_GROUP.ID.eq(newParentId))) {
                    throw new IllegalArgumentException("Unknown group " + newParentId);
                }
                if (createsCycle(tx, groupId, newParentId)) {
                    throw new GroupCycleException(groupId, newParentId);
                }
            }
            tx.update(ACCOUNT_GROUP).set(ACCOUNT_GROUP.PARENT_ID, newParentId)
                    .where(ACCOUNT_GROUP.ID.eq(groupId)).execute();
        });
    }

    /** A cycle appears exactly when the group is the new parent itself or one of the new parent's ancestors. */
    private static boolean createsCycle(DSLContext tx, long groupId, long newParentId) {
        CommonTableExpression<?> up = name("up").fields("id", "parent_id").as(
                select(ACCOUNT_GROUP.ID, ACCOUNT_GROUP.PARENT_ID).from(ACCOUNT_GROUP)
                        .where(ACCOUNT_GROUP.ID.eq(newParentId))
                        .unionAll(select(ACCOUNT_GROUP.ID, ACCOUNT_GROUP.PARENT_ID)
                                .from(table(name("up")))
                                .join(ACCOUNT_GROUP).on(ACCOUNT_GROUP.ID.eq(field(name("up", "parent_id"), Long.class)))));
        return tx.withRecursive(up)
                .select(field(exists(selectOne().from(table(name("up"))).where(field(name("up", "id"), Long.class).eq(groupId)))))
                .fetchSingle().value1();
    }
}
