package dev.rabauer.ledger.service;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static dev.rabauer.ledger.jooq.Tables.CUSTOMER;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One shared Postgres container and one Spring context for all service tests. */
@SpringBootTest
abstract class AbstractLedgerTest {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static {
        postgres.start();
    }

    @Autowired
    DSLContext dsl;
    @Autowired
    LedgerService ledger;

    long newCustomer(String name) {
        return dsl.insertInto(CUSTOMER).set(CUSTOMER.NAME, name)
                .returningResult(CUSTOMER.ID).fetchSingle().value1();
    }

    long newAccount(long customerId, String currency, boolean allowOverdraft) {
        return dsl.insertInto(ACCOUNT)
                .set(ACCOUNT.CUSTOMER_ID, customerId)
                .set(ACCOUNT.IBAN, "TEST-" + UUID.randomUUID())
                .set(ACCOUNT.CURRENCY, currency)
                .set(ACCOUNT.ALLOW_OVERDRAFT, allowOverdraft)
                .returningResult(ACCOUNT.ID).fetchSingle().value1();
    }

    long newAccount(String currency, boolean allowOverdraft) {
        return newAccount(newCustomer("Customer " + UUID.randomUUID()), currency, allowOverdraft);
    }

    /** Funds an account from a fresh overdraft-enabled "source" account of the same currency. */
    void deposit(long accountId, String currency, String amount, OffsetDateTime at, String description) {
        long source = newAccount(currency, true);
        ledger.book(description, at, null, List.of(
                new PostingSpec(source, new BigDecimal(amount).negate()),
                new PostingSpec(accountId, new BigDecimal(amount))));
    }

    void deposit(long accountId, String currency, String amount) {
        deposit(accountId, currency, amount, OffsetDateTime.now(), "deposit");
    }

    BigDecimal balance(long accountId) {
        return ledger.balance(accountId);
    }

    /** Number of transactions whose postings do not sum to zero per currency. */
    long unbalancedTransactions() {
        return dsl.select(POSTING.TRANSACTION_ID)
                .from(POSTING)
                .join(ACCOUNT).on(ACCOUNT.ID.eq(POSTING.ACCOUNT_ID))
                .groupBy(POSTING.TRANSACTION_ID, ACCOUNT.CURRENCY)
                .having(sum(POSTING.AMOUNT).ne(BigDecimal.ZERO))
                .fetch().size();
    }
}
