CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS document (
  id           BIGSERIAL PRIMARY KEY,
  source       TEXT        NOT NULL CHECK (source IN ('jira', 'confluence')),
  external_id  TEXT        NOT NULL,
  title        TEXT        NOT NULL,
  url          TEXT        NOT NULL,
  metadata     JSONB       NOT NULL DEFAULT '{}',
  content_hash TEXT        NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL,
  ingested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (source, external_id)
);

CREATE TABLE IF NOT EXISTS chunk (
  id           BIGSERIAL PRIMARY KEY,
  document_id  BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
  position     INT    NOT NULL,
  content      TEXT   NOT NULL,
  embedding    vector(${embeddingDim}) NOT NULL,
  tsv          tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED
);

CREATE INDEX IF NOT EXISTS chunk_embedding_idx ON chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS chunk_tsv_idx       ON chunk USING gin (tsv);
CREATE INDEX IF NOT EXISTS document_metadata_idx ON document USING gin (metadata);

CREATE TABLE IF NOT EXISTS sync_state (
  source       TEXT PRIMARY KEY,
  last_sync_at TIMESTAMPTZ NOT NULL
);
