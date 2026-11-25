package dev.rabauer.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Rendering checks only: no other database is started.
 *
 * <p>The open source jOOQ jar has no SQLDialect.ORACLE and no SQLDialect.SQLSERVER (they exist only in the
 * commercial editions), so these two cannot be named in code that has to compile here. The tests look them up by name
 * and include them when the jar knows them; otherwise they check the open source dialects that are closest to
 * "something else than Postgres": MYSQL, MARIADB, H2, HSQLDB, DERBY, SQLITE, FIREBIRD and TRINO. Dialects that are
 * Postgres compatible (DUCKDB, YUGABYTEDB) are left out on purpose because they may accept Postgres syntax.
 */
class DialectRenderingTest extends AbstractLedgerTest {

    /** Syntax that only PostgreSQL understands (lower case, jOOQ renders keywords in lower case by default). */
    private static final List<String> POSTGRES_ONLY = List.of("::", "jsonb", "ilike", "generate_series",
            "returning", "on conflict", "for update of", "interval '", "pg_");

    private static final OffsetDateTime AS_OF = OffsetDateTime.of(2030, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    GroupService groups;
    @Autowired
    ValuationService valuation;

    /** The dialects to check against Postgres: ORACLE and SQLSERVER where the jar has them. */
    private static List<SQLDialect> otherDialects() {
        List<SQLDialect> result = new ArrayList<>(List.of(SQLDialect.MYSQL, SQLDialect.MARIADB, SQLDialect.H2,
                SQLDialect.HSQLDB, SQLDialect.DERBY, SQLDialect.SQLITE, SQLDialect.FIREBIRD, SQLDialect.TRINO));
        for (String name : List.of("ORACLE", "SQLSERVER")) {
            try {
                result.add(SQLDialect.valueOf(name));
            } catch (IllegalArgumentException e) {
                // open source edition: not available
            }
        }
        return result;
    }

    /** Dialects whose rendering needs a commercial jOOQ feature (SQL transformations), with the reason. */
    private static final Map<SQLDialect, String> CANNOT_RENDER = new LinkedHashMap<>();

    private static Map<SQLDialect, String> renderAll(Function<SQLDialect, String> render) {
        Map<SQLDialect, String> sql = new LinkedHashMap<>();
        sql.put(SQLDialect.POSTGRES, render.apply(SQLDialect.POSTGRES));
        for (SQLDialect d : otherDialects()) {
            try {
                sql.put(d, render.apply(d));
            } catch (DataAccessException e) {
                CANNOT_RENDER.put(d, e.getMessage());
            }
        }
        return sql;
    }

    private Map<SQLDialect, String> rollupSql() {
        return renderAll(d -> groups.rollupSql(d, 1L));
    }

    private Map<SQLDialect, String> valuationSql() {
        return renderAll(d -> valuation.valuationSql(d, 1L, AS_OF));
    }

    @Test
    void rollupRendersForPostgresAndTheOtherDialects() {
        Map<SQLDialect, String> sql = rollupSql();
        sql.forEach((d, s) -> assertThat(s).as(d.name()).isNotBlank().containsIgnoringCase("account_group"));
        assertThat(sql.get(SQLDialect.POSTGRES)).contains("with recursive");
        // really rendered per dialect: MySQL quotes with backticks, Postgres with double quotes
        assertThat(sql.get(SQLDialect.MYSQL)).contains("`").isNotEqualTo(sql.get(SQLDialect.POSTGRES));
        assertThat(sql.get(SQLDialect.POSTGRES)).doesNotContain("`");
    }

    @Test
    void valuationRendersForPostgresAndTheOtherDialects() {
        Map<SQLDialect, String> sql = valuationSql();
        sql.forEach((d, s) -> assertThat(s).as(d.name()).isNotBlank().containsIgnoringCase("exchange_rate"));
        assertThat(sql.get(SQLDialect.MYSQL)).contains("`").isNotEqualTo(sql.get(SQLDialect.POSTGRES));
        // "latest rate" subquery: the row limit is written differently outside Postgres
        assertThat(sql.get(SQLDialect.FIREBIRD)).doesNotContain(" limit ");
    }

    @Test
    void noPostgresOnlySyntaxLeaksIntoTheOtherDialects() {
        for (var rendered : List.of(rollupSql(), valuationSql())) {
            rendered.forEach((d, s) -> {
                if (d == SQLDialect.POSTGRES) {
                    return;
                }
                String lower = s.toLowerCase();
                for (String pg : POSTGRES_ONLY) {
                    assertThat(lower).as("%s must not contain '%s'", d, pg).doesNotContain(pg);
                }
            });
        }
    }

    @Test
    void mostOtherDialectsRenderBothQueries() {
        // a dialect that needs a commercial transformation is reported here with its reason, not silently skipped
        Map<SQLDialect, String> roll = rollupSql();
        Map<SQLDialect, String> val = valuationSql();
        assertThat(roll.keySet()).as("rollup dialects, cannot render: %s", CANNOT_RENDER)
                .contains(SQLDialect.POSTGRES, SQLDialect.MYSQL).hasSizeGreaterThanOrEqualTo(4);
        assertThat(val.keySet()).as("valuation dialects, cannot render: %s", CANNOT_RENDER)
                .contains(SQLDialect.POSTGRES, SQLDialect.MYSQL).hasSizeGreaterThanOrEqualTo(4);
        System.out.println("DIALECTS rollup=" + roll.keySet() + " valuation=" + val.keySet()
                + " cannotRender=" + CANNOT_RENDER.keySet());
    }

    @Test
    void theLeakListCatchesPostgresSyntax() {
        // The two queries happen to use no Postgres-only syntax in the first place (jOOQ renders casts as
        // cast(.. as ..)), so prove the checker itself with hand-written Postgres SQL.
        for (String pgSql : List.of("select x::int from t", "select payload::jsonb from t",
                "select 1 from t where a ilike 'x'", "select * from generate_series(1, 3)",
                "insert into t values (1) on conflict do nothing returning id")) {
            assertThat(POSTGRES_ONLY.stream().filter(pgSql.toLowerCase()::contains)).as(pgSql).isNotEmpty();
        }
    }
}
