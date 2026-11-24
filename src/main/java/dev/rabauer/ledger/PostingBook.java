package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.jooq.DSLContext;
import org.jooq.Record3;
import org.jooq.Result;
import org.springframework.stereotype.Component;

/**
 * The one place that writes ledger transactions and enforces the invariants:
 * postings sum to zero per currency, no forbidden overdraft, append-only.
 * Must be called inside a database transaction.
 */
@Component
public class PostingBook {

    public record Leg(long accountId, BigDecimal amount) {
    }

    /**
     * Books a transaction. Returns empty when {@code idempotencyKey} was already booked (nothing is written).
     */
    public Optional<Long> book(DSLContext tx, OffsetDateTime bookedAt, String description,
            String idempotencyKey, List<Leg> legs) {
        if (legs.size() < 2) {
            throw new IllegalArgumentException("A transaction needs at least two postings");
        }

        Optional<Long> transactionId = tx.insertInto(LEDGER_TRANSACTION)
                .set(LEDGER_TRANSACTION.BOOKED_AT, bookedAt)
                .set(LEDGER_TRANSACTION.DESCRIPTION, description)
                .set(LEDGER_TRANSACTION.IDEMPOTENCY_KEY, idempotencyKey)
                .onConflict(LEDGER_TRANSACTION.IDEMPOTENCY_KEY).doNothing()
                .returningResult(LEDGER_TRANSACTION.ID)
                .fetchOptional(LEDGER_TRANSACTION.ID);
        if (transactionId.isEmpty()) {
            return Optional.empty();
        }

        Result<Record3<Long, String, Boolean>> accounts = lockAccounts(tx, legs);
        checkZeroSumPerCurrency(accounts, legs);
        checkOverdraft(tx, accounts, legs);

        var insert = tx.insertInto(POSTING, POSTING.TRANSACTION_ID, POSTING.ACCOUNT_ID, POSTING.AMOUNT);
        for (Leg leg : legs) {
            insert = insert.values(transactionId.get(), leg.accountId(), leg.amount());
        }
        insert.execute();
        return transactionId;
    }

    /** Locks the account rows in id order (no deadlocks) so concurrent bookings per account are serialized. */
    private Result<Record3<Long, String, Boolean>> lockAccounts(DSLContext tx, List<Leg> legs) {
        TreeSet<Long> ids = new TreeSet<>();
        legs.forEach(l -> ids.add(l.accountId()));
        Result<Record3<Long, String, Boolean>> accounts = tx
                .select(ACCOUNT.ID, ACCOUNT.CURRENCY, ACCOUNT.ALLOW_OVERDRAFT)
                .from(ACCOUNT)
                .where(ACCOUNT.ID.in(ids))
                .orderBy(ACCOUNT.ID)
                .forUpdate()
                .fetch();
        if (accounts.size() != ids.size()) {
            throw new IllegalArgumentException("Unknown account in " + ids);
        }
        return accounts;
    }

    private void checkZeroSumPerCurrency(Result<Record3<Long, String, Boolean>> accounts, List<Leg> legs) {
        Map<Long, String> currencyOf = new HashMap<>();
        accounts.forEach(a -> currencyOf.put(a.value1(), a.value2()));
        Map<String, BigDecimal> sums = new HashMap<>();
        for (Leg leg : legs) {
            sums.merge(currencyOf.get(leg.accountId()), leg.amount(), BigDecimal::add);
        }
        sums.forEach((currency, sum) -> {
            if (sum.signum() != 0) {
                throw new IllegalArgumentException("Postings do not sum to zero in " + currency + ": " + sum);
            }
        });
    }

    private void checkOverdraft(DSLContext tx, Result<Record3<Long, String, Boolean>> accounts, List<Leg> legs) {
        Map<Long, BigDecimal> delta = new HashMap<>();
        legs.forEach(l -> delta.merge(l.accountId(), l.amount(), BigDecimal::add));

        List<Long> restricted = accounts.stream()
                .filter(a -> !a.value3() && delta.get(a.value1()).signum() < 0)
                .map(Record3::value1)
                .toList();
        if (restricted.isEmpty()) {
            return;
        }
        Map<Long, BigDecimal> balances = tx
                .select(POSTING.ACCOUNT_ID, coalesce(sum(POSTING.AMOUNT), BigDecimal.ZERO))
                .from(POSTING)
                .where(POSTING.ACCOUNT_ID.in(restricted))
                .groupBy(POSTING.ACCOUNT_ID)
                .fetchMap(POSTING.ACCOUNT_ID, coalesce(sum(POSTING.AMOUNT), BigDecimal.ZERO));
        for (Long id : restricted) {
            BigDecimal after = balances.getOrDefault(id, BigDecimal.ZERO).add(delta.get(id));
            if (after.signum() < 0) {
                throw new InsufficientFundsException(id);
            }
        }
    }
}
