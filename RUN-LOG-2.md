# Run log 2 (task 2, bare agent)

Honest log of compile errors, test failures, approach changes and guesses.

## Setup
- Read README, TASK, TASK-2, pom, V1-V3, all services and tests of round 1. Ran `mvn -q -B verify` on the branch as it was: green (this also generated AccountGroup and BankLine, which did not exist in target/ before).
- Order of work: item 5 (IBAN) first, because it touches every existing use of `ACCOUNT.IBAN`; then 1, 2, 3, 4, 6.

## Item 5: Iban type
- `Iban` is a record in `dev.rabauer.ledger.service` (next to the services; there is no other package for domain types). Compact constructor normalizes (strips spaces, upper case), checks `[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}` and mod-97. Country specific lengths are NOT checked (my decision, would need a table).
- Codegen: `<forcedTypes><forcedType>` in the pom with `<userType>`, `<includeExpression>.*\.account\.iban</includeExpression>` and `<lambdaConverter><from>Iban::of</from><to>Iban::value</to></lambdaConverter>`. Guessed the element name `lambdaConverter` from memory (a normal `<converter>` class would not work: the plugin runs in generate-sources, before src/main is compiled, so the converter class is not on the codegen classpath; the lambda form only emits source text). It worked on the first run, generated `TableField<AccountRecord, Iban> IBAN ... Converter.ofNullable(String.class, Iban.class, Iban::of, Iban::value)`. No error.
- Had to adapt: FX clearing accounts used the iban "FX-CLEARING-EUR", which is not a valid IBAN. Now `Iban.fromBban("XF", "FXCLEARING"+ccy)` (XF = user-assigned country code range, valid check digits). Existing databases that already contain old "FX-CLEARING-*" rows would fail to read through the converter (decision: not handled, there is no production data, a migration to rewrite them would be needed; I did not add V4). Test IBANs `TEST-<uuid>` replaced by generated valid CH IBANs.
- `ReportService.AccountSummary.iban` changed from `String` to `Iban` (the multiset + `Records.mapping` picks the converted type up without more code).
- Own mistake: edit tool refused edits for files I had only `cat`-ed through bash ("File has not been read yet"). Re-read with Read, then edited. Also ran a stray `python` command by mistake that hung on a REPL for 2 minutes; harmless.
- `mvn -q -B verify`: green (IbanTest 4, ReportSearchImportTest 7, TransferTest 9, app 1).

## Item 1: group roll-up and move (GroupService)
- Design: one `WITH RECURSIVE` statement with three CTEs: `tree` (subtree of the root with depth and path), `closure` (ancestor/descendant pairs inside that subtree, recursive too) and `own` (per group and currency: sum of its own accounts' postings). Final select joins tree-closure-own and sums `own` per ancestor (own = `sum(case when descendant = tree.id ...)`, subtree = `sum(own.balance)`). Chose the closure CTE over a path/array column because it renders on every dialect (needed for item 6).
- Guessed API without compiler help, all compiled on the first try: `DSL.withRecursive(cte1, cte2, cte3)`, `name("tree").fields("id","depth","path").as(select(...).unionAll(select(...)))`, self-reference through `table(name("tree"))` plus `field(name("tree","id"), Long.class)`, `DSL.when(cond, value).otherwise(value)`, `Field.concat(String)`, `dsl.fetchExists(Select)`, `Records.mapping(GroupBalance::new)` with 6 columns.
- Decisions: path and depth are relative to the root passed in (the root is "Bank", depth 0). One row per (group, currency); a group with no account in its subtree gets one row with currency null and zeros (left join to `own`). Balances of different currencies are never summed.
- Cycle safety: `moveGroup` first takes `pg_advisory_xact_lock(7002)` (plain SQL via `dsl.execute`, as in round 1; the function returns void so it must not be read as a field, which I remembered from round 1), then checks with a recursive CTE whether the new parent is the group itself or below it. A global lock is coarse but simple; the alternative would have been locking the ancestor rows. The concurrent test moves A under B and B under A 25 times in parallel and requires exactly one success per round.
- Test failure 1: `rollupOverThree...` asserted paths starting with "Bank / Retail" for `rollup(retail)`. Test bug: paths are relative to the root passed in, so they are "Retail", "Retail / Zurich". Fixed the test.
- `mvn verify` green after that (took over two minutes in total, the foreground tool call timed out and moved to the background; I now run maven in the background).

## Item 2: average daily balance (DailyBalanceService)
- Design: `account CROSS JOIN generate_series(1, lengthOfMonth) d(n)` in a derived table; the day-end balance is a correlated scalar subquery `coalesce((select sum(amount) from posting join ledger_transaction where account = a.id and booked_at < monthStart + n days), 0)`. Outer select adds `avg(balance) over (partition by account)` and `min(...)` window functions. Day n ends where day n+1 starts, so a posting at exactly 00:00 counts for the day that begins there; everything before the month is in the opening balance automatically (no lower bound). Java only groups the flat rows per account, it computes nothing.
- Guessed: `DSL.generateSeries(1, n).asTable("d", "n")`, `val(OffsetDateTime).plus(Field<Integer>)` for "start + n days". jOOQ rendered `cast(? as timestamp with time zone) + "d"."n" * interval '1 day'`, which is what I wanted. I was not sure the Field.plus on a temporal value would add days and not fail; it worked.
- Compile error: `Incompatible types: Result<Record6<Long,String,Integer,BigDecimal,BigDecimal,BigDecimal>> cannot be converted to Result<Record>` at `Result<Record> rows = dsl.select(...).fetch()`. Generic invariance; fixed with `Result<? extends Record>`.
- Averages: Postgres `avg(numeric)` returns many decimals; test compares with a tolerance (`isCloseTo ... within 0.0001`).
- Tests (3): quiet account, midnight at day 10, midnight at day 1 of the month, 23:59:59.999 on the last day, April posting excluded, opening balance from before the month, intraday dip not counted, negative lowest.
- Passed on the first test run after the compile fix. Observation: one `mvn verify` takes about 2 minutes here (codegen container plus Spring context startup), so the tool's foreground timeout moves it to the background every time.

(end of log)
