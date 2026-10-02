CREATE EXTENSION IF NOT EXISTS vector;

-- An evidence source: book, paper, datasheet, standard, report, ...
CREATE TABLE source_document (
    id           UUID PRIMARY KEY,
    title        TEXT        NOT NULL,
    source_type  TEXT        NOT NULL DEFAULT 'document',
    uri          TEXT,
    content_hash TEXT        NOT NULL UNIQUE,
    metadata     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A retrievable unit of evidence. Searched two ways: dense (embedding)
-- and lexical (tsv), then fused.
CREATE TABLE chunk (
    id          UUID PRIMARY KEY,
    document_id UUID    NOT NULL REFERENCES source_document (id) ON DELETE CASCADE,
    ordinal     INT     NOT NULL,
    content     TEXT    NOT NULL,
    embedding   vector(768) NOT NULL,
    tsv         tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    metadata    JSONB   NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (document_id, ordinal)
);

CREATE INDEX chunk_embedding_hnsw ON chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX chunk_tsv_gin ON chunk USING gin (tsv);
CREATE INDEX chunk_document_idx ON chunk (document_id);
