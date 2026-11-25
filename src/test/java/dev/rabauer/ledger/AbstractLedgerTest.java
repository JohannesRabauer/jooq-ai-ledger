package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static dev.rabauer.ledger.jooq.Tables.CUSTOMER;
import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Shared Postgres container for all service tests; every test creates its own customers and accounts. */
@SpringBootTest
abstract class AbstractLedgerTest {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final AtomicLong IBAN_SEQUENCE = new AtomicLong();

    static {
        postgres.start();
    }

    @Autowired
    protected DSLContext dsl;

    protected long customer(String name) {
        return dsl.insertInto(CUSTOMER).set(CUSTOMER.NAME, name)
                .returningResult(CUSTOMER.ID).fetchSingle(CUSTOMER.ID);
    }

    protected long account(long customerId, String currency, boolean allowOverdraft) {
        return dsl.insertInto(ACCOUNT)
                .set(ACCOUNT.CUSTOMER_ID, customerId)
                .set(ACCOUNT.IBAN, Iban.generate("CH", String.format("%017d", IBAN_SEQUENCE.incrementAndGet())))
                .set(ACCOUNT.CURRENCY, currency)
                .set(ACCOUNT.ALLOW_OVERDRAFT, allowOverdraft)
                .returningResult(ACCOUNT.ID).fetchSingle(ACCOUNT.ID);
    }

    /** Books a deposit from a fresh overdraft-enabled counter account. */
    protected void deposit(TransferService transfers, long accountId, String currency, String amount) {
        long source = account(customer("bank"), currency, true);
        transfers.transfer(source, accountId, new BigDecimal(amount), "deposit");
    }

    protected BigDecimal balance(long accountId) {
        return dsl.select(coalesce(sum(POSTING.AMOUNT), BigDecimal.ZERO)).from(POSTING)
                .where(POSTING.ACCOUNT_ID.eq(accountId)).fetchSingle().value1();
    }

    protected long postingCount(long accountId) {
        return dsl.fetchCount(POSTING, POSTING.ACCOUNT_ID.eq(accountId));
    }

    /** Global invariant: every transaction sums to zero per currency. */
    protected void assertAllTransactionsBalanced() {
        var unbalanced = dsl.select(POSTING.TRANSACTION_ID, ACCOUNT.CURRENCY)
                .from(POSTING).join(ACCOUNT).on(ACCOUNT.ID.eq(POSTING.ACCOUNT_ID))
                .groupBy(POSTING.TRANSACTION_ID, ACCOUNT.CURRENCY)
                .having(sum(POSTING.AMOUNT).ne(BigDecimal.ZERO))
                .fetch();
        assertThat(unbalanced).isEmpty();
        assertThat(dsl.fetchCount(LEDGER_TRANSACTION)).isPositive();
    }
}
