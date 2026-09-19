DROP TABLE schema_note;

-- One row per analysis request. The report is stored as json (not jsonb) so it is returned
-- exactly as written: jsonb would reorder object keys.
CREATE TABLE analysis (
    id           UUID        PRIMARY KEY,
    source       TEXT        NOT NULL,
    status       TEXT        NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    stage        TEXT,
    percent      INT         NOT NULL DEFAULT 0,
    detail       TEXT,
    error        TEXT,
    repo_name    TEXT,
    commit_sha   TEXT,
    created_at   TIMESTAMPTZ NOT NULL,
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    report       JSON
);

CREATE INDEX analysis_source_created_idx ON analysis (source, created_at DESC);
CREATE INDEX analysis_status_idx ON analysis (status);
