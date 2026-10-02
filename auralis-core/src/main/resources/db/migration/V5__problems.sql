-- A problem statement worked as a project: decomposed into parts, each part
-- researched into a dedicated notebook, then designed from that evidence.
CREATE TABLE problem (
    id          UUID PRIMARY KEY,
    statement   TEXT        NOT NULL,
    title       TEXT,
    notebook    TEXT        NOT NULL,
    status      TEXT        NOT NULL,      -- DECOMPOSING | COLLECTING | READY | FAILED
    breakdown   JSONB,
    error       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A design can draw its evidence from a notebook (e.g. a problem's notebook).
ALTER TABLE design ADD COLUMN notebook TEXT;
