package dev.rabauer.ledger.service;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static dev.rabauer.ledger.jooq.Tables.ACCOUNT_GROUP;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.sum;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.when;

import java.math.BigDecimal;
import java.util.List;

import org.jooq.CommonTableExpression;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record6;
import org.jooq.Records;
import org.jooq.ResultQuery;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account group tree: roll-up of balances and cycle-safe moves. */
@Service
public class GroupService {

    /** Serializes all group moves, so two concurrent moves cannot together create a cycle. */
    private static final long GROUP_MOVE_LOCK = 7_002L;

    private final DSLContext dsl;

    public GroupService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * One row per group and currency. currency is null (balances zero) for a group whose subtree has no accounts.
     * ownBalance covers the accounts directly in the group, subtreeBalance the group and all its descendants.
     * Balances of different currencies are never added.
     */
    public record GroupBalance(long groupId, int depth, String path, String currency, BigDecimal ownBalance,
                               BigDecimal subtreeBalance) {
    }

    // The common table expressions of the roll-up and their columns.
    private static final Table<?> TREE = table(name("tree"));
    private static final Field<Long> T_ID = field(name("tree", "id"), Long.class);
    private static final Field<Integer> T_DEPTH = field(name("tree", "depth"), Integer.class);
    private static final Field<String> T_PATH = field(name("tree", "path"), String.class);

    private static final Table<?> CLOSURE = table(name("closure"));
    private static final Field<Long> C_ANCESTOR = field(name("closure", "ancestor"), Long.class);
    private static final Field<Long> C_DESCENDANT = field(name("closure", "descendant"), Long.class);

    private static final Table<?> OWN = table(name("own"));
    private static final Field<Long> O_GROUP = field(name("own", "group_id"), Long.class);
    private static final Field<String> O_CURRENCY = field(name("own", "currency"), String.class);
    private static final Field<BigDecimal> O_BALANCE = field(name("own", "balance"), BigDecimal.class);

    public List<GroupBalance> rollup(long rootGroupId) {
        return dsl.fetch(rollupQuery(rootGroupId)).map(Records.mapping(GroupBalance::new));
    }

    /**
     * The roll-up as one recursive query. Three common table expressions: the subtree of the root with depth and
     * path, the (ancestor, descendant) pairs inside that subtree, and the balance of the own accounts of every
     * group in the subtree per currency. The final select adds the own balances up per ancestor.
     */
    ResultQuery<Record6<Long, Integer, String, String, BigDecimal, BigDecimal>> rollupQuery(long rootGroupId) {
        CommonTableExpression<?> tree = name("tree").fields("id", "depth", "path").as(
                select(ACCOUNT_GROUP.ID, inline(0), ACCOUNT_GROUP.NAME)
                        .from(ACCOUNT_GROUP)
                        .where(ACCOUNT_GROUP.ID.eq(rootGroupId))
                        .unionAll(
                                select(ACCOUNT_GROUP.ID, T_DEPTH.plus(1), T_PATH.concat(" / ").concat(ACCOUNT_GROUP.NAME))
                                        .from(ACCOUNT_GROUP)
                                        .join(TREE).on(ACCOUNT_GROUP.PARENT_ID.eq(T_ID))));

        CommonTableExpression<?> closure = name("closure").fields("ancestor", "descendant").as(
                select(T_ID, T_ID).from(TREE)
                        .unionAll(
                                select(C_ANCESTOR, ACCOUNT_GROUP.ID)
                                        .from(CLOSURE)
                                        .join(ACCOUNT_GROUP).on(ACCOUNT_GROUP.PARENT_ID.eq(C_DESCENDANT))));

        CommonTableExpression<?> own = name("own").fields("group_id", "currency", "balance").as(
                select(ACCOUNT.GROUP_ID, ACCOUNT.CURRENCY, coalesce(sum(POSTING.AMOUNT), BigDecimal.ZERO))
                        .from(ACCOUNT)
                        .leftJoin(POSTING).on(POSTING.ACCOUNT_ID.eq(ACCOUNT.ID))
                        .where(ACCOUNT.GROUP_ID.in(select(T_ID).from(TREE)))
                        .groupBy(ACCOUNT.GROUP_ID, ACCOUNT.CURRENCY));

        Field<BigDecimal> ownSum = coalesce(
                sum(when(C_DESCENDANT.eq(T_ID), O_BALANCE).otherwise(BigDecimal.ZERO)), BigDecimal.ZERO);
        Field<BigDecimal> subtreeSum = coalesce(sum(O_BALANCE), BigDecimal.ZERO);

        return DSL.withRecursive(tree, closure, own)
                .select(T_ID, T_DEPTH, T_PATH, O_CURRENCY, ownSum, subtreeSum)
                .from(TREE)
                .join(CLOSURE).on(C_ANCESTOR.eq(T_ID))
                .leftJoin(OWN).on(O_GROUP.eq(C_DESCENDANT))
                .groupBy(T_ID, T_DEPTH, T_PATH, O_CURRENCY)
                .orderBy(T_PATH, O_CURRENCY);
    }

    /** The roll-up SQL as rendered for the given dialect (bind variables stay placeholders). */
    public String rollupSql(SQLDialect dialect, long rootGroupId) {
        return DSL.using(dialect).render(rollupQuery(rootGroupId));
    }

    /**
     * Moves a group under another parent (null makes it a root). Rejects a move that would make the group its own
     * ancestor. All moves take one advisory lock first: without it two concurrent moves (A under B, B under A) would
     * each see an acyclic tree in their own snapshot and both commit.
     */
    @Transactional
    public void moveGroup(long groupId, Long newParentId) {
        dsl.execute("select pg_advisory_xact_lock(?)", GROUP_MOVE_LOCK);
        if (!dsl.fetchExists(ACCOUNT_GROUP, ACCOUNT_GROUP.ID.eq(groupId))) {
            throw new LedgerException("Unknown group " + groupId);
        }
        if (newParentId != null) {
            if (!dsl.fetchExists(ACCOUNT_GROUP, ACCOUNT_GROUP.ID.eq(newParentId))) {
                throw new LedgerException("Unknown group " + newParentId);
            }
            // The new parent must not be the group itself or anything below it. The lock above guarantees that
            // this read sees every earlier committed move.
            CommonTableExpression<?> below = name("below").fields("id").as(
                    select(ACCOUNT_GROUP.ID).from(ACCOUNT_GROUP).where(ACCOUNT_GROUP.ID.eq(groupId))
                            .unionAll(select(ACCOUNT_GROUP.ID).from(ACCOUNT_GROUP)
                                    .join(table(name("below")))
                                    .on(ACCOUNT_GROUP.PARENT_ID.eq(field(name("below", "id"), Long.class)))));
            boolean cycle = dsl.fetchExists(
                    DSL.withRecursive(below)
                            .selectOne()
                            .from(table(name("below")))
                            .where(field(name("below", "id"), Long.class).eq(newParentId)));
            if (cycle) {
                throw new LedgerException("Moving group " + groupId + " under " + newParentId + " would create a cycle");
            }
        }
        dsl.update(ACCOUNT_GROUP).set(ACCOUNT_GROUP.PARENT_ID, newParentId)
                .where(ACCOUNT_GROUP.ID.eq(groupId)).execute();
    }
}
