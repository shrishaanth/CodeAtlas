-- Baseline migration so Flyway owns the schema from day one.
-- Real tables (repositories, analysis runs, reports) arrive in M1.
CREATE TABLE schema_note (
    id    SMALLINT PRIMARY KEY,
    note  TEXT NOT NULL
);

INSERT INTO schema_note (id, note) VALUES (1, 'CodeAtlas schema baseline');
