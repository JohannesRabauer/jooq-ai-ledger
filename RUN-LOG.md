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
