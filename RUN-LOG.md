# RUN-LOG (run2, jOOQ docs MCP available)

MCP tools were available (jooq-docs: 6 tools loaded via ToolSearch).

## Setup
- `mvn -q -B generate-sources` works; generated classes in target/generated-sources/jooq. Fields are plain TableFields (OffsetDateTime, BigDecimal, Long).

## MCP calls
1. searchDocumentation "multiset nested collections" - gave general multiset description + ad-hoc converter snippets. Helped confirm `multiset(...).convertFrom(...)`/`Records.mapping`. Did not change much (I already knew it).
2. searchDocumentation "seek clause keyset pagination" - confirmed SEEK clause exists (orderBy().seek()), row predicate rendering. Confirmatory.
3. getQueryDslReference "INSERT ON CONFLICT DO NOTHING" - returned only MySQL/SQLServer/MariaDB rendering of ON CONFLICT .. SET ALL TO EXCLUDED; NOT useful for Postgres DO NOTHING. Relied on own knowledge (`onConflict(field).doNothing()`).
4. getSqlExamples "window function running total sum over order by" - generic text, truncated; confirmed `sum(x).over(orderBy(...))`. Confirmatory.
5. getSqlExamples "multiset convertFrom Records.mapping into records" - truncated, partially relevant snippets (row(..).mapping, convertFrom). Confirmed pattern.
6. searchDocumentation "aggregate FILTER WHERE clause" - confirms FILTER exists, no Java method name shown in the snippet; I will use `filterWhere` from memory.
7. searchDocumentation "SELECT FOR UPDATE locking" - snippet claims "FOR UPDATE NOWAIT: This is the default behaviour" which looks wrong/misleading for Postgres (plain FOR UPDATE waits). Did not follow; I use plain forUpdate() which waits.

## Decisions / guesses
- Cross-currency: strict reading of "postings sum to zero per currency" needs 4 postings via per-currency FX clearing accounts (iban `FX-CLEARING-<CUR>`, overdraft allowed, lazily created, no migration). Guess/design choice.
- Statement order = (transaction.booked_at, posting.id) (matches the index ledger_transaction_booked_at_idx); keyset cursor on that pair.
- Concurrency: lock account rows with SELECT .. FOR UPDATE ordered by id, then check balance, then insert.

## Progress log
