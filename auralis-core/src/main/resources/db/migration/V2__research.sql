-- One investigation of an engineering objective.
CREATE TABLE research_session (
    id           UUID PRIMARY KEY,
    objective    TEXT        NOT NULL,
    status       TEXT        NOT NULL,          -- RUNNING | SUFFICIENT | EXHAUSTED | FAILED
    deep_mode    TEXT        NOT NULL,          -- NONE | LOCAL | KAGGLE
    max_rounds   INT         NOT NULL,
    rounds_run   INT         NOT NULL DEFAULT 0,
    report       TEXT,
    sufficiency  JSONB,
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A research question; gap questions point at the question that raised them.
CREATE TABLE research_question (
    id          UUID PRIMARY KEY,
    session_id  UUID    NOT NULL REFERENCES research_session (id) ON DELETE CASCADE,
    parent_id   UUID    REFERENCES research_question (id),
    round       INT     NOT NULL,
    origin      TEXT    NOT NULL,              -- PLAN | GAP
    text        TEXT    NOT NULL,
    status      TEXT    NOT NULL,              -- OPEN | ANSWERED | UNRESOLVED
    answer      TEXT,
    answered_p  DOUBLE PRECISION              -- Laya: P(evidence answers it)
);
CREATE INDEX research_question_session_idx ON research_question (session_id);

-- Evidence attached to a question: a knowledge-base chunk or a web finding.
CREATE TABLE research_evidence (
    id           UUID PRIMARY KEY,
    question_id  UUID    NOT NULL REFERENCES research_question (id) ON DELETE CASCADE,
    kind         TEXT    NOT NULL,             -- CHUNK | WEB
    chunk_id     UUID    REFERENCES chunk (id) ON DELETE SET NULL,
    source_title TEXT,
    source_uri   TEXT,
    content      TEXT    NOT NULL,
    relevance    DOUBLE PRECISION,
    round        INT     NOT NULL
);
CREATE INDEX research_evidence_question_idx ON research_evidence (question_id);

-- Human-readable trace of what the loop did and why.
CREATE TABLE research_event (
    id          BIGSERIAL PRIMARY KEY,
    session_id  UUID        NOT NULL REFERENCES research_session (id) ON DELETE CASCADE,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    round       INT         NOT NULL,
    type        TEXT        NOT NULL,
    message     TEXT        NOT NULL
);
CREATE INDEX research_event_session_idx ON research_event (session_id, id);
