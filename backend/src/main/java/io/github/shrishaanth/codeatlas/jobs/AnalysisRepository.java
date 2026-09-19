package io.github.shrishaanth.codeatlas.jobs;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AnalysisRepository {

    private static final String COLUMNS = """
            id, source, status, stage, percent, detail, error, repo_name, commit_sha,
            created_at, started_at, finished_at""";

    private static final RowMapper<AnalysisStatus> STATUS = (rs, i) -> new AnalysisStatus(
            rs.getObject("id", UUID.class),
            rs.getString("source"),
            AnalysisStatus.State.valueOf(rs.getString("status")),
            rs.getString("stage"),
            rs.getInt("percent"),
            rs.getString("detail"),
            rs.getString("error"),
            rs.getString("repo_name"),
            rs.getString("commit_sha"),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("finished_at")));

    private final JdbcClient jdbc;

    public AnalysisRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertQueued(UUID id, String source, Instant now) {
        jdbc.sql("INSERT INTO analysis (id, source, status, stage, detail, created_at) "
                        + "VALUES (:id, :source, 'QUEUED', 'queued', 'Waiting to start', :now)")
                .param("id", id).param("source", source).param("now", Timestamp.from(now))
                .update();
    }

    public Optional<AnalysisStatus> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM analysis WHERE id = :id")
                .param("id", id).query(STATUS).optional();
    }

    /** Most recent unfinished analysis of the same source, so repeated clicks don't queue duplicates. */
    public Optional<AnalysisStatus> findActive(String source) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM analysis WHERE source = :source "
                        + "AND status IN ('QUEUED', 'RUNNING') ORDER BY created_at DESC LIMIT 1")
                .param("source", source).query(STATUS).optional();
    }

    public List<AnalysisStatus> recentDone(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM analysis WHERE status = 'DONE' "
                        + "ORDER BY finished_at DESC LIMIT :limit")
                .param("limit", limit).query(STATUS).list();
    }

    public void markRunning(UUID id, Instant now) {
        jdbc.sql("UPDATE analysis SET status = 'RUNNING', started_at = :now, stage = 'fetch', "
                        + "detail = 'Fetching repository' WHERE id = :id")
                .param("id", id).param("now", Timestamp.from(now)).update();
    }

    public void updateProgress(UUID id, String stage, int percent, String detail) {
        jdbc.sql("UPDATE analysis SET stage = :stage, percent = :percent, detail = :detail "
                        + "WHERE id = :id AND status = 'RUNNING'")
                .param("id", id).param("stage", stage).param("percent", percent).param("detail", detail)
                .update();
    }

    public void markDone(UUID id, String repoName, String commit, String reportJson, Instant now) {
        jdbc.sql("UPDATE analysis SET status = 'DONE', stage = 'done', percent = 100, detail = 'Analysis complete', "
                        + "repo_name = :name, commit_sha = :commit, report = CAST(:report AS json), finished_at = :now "
                        + "WHERE id = :id")
                .param("id", id).param("name", repoName).param("commit", commit).param("report", reportJson)
                .param("now", Timestamp.from(now)).update();
    }

    public void markFailed(UUID id, String error, Instant now) {
        jdbc.sql("UPDATE analysis SET status = 'FAILED', error = :error, finished_at = :now WHERE id = :id")
                .param("id", id).param("error", error).param("now", Timestamp.from(now)).update();
    }

    /** Analyses left unfinished by a previous process (e.g. the host restarted) can never finish. */
    public int failAbandoned(Instant now) {
        return jdbc.sql("UPDATE analysis SET status = 'FAILED', error = 'The server restarted before this analysis finished', "
                        + "finished_at = :now WHERE status IN ('QUEUED', 'RUNNING')")
                .param("now", Timestamp.from(now)).update();
    }

    public Optional<String> findReportJson(UUID id) {
        return jdbc.sql("SELECT report::text FROM analysis WHERE id = :id AND status = 'DONE'")
                .param("id", id).query(String.class).optional();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
