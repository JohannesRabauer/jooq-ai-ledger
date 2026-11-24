# Run log (run1, bare agent)

Honest log of compile errors, test failures, approach changes and guesses.

## Setup
- Read README/TASK, pom, V1/V2, scaffold test. Generated classes did not exist yet, so ran `mvn -q -B verify` once on the untouched scaffold: green (1 test, smoke test). Then read generated Account/Posting/LedgerTransaction/ExchangeRate/Customer table classes for field names and types.

## Design decisions (guesses where the task was ambiguous)
- Debits in the customer report are shown as positive magnitudes (sum of -amount for amount < 0). Guess.
- "Booking order" = (ledger_transaction.booked_at, posting.id). Keyset cursor is that pair.
- Search amount filter: applies per posting on abs(amount), together with the account filter on the SAME posting (one EXISTS subquery). Guess.
- Cross-currency: "postings sum to zero per currency" cannot hold with only two postings in two currencies, so I book 4 postings via per-currency FX clearing accounts (iban `FX-CLEARING-<CCY>`, allow_overdraft = true), created lazily. No schema change needed.
- Concurrency: `SELECT ... FOR UPDATE` on the involved account rows in id order, then balance check, then insert.

## Implementation round 1
- First attempt to write the files with a bash heredoc failed (`unexpected EOF while looking for matching '` - shell quoting problem, not jOOQ). Nothing was written; redid it with the Write tool.
- `mvn -q -B compile` of all five services: no compile errors on the first try.
- Unsure / guessed without compiler help: `DSL.field("pg_advisory_xact_lock({0})", Long.class, 7001L)` plain-SQL templating (bind value as `{0}`); `sum(...).filterWhere(...)` (Postgres FILTER clause); `Records.mapping(Record::new)` inside `multiset(...).convertFrom(...)`; `row(a,b).gt(row(x,y))` for the keyset predicate; `.onConflict(field).doNothing().returningResult(...).fetchOne(field)` returns null when nothing is inserted. All are checked by the tests below.

## Test round 1 (4 failing of 17)
1. Runtime, in `LedgerService.fxClearingAccount`: `select pg_advisory_xact_lock(?)` via `DSL.field("pg_advisory_xact_lock({0})", Long.class, ...)` failed with `DataAccessException: Error while reading field: pg_advisory_xact_lock(7001), at JDBC index: 1` / PSQLException `Unzulaessiger Wert fuer den Typ long`. Cause: the function returns `void`, jOOQ tried to read it as Long. Fix: `dsl.execute("select pg_advisory_xact_lock(?)", 7001L)` (plain SQL, result ignored). Concept: plain SQL templating / `DSLContext.execute(String, Object...)`. This broke 2 cross-currency tests (the second one only looked like an assertion failure because the exception type was wrong).
2. Test bug, `searchAppliesOnlyTheGivenFilters`: asserted `hasSize(5)` for an unfiltered search but DB had only 3 transactions when run first. Changed to limit 2 / hasSize(2).
3. Test bug, `searchSqlContainsOnlyGivenFilters`: `doesNotContain("where")` failed because the nested `multiset` subquery has its own where clause (`... where posting.transaction_id = ledger_transaction.id`). Fixed by only inspecting the SQL of the outer query (substring after the multiset). Rendered SQL showed the multiset is `select coalesce(jsonb_agg(jsonb_build_array(...)), jsonb_build_array())` on Postgres, fine.

## Test round 2
- After the three fixes above `mvn -q -B verify` is green. No migration added (V1/V2 untouched), no generated sources edited.
- Not verified by the tests: statement performance (the window function runs over all postings of the account for each page, the keyset predicate is applied outside it; correct, but not index-seek fast for deep pages).
