-- Retrievable pieces of code for question answering. One row per chunk, tied to the analysis that
-- produced it, so an answer can only cite code from the analyzed commit.
CREATE TABLE chunk (
    id           BIGSERIAL PRIMARY KEY,
    analysis_id  UUID        NOT NULL REFERENCES analysis (id) ON DELETE CASCADE,
    path         TEXT        NOT NULL,
    start_line   INT         NOT NULL,
    end_line     INT         NOT NULL,
    kind         TEXT        NOT NULL,
    symbol       TEXT,
    is_test      BOOLEAN     NOT NULL DEFAULT FALSE,
    is_generated BOOLEAN     NOT NULL DEFAULT FALSE,
    file_score   REAL        NOT NULL DEFAULT 0,
    body         TEXT        NOT NULL,
    -- Code identifiers are matched as words; 'simple' avoids English stemming of names like "getter".
    tsv          TSVECTOR GENERATED ALWAYS AS (
                     setweight(to_tsvector('simple', coalesce(symbol, '')), 'A')
                     || setweight(to_tsvector('simple', replace(path, '/', ' ')), 'B')
                     || setweight(to_tsvector('simple', body), 'C')
                 ) STORED
);

CREATE INDEX chunk_tsv_idx ON chunk USING GIN (tsv);
CREATE INDEX chunk_analysis_idx ON chunk (analysis_id);
