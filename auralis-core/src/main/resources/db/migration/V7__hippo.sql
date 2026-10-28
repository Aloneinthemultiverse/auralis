-- TurboVec-HippoRAG: facts extracted from chunks (subject, relation, object) and the
-- entities they name. Embeddings are kept here in full precision; the in-memory index
-- holds them as 2-bit codes together with the exact relationship edges.
CREATE TABLE hippo_entity (
    id        BIGSERIAL PRIMARY KEY,
    name      TEXT NOT NULL UNIQUE,
    embedding vector(768) NOT NULL
);

CREATE TABLE hippo_fact (
    id         BIGSERIAL PRIMARY KEY,
    chunk_id   UUID   NOT NULL REFERENCES chunk (id) ON DELETE CASCADE,
    subject_id BIGINT NOT NULL REFERENCES hippo_entity (id),
    relation   TEXT   NOT NULL,
    object_id  BIGINT NOT NULL REFERENCES hippo_entity (id),
    embedding  vector(768) NOT NULL
);

CREATE INDEX hippo_fact_chunk_idx ON hippo_fact (chunk_id);

-- Chunks whose facts have been extracted (even if none were found), so backfill can resume.
CREATE TABLE hippo_done (
    chunk_id UUID PRIMARY KEY REFERENCES chunk (id) ON DELETE CASCADE
);
