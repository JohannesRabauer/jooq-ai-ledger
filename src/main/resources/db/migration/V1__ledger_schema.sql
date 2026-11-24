-- Double-entry ledger. Amounts are NUMERIC, never floating point.

CREATE TABLE currency (
    code        CHAR(3)  PRIMARY KEY,
    minor_units SMALLINT NOT NULL CHECK (minor_units BETWEEN 0 AND 4)
);

CREATE TABLE customer (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE account (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id     BIGINT      NOT NULL REFERENCES customer (id),
    iban            TEXT        NOT NULL UNIQUE,
    currency        CHAR(3)     NOT NULL REFERENCES currency (code),
    -- Overdraft is only allowed where this is true.
    allow_overdraft BOOLEAN     NOT NULL DEFAULT false,
    opened_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX account_customer_idx ON account (customer_id);

-- Rate: 1 unit of base_currency = rate units of quote_currency, valid from valid_from until the next row.
CREATE TABLE exchange_rate (
    base_currency  CHAR(3)        NOT NULL REFERENCES currency (code),
    quote_currency CHAR(3)        NOT NULL REFERENCES currency (code),
    valid_from     TIMESTAMPTZ    NOT NULL,
    rate           NUMERIC(18, 8) NOT NULL CHECK (rate > 0),
    PRIMARY KEY (base_currency, quote_currency, valid_from)
);

-- One business event (transfer, deposit, fee). Its postings must sum to zero per currency.
CREATE TABLE ledger_transaction (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    booked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    description     TEXT        NOT NULL,
    -- Lets a bank file import run twice without double booking.
    idempotency_key TEXT        UNIQUE
);

CREATE INDEX ledger_transaction_booked_at_idx ON ledger_transaction (booked_at, id);

-- Positive = credit to the account, negative = debit. Currency is the account currency.
CREATE TABLE posting (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id BIGINT         NOT NULL REFERENCES ledger_transaction (id),
    account_id     BIGINT         NOT NULL REFERENCES account (id),
    amount         NUMERIC(19, 4) NOT NULL CHECK (amount <> 0)
);

CREATE INDEX posting_account_idx ON posting (account_id, id);
CREATE INDEX posting_transaction_idx ON posting (transaction_id);
