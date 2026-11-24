package dev.rabauer.ledger.service;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static dev.rabauer.ledger.jooq.Tables.CURRENCY;
import static dev.rabauer.ledger.jooq.Tables.CUSTOMER;
import static dev.rabauer.ledger.jooq.Tables.EXCHANGE_RATE;
import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Booking: transfers, cross-currency transfers and idempotent imports. */
@Service
public class LedgerService {

    private static final long FX_CLEARING_LOCK = 7_001L;

    private final DSLContext dsl;

    public LedgerService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** One entry of a bank file. */
    public record ImportEntry(String idempotencyKey, String description, OffsetDateTime bookedAt,
                              List<PostingSpec> postings) {
    }

    public record ImportResult(int booked, int skipped) {
    }

    /** Moves amount from one account to another of the same currency. Returns the transaction id. */
    @Transactional
    public long transfer(long fromAccountId, long toAccountId, BigDecimal amount, String description) {
        if (fromAccountId == toAccountId) {
            throw new LedgerException("Cannot transfer to the same account");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new LedgerException("Transfer amount must be positive");
        }
        return book(description, OffsetDateTime.now(), null, List.of(
                new PostingSpec(fromAccountId, amount.negate()),
                new PostingSpec(toAccountId, amount))).orElseThrow();
    }

    /**
     * Debits fromAccount (amount in its currency) and credits toAccount with the converted amount, using the
     * latest exchange rate valid at booking time. Booked through the FX clearing accounts so that postings sum
     * to zero per currency.
     */
    @Transactional
    public long transferCrossCurrency(long fromAccountId, long toAccountId, BigDecimal amount, String description) {
        if (fromAccountId == toAccountId) {
            throw new LedgerException("Cannot transfer to the same account");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new LedgerException("Transfer amount must be positive");
        }
        OffsetDateTime bookedAt = OffsetDateTime.now();
        Map<Long, String> currencies = dsl.select(ACCOUNT.ID, ACCOUNT.CURRENCY)
                .from(ACCOUNT)
                .where(ACCOUNT.ID.in(fromAccountId, toAccountId))
                .fetchMap(ACCOUNT.ID, ACCOUNT.CURRENCY);
        String fromCcy = currencies.get(fromAccountId);
        String toCcy = currencies.get(toAccountId);
        if (fromCcy == null || toCcy == null) {
            throw new LedgerException("Unknown account");
        }
        if (fromCcy.equals(toCcy)) {
            throw new LedgerException("Same currency, use transfer()");
        }
        BigDecimal rate = rateAt(fromCcy, toCcy, bookedAt);
        int minorUnits = dsl.select(CURRENCY.MINOR_UNITS).from(CURRENCY).where(CURRENCY.CODE.eq(toCcy))
                .fetchOptional(CURRENCY.MINOR_UNITS)
                .orElseThrow(() -> new LedgerException("Unknown currency " + toCcy));
        BigDecimal converted = amount.multiply(rate).setScale(minorUnits, RoundingMode.HALF_EVEN);
        if (converted.signum() == 0) {
            throw new LedgerException("Converted amount rounds to zero");
        }
        long fromClearing = fxClearingAccount(fromCcy);
        long toClearing = fxClearingAccount(toCcy);
        return book(description, bookedAt, null, List.of(
                new PostingSpec(fromAccountId, amount.negate()),
                new PostingSpec(fromClearing, amount),
                new PostingSpec(toClearing, converted.negate()),
                new PostingSpec(toAccountId, converted))).orElseThrow();
    }

    /** Imports a batch atomically. Entries whose idempotency key already exists are skipped. */
    @Transactional
    public ImportResult importBatch(List<ImportEntry> entries) {
        int booked = 0;
        int skipped = 0;
        for (ImportEntry e : entries) {
            if (e.idempotencyKey() == null || e.idempotencyKey().isBlank()) {
                throw new LedgerException("Import entries need an idempotency key");
            }
            OffsetDateTime at = e.bookedAt() != null ? e.bookedAt() : OffsetDateTime.now();
            if (book(e.description(), at, e.idempotencyKey(), e.postings()).isPresent()) {
                booked++;
            } else {
                skipped++;
            }
        }
        return new ImportResult(booked, skipped);
    }

    /** Current balance of an account. */
    public BigDecimal balance(long accountId) {
        return dsl.select(coalesce(sum(POSTING.AMOUNT), BigDecimal.ZERO))
                .from(POSTING)
                .where(POSTING.ACCOUNT_ID.eq(accountId))
                .fetchSingle(0, BigDecimal.class);
    }

    /**
     * Books one transaction. Returns the new transaction id, or empty if the idempotency key was already used.
     */
    @Transactional
    public Optional<Long> book(String description, OffsetDateTime bookedAt, String idempotencyKey,
                               List<PostingSpec> postings) {
        if (postings.size() < 2) {
            throw new LedgerException("A transaction needs at least two postings");
        }
        for (PostingSpec p : postings) {
            if (p.amount() == null || p.amount().signum() == 0) {
                throw new LedgerException("Posting amounts must be non-zero");
            }
        }

        // Lock the accounts in id order (no deadlocks between concurrent transfers).
        TreeSet<Long> ids = new TreeSet<>();
        postings.forEach(p -> ids.add(p.accountId()));
        Map<Long, AccountInfo> accounts = new HashMap<>();
        dsl.select(ACCOUNT.ID, ACCOUNT.CURRENCY, ACCOUNT.ALLOW_OVERDRAFT)
                .from(ACCOUNT)
                .where(ACCOUNT.ID.in(ids))
                .orderBy(ACCOUNT.ID)
                .forUpdate()
                .fetch()
                .forEach(r -> accounts.put(r.value1(), new AccountInfo(r.value2(), r.value3())));
        for (Long id : ids) {
            if (!accounts.containsKey(id)) {
                throw new LedgerException("Unknown account " + id);
            }
        }

        // Postings must sum to zero per currency.
        Map<String, BigDecimal> sums = new HashMap<>();
        Map<Long, BigDecimal> perAccount = new HashMap<>();
        for (PostingSpec p : postings) {
            sums.merge(accounts.get(p.accountId()).currency(), p.amount(), BigDecimal::add);
            perAccount.merge(p.accountId(), p.amount(), BigDecimal::add);
        }
        sums.forEach((ccy, s) -> {
            if (s.signum() != 0) {
                throw new LedgerException("Postings do not sum to zero for " + ccy + ": " + s);
            }
        });

        // Idempotency: first writer wins, a repeat inserts nothing.
        Long txId = dsl.insertInto(LEDGER_TRANSACTION)
                .set(LEDGER_TRANSACTION.DESCRIPTION, description)
                .set(LEDGER_TRANSACTION.BOOKED_AT, bookedAt)
                .set(LEDGER_TRANSACTION.IDEMPOTENCY_KEY, idempotencyKey)
                .onConflict(LEDGER_TRANSACTION.IDEMPOTENCY_KEY).doNothing()
                .returningResult(LEDGER_TRANSACTION.ID)
                .fetchOne(LEDGER_TRANSACTION.ID);
        if (txId == null) {
            return Optional.empty();
        }

        // Overdraft check on the locked accounts: current balance plus the net change must not be negative.
        for (var entry : perAccount.entrySet()) {
            AccountInfo info = accounts.get(entry.getKey());
            if (!info.allowOverdraft() && entry.getValue().signum() < 0) {
                BigDecimal balance = balance(entry.getKey());
                if (balance.add(entry.getValue()).signum() < 0) {
                    throw new InsufficientFundsException("Account " + entry.getKey() + " would be overdrawn: balance "
                            + balance + ", change " + entry.getValue());
                }
            }
        }

        var insert = dsl.insertInto(POSTING, POSTING.TRANSACTION_ID, POSTING.ACCOUNT_ID, POSTING.AMOUNT);
        for (PostingSpec p : postings) {
            insert = insert.values(txId, p.accountId(), p.amount());
        }
        insert.execute();
        return Optional.of(txId);
    }

    private BigDecimal rateAt(String from, String to, OffsetDateTime at) {
        return dsl.select(EXCHANGE_RATE.RATE)
                .from(EXCHANGE_RATE)
                .where(EXCHANGE_RATE.BASE_CURRENCY.eq(from)
                        .and(EXCHANGE_RATE.QUOTE_CURRENCY.eq(to))
                        .and(EXCHANGE_RATE.VALID_FROM.le(at)))
                .orderBy(EXCHANGE_RATE.VALID_FROM.desc())
                .limit(1)
                .fetchOptional(EXCHANGE_RATE.RATE)
                .orElseThrow(() -> new LedgerException("No exchange rate " + from + "->" + to + " at " + at));
    }

    private long fxClearingAccount(String currency) {
        String iban = "FX-CLEARING-" + currency;
        Optional<Long> existing = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.IBAN.eq(iban))
                .fetchOptional(ACCOUNT.ID);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Serialize creation so that concurrent first use does not leave stray customers.
        dsl.select(DSL.field("pg_advisory_xact_lock({0})", Long.class, FX_CLEARING_LOCK)).fetch();
        return dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.IBAN.eq(iban))
                .fetchOptional(ACCOUNT.ID)
                .orElseGet(() -> {
                    long customerId = dsl.insertInto(CUSTOMER).set(CUSTOMER.NAME, "FX clearing")
                            .returningResult(CUSTOMER.ID).fetchSingle().value1();
                    return dsl.insertInto(ACCOUNT)
                            .set(ACCOUNT.CUSTOMER_ID, customerId)
                            .set(ACCOUNT.IBAN, iban)
                            .set(ACCOUNT.CURRENCY, currency)
                            .set(ACCOUNT.ALLOW_OVERDRAFT, true)
                            .returningResult(ACCOUNT.ID).fetchSingle().value1();
                });
    }

    private record AccountInfo(String currency, boolean allowOverdraft) {
    }
}
