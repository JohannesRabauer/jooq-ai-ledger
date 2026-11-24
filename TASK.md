# Task: build the ledger queries

The schema and the jOOQ code generation are already set up. Your job is the application code on top: a small double-entry ledger service. Use jOOQ's DSL for all SQL. Use the generated classes in `dev.rabauer.ledger.jooq`; never edit them (they are rebuilt from `src/main/resources/db/migration` on every build).

Rules of the domain:
- A ledger transaction has two or more postings. Postings are signed: negative debits an account, positive credits it.
- All postings of one transaction sum to zero per currency.
- Money is `NUMERIC`/`BigDecimal`, never `double`.
- A balance is the sum of an account's postings. Accounts with `allow_overdraft = false` must never go below zero.
- Postings are append-only. Corrections are new transactions.

Build these, each as a service method with tests against a real Postgres (Testcontainers):

1. **Transfer.** Move an amount between two accounts of the same currency in one database transaction. Must be safe when many transfers run concurrently, and must reject a transfer that would overdraw an account that forbids it.
2. **Statement.** The postings of one account in booking order, each with the running balance after it. Page through it with keyset pagination (no `OFFSET`).
3. **Customer report for one month.** For each customer: their accounts, and for each account the month's total credits, total debits and closing balance. One query, nested result (`multiset`), no per-account follow-up queries.
4. **Transaction search.** Optional filters: date range, minimum and maximum amount, text in the description, account. Only the filters that are given may appear in the SQL.
5. **Cross-currency transfer.** Debit an account in one currency and credit another account in a different currency, using the exchange rate valid at the booking time (the latest `exchange_rate` row with `valid_from <= booking time`).
6. **Bank file import.** Import a batch of transactions that carry an `idempotency_key`. Running the same import twice must not book anything twice.

Tests must show the invariants, not only the happy path: all postings sum to zero, no forbidden overdraft, and the total of all balances stays the same after many concurrent transfers.

Log the SQL jOOQ renders (already enabled in `application.yaml`).
