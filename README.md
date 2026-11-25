# jooq-ai-ledger

A small double-entry ledger (accounts, postings, multi-currency) on Spring Boot, PostgreSQL, Flyway and jOOQ. It is the playground for one question: how good is the jOOQ code an AI coding agent writes?

`main` holds only the scaffold: schema, code generation, a smoke test. The tasks the AI gets are [TASK.md](TASK.md) (the basics) and [TASK-2.md](TASK-2.md) (harder: recursive groups, daily balances, as-of FX valuation, reconciliation, a typed IBAN through code generation, other SQL dialects).

## Stack

- Java 25, Spring Boot 4.2, Maven
- PostgreSQL 17, schema by Flyway (`src/main/resources/db/migration`)
- jOOQ 3.21 (version managed by Spring Boot), classes generated at build time from the migrations with the [Testcontainers jOOQ codegen plugin](https://github.com/testcontainers/testcontainers-jooq-codegen-maven-plugin)

## Build and test

Needs JDK 25 and Docker (the code generation and the tests start a throwaway Postgres container).

```
mvn verify
```

`.mvn/jvm.config` sets `-Dapi.version=1.44` because the plugin bundles an older Testcontainers that otherwise cannot talk to Docker Engine 29 or newer.

## Run locally

```
docker compose up -d
mvn spring-boot:run
```

## Schema

`customer` 1-n `account` (own currency, optional overdraft, optional `account_group` from a tree), `bank_line` (external statement lines to reconcile), `ledger_transaction` 1-n `posting` (signed amount per account), `exchange_rate` (rate valid from a point in time), `currency`.
