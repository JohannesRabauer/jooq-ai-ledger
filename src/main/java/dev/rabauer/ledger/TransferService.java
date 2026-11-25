package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static dev.rabauer.ledger.jooq.Tables.CURRENCY;
import static dev.rabauer.ledger.jooq.Tables.CUSTOMER;
import static dev.rabauer.ledger.jooq.Tables.EXCHANGE_RATE;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import dev.rabauer.ledger.PostingBook.Leg;

@Service
public class TransferService {

    static final String FX_CUSTOMER = "FX clearing";

    private final DSLContext dsl;
    private final PostingBook book;

    public TransferService(DSLContext dsl, PostingBook book) {
        this.dsl = dsl;
        this.book = book;
    }

    /** Moves {@code amount} from one account to another of the same currency. Returns the transaction id. */
    public long transfer(long fromAccountId, long toAccountId, BigDecimal amount, String description) {
        return transfer(fromAccountId, toAccountId, amount, description, OffsetDateTime.now());
    }

    public long transfer(long fromAccountId, long toAccountId, BigDecimal amount, String description,
            OffsetDateTime bookedAt) {
        requirePositive(amount);
        if (fromAccountId == toAccountId) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }
        return dsl.transactionResult(cfg -> {
            DSLContext tx = cfg.dsl();
            String from = currencyOf(tx, fromAccountId);
            String to = currencyOf(tx, toAccountId);
            if (!from.equals(to)) {
                throw new IllegalArgumentException("Currency mismatch " + from + "/" + to + ", use a cross-currency transfer");
            }
            return book.book(tx, bookedAt, description, null,
                    List.of(new Leg(fromAccountId, amount.negate()), new Leg(toAccountId, amount))).orElseThrow();
        });
    }

    /**
     * Debits {@code amount} (in the currency of the source account) and credits the converted amount to the
     * target account, using the latest exchange rate valid at {@code bookedAt}. To keep every transaction
     * balanced per currency, the conversion runs through one FX clearing account per currency (4 postings).
     */
    public long transferCrossCurrency(long fromAccountId, long toAccountId, BigDecimal amount, String description,
            OffsetDateTime bookedAt) {
        requirePositive(amount);
        if (fromAccountId == toAccountId) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }
        return dsl.transactionResult(cfg -> {
            DSLContext tx = cfg.dsl();
            String from = currencyOf(tx, fromAccountId);
            String to = currencyOf(tx, toAccountId);
            if (from.equals(to)) {
                throw new IllegalArgumentException("Same currency " + from + ", use a plain transfer");
            }
            BigDecimal rate = rate(tx, from, to, bookedAt);
            int minorUnits = tx.select(CURRENCY.MINOR_UNITS).from(CURRENCY).where(CURRENCY.CODE.eq(to))
                    .fetchSingle(CURRENCY.MINOR_UNITS);
            BigDecimal converted = amount.multiply(rate).setScale(minorUnits, RoundingMode.HALF_EVEN);
            if (converted.signum() == 0) {
                throw new IllegalArgumentException("Converted amount rounds to zero");
            }
            long clearingFrom = fxClearingAccount(tx, from);
            long clearingTo = fxClearingAccount(tx, to);
            return book.book(tx, bookedAt, description, null, List.of(
                    new Leg(fromAccountId, amount.negate()),
                    new Leg(clearingFrom, amount),
                    new Leg(clearingTo, converted.negate()),
                    new Leg(toAccountId, converted))).orElseThrow();
        });
    }

    /** Latest rate with valid_from <= at; falls back to the inverse of the opposite pair. */
    private BigDecimal rate(DSLContext tx, String from, String to, OffsetDateTime at) {
        BigDecimal direct = tx.select(EXCHANGE_RATE.RATE).from(EXCHANGE_RATE)
                .where(EXCHANGE_RATE.BASE_CURRENCY.eq(from))
                .and(EXCHANGE_RATE.QUOTE_CURRENCY.eq(to))
                .and(EXCHANGE_RATE.VALID_FROM.le(at))
                .orderBy(EXCHANGE_RATE.VALID_FROM.desc())
                .limit(1)
                .fetchOne(EXCHANGE_RATE.RATE);
        if (direct != null) {
            return direct;
        }
        BigDecimal inverse = tx.select(EXCHANGE_RATE.RATE).from(EXCHANGE_RATE)
                .where(EXCHANGE_RATE.BASE_CURRENCY.eq(to))
                .and(EXCHANGE_RATE.QUOTE_CURRENCY.eq(from))
                .and(EXCHANGE_RATE.VALID_FROM.le(at))
                .orderBy(EXCHANGE_RATE.VALID_FROM.desc())
                .limit(1)
                .fetchOne(EXCHANGE_RATE.RATE);
        if (inverse == null) {
            throw new IllegalArgumentException("No exchange rate " + from + "->" + to + " valid at " + at);
        }
        return BigDecimal.ONE.divide(inverse, MathContext.DECIMAL64);
    }

    private long fxClearingAccount(DSLContext tx, String currency) {
        Iban iban = Iban.generate("CH", "FXCLEARING" + currency);
        Long id = tx.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.IBAN.eq(iban)).fetchOne(ACCOUNT.ID);
        if (id != null) {
            return id;
        }
        Long customerId = tx.select(CUSTOMER.ID).from(CUSTOMER).where(CUSTOMER.NAME.eq(FX_CUSTOMER))
                .orderBy(CUSTOMER.ID).limit(1).fetchOne(CUSTOMER.ID);
        if (customerId == null) {
            customerId = tx.insertInto(CUSTOMER).set(CUSTOMER.NAME, FX_CUSTOMER)
                    .returningResult(CUSTOMER.ID).fetchSingle(CUSTOMER.ID);
        }
        tx.insertInto(ACCOUNT)
                .set(ACCOUNT.CUSTOMER_ID, customerId)
                .set(ACCOUNT.IBAN, iban)
                .set(ACCOUNT.CURRENCY, currency)
                .set(ACCOUNT.ALLOW_OVERDRAFT, true)
                .onConflict(ACCOUNT.IBAN).doNothing()
                .execute();
        return tx.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.IBAN.eq(iban)).fetchSingle(ACCOUNT.ID);
    }

    static String currencyOf(DSLContext tx, long accountId) {
        String currency = tx.select(ACCOUNT.CURRENCY).from(ACCOUNT).where(ACCOUNT.ID.eq(accountId))
                .fetchOne(ACCOUNT.CURRENCY);
        if (currency == null) {
            throw new IllegalArgumentException("Unknown account " + accountId);
        }
        return currency;
    }

    static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }
}
