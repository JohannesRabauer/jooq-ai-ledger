package dev.rabauer.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

/**
 * Rendering checks only: no database of those dialects is needed, nor is any database at all.
 * <p>
 * ORACLE and SQLSERVER are commercial-edition dialects: the open-source jOOQ on the classpath has no such enum
 * constants, so those two cannot be rendered here (see {@link #oracleAndSqlServerWhenTheBuildHasThem()}, which runs
 * only when they exist). MYSQL and the other open-source non-Postgres dialects are checked instead.
 */
class DialectRenderingTest {

    private static final List<SQLDialect> OTHERS = List.of(SQLDialect.MYSQL, SQLDialect.MARIADB, SQLDialect.H2,
            SQLDialect.HSQLDB, SQLDialect.SQLITE, SQLDialect.FIREBIRD);
    // DERBY is left out on purpose: it has no CTEs, and the open-source jOOQ cannot emulate them (needs the commercial
    // Settings.transformInlineCTE).
    private static final List<String> POSTGRES_ONLY = List.of("::", "jsonb", "ilike", "returning", "generate_series",
            "on conflict", "distinct on", "interval '");

    private final AccountGroupService groups = new AccountGroupService(DSL.using(SQLDialect.DEFAULT));
    private final ValuationService valuation = new ValuationService(DSL.using(SQLDialect.DEFAULT));
    private final OffsetDateTime asOf = OffsetDateTime.parse("2026-03-01T00:00:00Z");

    private static SQLDialect commercial(String name) {
        return Arrays.stream(SQLDialect.values()).filter(d -> d.name().equals(name)).findFirst().orElse(null);
    }

    private void check(List<SQLDialect> dialects, Function<SQLDialect, String> render, String mustContain) {
        String postgres = render.apply(SQLDialect.POSTGRES);
        assertThat(postgres).isNotBlank().containsIgnoringCase(mustContain);

        for (SQLDialect dialect : dialects) {
            String sql;
            try {
                sql = render.apply(dialect);
            } catch (RuntimeException e) {
                throw new AssertionError("Rendering failed for " + dialect, e);
            }
            assertThat(sql).as(dialect.name()).isNotBlank().containsIgnoringCase(mustContain);
            String lower = sql.toLowerCase(Locale.ROOT);
            for (String postgresOnly : POSTGRES_ONLY) {
                assertThat(lower).as("%s leaks '%s':%n%s", dialect, postgresOnly, sql).doesNotContain(postgresOnly);
            }
        }
    }

    @Test
    void rollupRendersWithoutPostgresSyntax() {
        check(OTHERS, d -> groups.rollupSql(d, 1L), "account_group");
        assertThat(groups.rollupSql(SQLDialect.POSTGRES, 1L).toLowerCase(Locale.ROOT)).contains("with recursive");
    }

    @Test
    void valuationRendersWithoutPostgresSyntax() {
        check(OTHERS, d -> valuation.valuationSql(d, 1L, asOf), "exchange_rate");
    }

    @Test
    void oracleAndSqlServerWhenTheBuildHasThem() {
        SQLDialect oracle = commercial("ORACLE");
        SQLDialect sqlServer = commercial("SQLSERVER");
        assumeTrue(oracle != null && sqlServer != null,
                "ORACLE/SQLSERVER are not in the open-source jOOQ build: " + Arrays.toString(SQLDialect.values()));
        List<SQLDialect> commercial = List.of(oracle, sqlServer);
        check(commercial, d -> groups.rollupSql(d, 1L), "account_group");
        check(commercial, d -> valuation.valuationSql(d, 1L, asOf), "exchange_rate");
    }
}
