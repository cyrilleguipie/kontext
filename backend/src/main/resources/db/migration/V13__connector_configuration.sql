CREATE TABLE connector_configuration (
    connector_id TEXT PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    encrypted_payload BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
