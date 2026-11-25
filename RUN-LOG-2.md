# RUN-LOG-2 (task 2, jOOQ docs MCP available)

MCP tools were available (6 tools loaded via ToolSearch). Read README, TASK, TASK-2, V1-V3, PostingBook, TransferService, CustomerReportService, AbstractLedgerTest.

## MCP calls
1. getCodeGenerationGuide () - returned only whitespace/Generator config fragments, nothing on forced types. Not helpful; no change.
2. searchDocumentation "forcedTypes converter code generation custom data type" - helpful: confirmed `<forcedType><userType>..</userType><converter>..</converter><includeExpression>` shape ("Qualified converters"). Snippets truncated, so the exact element names for includeExpression/includeTypes come from my memory (guess, will see at codegen).
3. searchDocumentation "recursive CTE withRecursive" - confirmed `DSLContext.withRecursive(...)` / DSL.name(..).fields(..).as(...) pattern, with a path example (`t.path || '\' || label`). Confirmatory.
4. searchDocumentation "generateSeries generate_series table function" - only showed emulations for Sybase/DB2/SQLDW (recursive CTE), confirms `generateSeries` exists and is emulated. Confirmatory; I will use it for the day axis.

## Decisions / guesses
- Item 5 (Iban): record `Iban` + `IbanConverter` (AbstractConverter) in main, forced type in pom (userType/converter/includeExpression `.*\.account\.iban`). Worried that the converter class (compiled later than generate-sources) would be needed on the codegen classpath: it was NOT; generator only emits the class name. Generated `Account.IBAN` is `TableField<AccountRecord, Iban>`. No compile errors, all tests green on first try (IbanTest 5 + old 17 = 22).
- FX clearing accounts now use `Iban.generate("CH", "FXCLEARING"+currency)` (valid check digits). Test helper `account()` generates valid IBANs from a counter. Existing databases that already hold e.g. `FX-CLEARING-CHF` would fail on read (converter throws); there is no seed data with IBANs in V1-V3, so no migration written.
- Test output of mvn is very noisy (DEBUG SQL); I read surefire XML for totals.

## Item 1 (group roll-up + move)
MCP calls (continued, numbering: I forgot to log one earlier):
5. searchDocumentation "forcedType includeExpression includeTypes userType converter maven" - confirmed includeExpression is a regex over fully qualified names. Helped (kept my config as is).
6. getSqlExamples "WITH RECURSIVE path hierarchy parent_id unionAll" - truncated, but showed `name("t").fields(..).as(select..unionAll(..))` pattern; matches what I wrote. Confirmatory.
7. searchDocumentation "LOCK TABLE or SELECT FOR UPDATE lock rows in order" - only generic FOR UPDATE text (and again the dubious "NOWAIT is the default" claim). Not helpful; no change. jOOQ has no LOCK TABLE I know of, so I lock rows with FOR UPDATE.

- Design: recursive CTE `tree` (depth, path), recursive CTE `closure` (ancestor, descendant), CTE `acct` (own balance per group+currency, left join so empty accounts count as 0), CTE `subtree`; final select left-joins, grouped into nested records in Java (flat rows, not multiset: avoids JSON emulation in the other dialects). One statement. Ordered by path (string), tests look up by path.
- moveGroup: `SELECT id FROM account_group ORDER BY id FOR UPDATE` (all group rows, id order) then re-check cycle with a recursive "ancestors of new parent" CTE in a new statement (READ COMMITTED sees the other commit). Trade-off: serializes all moves; fine for an org tree. Not sure Postgres locks in ORDER BY order, but I believe LockRows sits above Sort; the 25-round concurrency test passes with exactly one winner each round.
- Compile error: `ResultQuery<Record>` return type vs `SelectSeekStep2<Record7..>` -> changed to `ResultQuery<? extends Record>`.
- Test failure: concurrentOppositeMovesNeverCreateACycle NPE at test line 157: `List.of(null, ..)` in my test. Fixed test (Arrays.asList).

## Item 2 (average daily balance)
MCP calls:
8. searchDocumentation "date time arithmetic add days interval to TIMESTAMP WITH TIME ZONE field" - pointed at TIMESTAMPADD, but only the name/Timestamp flavour; no OffsetDateTime. Partly helpful (found the name), did not solve it.
9. getSqlExamples "GENERATE_SERIES" - only dialect emulations (Sybase/SQLServer/DB2); confirmed `generateSeries(0, n)` exists. Kept.
10. searchDocumentation "dateAdd OffsetDateTime Field DatePart HOUR add to temporal field" - useless (OffsetDateTime == TIMESTAMP WITH TIME ZONE, hour() docs). No change.
- Compile error: `timestampAdd(Param<OffsetDateTime>, Field<Integer>, DatePart)` - "not applicable", jOOQ only has timestampAdd for java.sql.Timestamp. Changed approach: `val(start).add(inline(new DayToSecond(0, 1)).mul(n.plus(1).times(24)))` (interval hours * n). GUESSED that Field.add/mul accept intervals on OffsetDateTime; it compiled and ran on Postgres. Reason for 24-hour steps instead of days: `timestamptz + 1 day` depends on session time zone (pgjdbc sets the JVM zone) and would break across DST; the test uses March 2026 (DST change on the 29th) to cover this.
- Query: generate_series day axis cross join account, correlated scalar subquery sum(postings booked_at < day end) -> includes opening balance and a posting exactly at midnight lands on the new day; derived table + window avg/min per account. Day date is computed in Java from the index of the row (not a loop that builds days).
- Rendering for Postgres shows `cast(interval '0 years 0 mons 0 days 1 hours ...' as interval day to second)` - ugly but works; not relevant for item 6 (only item 1 and 3 are rendered per dialect).
- Tests passed first run (4 tests).

## Item 3 (CHF valuation)
No MCP call for this item (relied on own knowledge; scalar subqueries, max(valid_from) pattern).
- Decision: inverse rates NOT used (documented in the class comment); differs from TransferService which falls back to the inverse. Booking value is null if any posting has no rate or the currency has no rate as of the valuation time; empty account is worth 0.
- Query: derived table pv (one row per posting, rate lookups as scalar subqueries via max(valid_from)), outer group by with count(amount) = count(amount*rate) check. Compiled first try, 4 tests passed first run.

## Item 4 (bank reconciliation)
MCP calls:
11. searchDocumentation "AT TIME ZONE convert TIMESTAMP WITH TIME ZONE to UTC date" - useless (data type pages). Used plain SQL template `{0} at time zone 'UTC'` for the UTC booking day (Postgres-only, acceptable: item 6 only renders roll-up and valuation). A plain cast to DATE would depend on the session time zone (pgjdbc uses the JVM zone), so I avoided it.
12. searchDocumentation "UPDATE with subquery or UPDATE FROM join, row value IN" - confirmed UPDATE .. FROM exists in jOOQ (Postgres/SQL Server). Helped: used `with(..).update(..).set(..).from(..).where(..)`; WITH + UPDATE chaining itself was my guess, it compiled.
- Algorithm: greedy closest-first cannot be one set statement, so rounds of one UPDATE each: candidates CTE (free line x free posting, same account+amount, |day diff| <= 2), ranked CTE with row_number per line and per posting (ties: lower line id, lower posting id), update where both ranks = 1 (mutually best pairs = what a sequential closest-first pass picks). Repeat until 0 rows updated (a Java loop over statements, but no rows loaded). Compiled first time; 8 tests passed first run.
- Not handled: two reconcile runs at the same time can fail on the UNIQUE(matched_posting_id) (documented in the code), no locking added.

## Item 6 (other dialects)
MCP calls:
13. getDatabaseSupport "SQL Server" - talked about SQLSERVER / SQLSERVER2012 dialects as if they exist. MISLEADING for this build: see below. No help.
14. getDatabaseSupport "Oracle" - only generic dialect-support snippets (DUAL etc.). No help.
- Methods: `AccountGroupService.rollupSql(SQLDialect, rootId)` and `ValuationService.valuationSql(SQLDialect, customerId, asOf)` build the same jOOQ query with `DSL.using(dialect)` and return `getSQL()` (placeholders).
- Compile error: `SQLDialect.ORACLE` / `SQLDialect.SQLSERVER` "Symbol nicht gefunden". The jOOQ on the classpath (open source, Spring Boot managed) has only: DEFAULT, CLICKHOUSE, CUBRID, DERBY, DUCKDB, FIREBIRD, H2, HSQLDB, IGNITE, MARIADB, MYSQL, POSTGRES, SQLITE, TRINO, YUGABYTEDB (I printed SQLDialect.values() in a throwaway test, deleted). ORACLE and SQLSERVER are commercial-only. The docs MCP does not say that. => TASK-2 item 6 is NOT fully doable as written.
- What I did instead: tests render both queries for POSTGRES and assert for MYSQL, MARIADB, H2, HSQLDB, SQLITE, FIREBIRD that rendering works and no `::`, jsonb, ilike, returning, generate_series, on conflict, distinct on, `interval '` appears. A third test checks ORACLE and SQLSERVER via `assumeTrue(dialect exists)`, so it is reported as SKIPPED here (Oracle/SQL Server were never rendered). I did not weaken any assertion to get green; the skip is because the dialects do not exist in this build.
- Runtime error: DERBY: "SQL transformation Settings.transformInlineCTE required. SQL transformations are a commercial only feature" when rendering the roll-up (Derby has no CTEs). Removed DERBY from the list with a comment.
- Note: only the roll-up and valuation are checked for leaks; the daily-balance and reconciliation SQL contain Postgres-specific bits (interval arithmetic, AT TIME ZONE) and were not required to be portable.

## Final
- `mvn -q -B verify` green: 46 tests, 0 failures, 0 errors, 1 skipped (DialectRenderingTest.oracleAndSqlServerWhenTheBuildHasThem: ORACLE/SQLSERVER do not exist in the open-source jOOQ build).
- MCP total: 14 calls. Confirmatory or unhelpful for most; useful: forcedType shape/includeExpression regex (#2, #5), UPDATE .. FROM exists (#12). Misleading: #7 (NOWAIT default), #13 (SQL Server dialect as available). No migration (V4) was needed.
- Not done: Oracle and SQL Server rendering (impossible with this jOOQ edition); concurrent reconciliation runs are not serialized; existing databases holding non-IBAN values in account.iban would fail on read.
