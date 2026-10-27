CREATE TABLE connector_sync_status (
    connector_id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    running BOOLEAN NOT NULL DEFAULT FALSE,
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    indexed INTEGER,
    deleted INTEGER,
    error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
