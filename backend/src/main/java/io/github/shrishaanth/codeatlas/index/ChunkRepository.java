package io.github.shrishaanth.codeatlas.index;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Stores and searches the chunks of one analysis. Ranking is finished in {@link ChunkSearch}. */
@Repository
public class ChunkRepository {

    /** Rows fetched from Postgres before the structural boosts are applied. */
    public record Match(CodeChunk chunk, double textScore) {
    }

    private static final RowMapper<Match> MATCH = (rs, i) -> new Match(
            new CodeChunk(rs.getString("path"), rs.getInt("start_line"), rs.getInt("end_line"),
                    rs.getString("kind"), rs.getString("symbol"), rs.getString("body"),
                    rs.getBoolean("is_test"), rs.getBoolean("is_generated"), rs.getDouble("file_score")),
            rs.getDouble("text_score"));

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public ChunkRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(UUID analysisId, List<CodeChunk> chunks) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO chunk (analysis_id, path, start_line, end_line, kind, symbol, is_test, is_generated,
                                   file_score, body)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                chunks, 500, (ps, c) -> {
                    ps.setObject(1, analysisId);
                    ps.setString(2, c.path());
                    ps.setInt(3, c.startLine());
                    ps.setInt(4, c.endLine());
                    ps.setString(5, c.kind());
                    ps.setString(6, c.symbol());
                    ps.setBoolean(7, c.test());
                    ps.setBoolean(8, c.generated());
                    ps.setDouble(9, c.fileScore());
                    ps.setString(10, c.text());
                });
    }

    /**
     * Full-text candidates for a question, most relevant first. The final ranking, which adds the
     * structural boosts, happens in {@link ChunkSearch}.
     */
    public List<Match> search(UUID analysisId, String query, int limit) {
        return jdbc.sql("""
                        SELECT path, start_line, end_line, kind, symbol, is_test, is_generated, file_score, body,
                               ts_rank_cd(tsv, q) AS text_score
                        FROM chunk, websearch_to_tsquery('simple', :query) q
                        WHERE analysis_id = :id AND tsv @@ q
                        ORDER BY text_score DESC
                        LIMIT :limit""")
                .param("id", analysisId).param("query", query).param("limit", limit)
                .query(MATCH).list();
    }

    public int countFor(UUID analysisId) {
        return jdbc.sql("SELECT count(*) FROM chunk WHERE analysis_id = :id")
                .param("id", analysisId).query(Integer.class).single();
    }

    /** Chunks are only useful while their analysis is the newest for that repository. */
    public int deleteOlderThan(int keepAnalyses) {
        return jdbc.sql("""
                        DELETE FROM chunk WHERE analysis_id IN (
                            SELECT id FROM analysis WHERE status = 'DONE' ORDER BY finished_at DESC OFFSET :keep)""")
                .param("keep", keepAnalyses).update();
    }
}
