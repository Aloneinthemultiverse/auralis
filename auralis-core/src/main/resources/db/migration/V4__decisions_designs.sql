-- Decision ledger: every control decision Auralis makes, with Laya's vote and,
-- when policy calls for it, the LLM's — so decisions are traceable and Laya's
-- agreement with the LLM can be measured and used to fine-tune it.
CREATE TABLE decision (
    id               UUID PRIMARY KEY,
    scope            TEXT        NOT NULL,       -- e.g. research, design, retrieval, claude
    scope_id         UUID,                       -- session / design the decision belongs to
    kind             TEXT        NOT NULL,       -- e.g. sufficiency, answerability, select_alternative
    policy           TEXT        NOT NULL,       -- LAYA | LAYA_THEN_LLM | LAYA_ADVISORY
    question         TEXT        NOT NULL,
    state_excerpt    TEXT        NOT NULL,
    options          JSONB,                      -- choice labels -> descriptions, or null for yes/no
    laya_answer      TEXT,                       -- chosen label, or 'yes'/'no'
    laya_p           DOUBLE PRECISION,           -- P(laya_answer)
    laya_probs       JSONB,
    llm_answer       TEXT,
    llm_rationale    TEXT,
    final_answer     TEXT        NOT NULL,
    decided_by       TEXT        NOT NULL,       -- LAYA | LLM
    latency_ms       BIGINT      NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX decision_scope_idx ON decision (scope, scope_id, created_at);
CREATE INDEX decision_kind_idx ON decision (kind, created_at);

-- A design investigation; versions form a tree (what-if branches).
CREATE TABLE design (
    id                  UUID PRIMARY KEY,
    objective           TEXT        NOT NULL,
    research_session_id UUID REFERENCES research_session (id) ON DELETE SET NULL,
    status              TEXT        NOT NULL,   -- RUNNING | VERIFIED | NEEDS_INPUT | EXHAUSTED | FAILED
    max_iterations      INT         NOT NULL,
    error               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE design_version (
    id            UUID PRIMARY KEY,
    design_id     UUID        NOT NULL REFERENCES design (id) ON DELETE CASCADE,
    parent_id     UUID REFERENCES design_version (id),
    label         TEXT        NOT NULL,          -- v1, v1.1, v2 ...
    iteration     INT         NOT NULL,
    alternative   TEXT        NOT NULL,          -- which alternative this version develops
    spec          TEXT        NOT NULL,          -- the design itself (markdown)
    artifacts     JSONB       NOT NULL DEFAULT '{}'::jsonb,  -- file name -> content (RTL, testbench, ...)
    rationale     TEXT,
    constraints   TEXT,                          -- extra / changed constraints (what-if)
    verification  TEXT,                          -- tool results and the verifier's reading
    verdict       TEXT,                          -- PASS | FIX | RESEARCH | ASK_USER
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX design_version_design_idx ON design_version (design_id, created_at);

CREATE TABLE design_alternative (
    id           UUID PRIMARY KEY,
    design_id    UUID NOT NULL REFERENCES design (id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    summary      TEXT NOT NULL,
    pros         TEXT,
    cons         TEXT,
    assumptions  TEXT,
    fit_score    DOUBLE PRECISION,               -- Laya: expected fit to requirements (0..k-1)
    selected     BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE design_event (
    id         BIGSERIAL PRIMARY KEY,
    design_id  UUID        NOT NULL REFERENCES design (id) ON DELETE CASCADE,
    at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    iteration  INT         NOT NULL,
    type       TEXT        NOT NULL,
    message    TEXT        NOT NULL
);
CREATE INDEX design_event_design_idx ON design_event (design_id, id);
