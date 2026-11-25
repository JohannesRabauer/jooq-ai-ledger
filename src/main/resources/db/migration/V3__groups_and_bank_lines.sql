-- Tree of account groups (a bank's organisation: bank / region / branch), accounts may belong to one group.
CREATE TABLE account_group (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id BIGINT REFERENCES account_group (id),
    name      TEXT NOT NULL
);

CREATE INDEX account_group_parent_idx ON account_group (parent_id);

ALTER TABLE account ADD COLUMN group_id BIGINT REFERENCES account_group (id);

CREATE INDEX account_group_id_idx ON account (group_id);

-- Lines of an external bank statement, to be reconciled against our postings.
CREATE TABLE bank_line (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id         BIGINT         NOT NULL REFERENCES account (id),
    external_ref       TEXT           NOT NULL,
    value_date         DATE           NOT NULL,
    amount             NUMERIC(19, 4) NOT NULL,
    -- Set once the line is matched with one of our postings.
    matched_posting_id BIGINT UNIQUE REFERENCES posting (id),
    UNIQUE (account_id, external_ref)
);

CREATE INDEX bank_line_account_date_idx ON bank_line (account_id, value_date);
