--liquibase formatted sql

--changeset petshop:1-pets
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'pets'
--comment: MARK_RAN: a database the shop created before it had a changelog already has the table, and adopts it.
CREATE TABLE pets (
    id      BIGINT  PRIMARY KEY,
    name    TEXT    NOT NULL,
    species TEXT    NOT NULL,
    adopted BOOLEAN NOT NULL
);

--changeset petshop:2-outbox
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'outbox'
--comment: seq is Postgres's identity, so it keeps counting across restarts and instances.
CREATE TABLE outbox (
    seq        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind       TEXT    NOT NULL CHECK (kind IN ('arrived', 'adopted', 'returned')),
    pet_id     BIGINT  NOT NULL,
    pet_name   TEXT    NOT NULL,
    species    TEXT    NOT NULL,
    adopted    BOOLEAN NOT NULL,
    adopted_by TEXT
);

--changeset petshop:3-outbox-metadata
--comment: What the write carried (Carriers.capture()), as JSON: a trace's traceparent, so the relay continues it.
ALTER TABLE outbox ADD COLUMN metadata TEXT;
