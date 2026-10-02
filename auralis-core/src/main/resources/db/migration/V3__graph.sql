-- A small property graph for traversable knowledge: catalogs, concepts,
-- books/chapters, and (later) requirements -> decisions -> experiments.
CREATE TABLE kg_node (
    id          UUID PRIMARY KEY,
    kind        TEXT  NOT NULL,                 -- e.g. CATALOG, CATEGORY, TOOL, BOOK, CONCEPT
    name        TEXT  NOT NULL,
    namespace   TEXT  NOT NULL,                 -- which import/graph it belongs to, e.g. 'osint'
    external_id TEXT,                           -- stable key inside the namespace
    uri         TEXT,
    document_id UUID  REFERENCES source_document (id) ON DELETE SET NULL,
    properties  JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (namespace, external_id)
);
CREATE INDEX kg_node_name_idx ON kg_node USING gin (to_tsvector('simple', name));
CREATE INDEX kg_node_kind_idx ON kg_node (namespace, kind);

CREATE TABLE kg_edge (
    src        UUID NOT NULL REFERENCES kg_node (id) ON DELETE CASCADE,
    dst        UUID NOT NULL REFERENCES kg_node (id) ON DELETE CASCADE,
    rel        TEXT NOT NULL,                   -- e.g. CONTAINS, CITES, RELATED_TO
    properties JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (src, dst, rel)
);
CREATE INDEX kg_edge_dst_idx ON kg_edge (dst, rel);
