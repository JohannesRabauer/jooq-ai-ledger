# jooq-ai-ledger

A small double-entry ledger (accounts, postings, multi-currency) on Spring Boot, PostgreSQL, Flyway and jOOQ. It is the playground for one question: how good is the jOOQ code an AI coding agent writes?

`main` holds only the scaffold: schema, code generation, a smoke test. The task the AI gets is in [TASK.md](TASK.md).

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

`customer` 1-n `account` (own currency, optional overdraft), `ledger_transaction` 1-n `posting` (signed amount per account), `exchange_rate` (rate valid from a point in time), `currency`.
