package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.CURRENCY;
import static org.assertj.core.api.Assertions.assertThat;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class LedgerApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    DSLContext dsl;

    @Test
    void migrationsRunAndGeneratedClassesMatchTheSchema() {
        assertThat(dsl.fetchCount(CURRENCY)).isEqualTo(4);
    }
}
