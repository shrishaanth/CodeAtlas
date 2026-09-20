package io.github.shrishaanth.codeatlas.eval;

import io.github.shrishaanth.codeatlas.index.ChunkRepository;
import io.github.shrishaanth.codeatlas.index.ChunkSearch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Measures question-answering retrieval without a language model: for symbols the repository really
 * contains, ask "where is X defined?" and see whether the code defining it comes back.
 * <pre>
 * java -cp &lt;classpath&gt; io.github.shrishaanth.codeatlas.eval.RetrievalEvalCommand \
 *     jdbc:postgresql://localhost:5432/codeatlas codeatlas codeatlas &lt;analysis-id&gt;
 * </pre>
 * A question built from a symbol name is an easy case; treat the result as a floor, not a score for
 * question answering as a whole. Results: docs/evaluation.md.
 */
public final class RetrievalEvalCommand {

    private static final int SAMPLE = 60;
    private static final long SEED = 7;

    private RetrievalEvalCommand() {
    }

    public static void main(String[] args) {
        // Same reason as CodeAtlasApplication.main: Postgres rejects legacy zone names such as
        // "Asia/Calcutta", which the driver sends on connect.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        if (args.length < 4) {
            System.err.println("usage: RetrievalEvalCommand <jdbcUrl> <user> <password> <analysisId>");
            System.exit(2);
        }
        DataSource dataSource = new DriverManagerDataSource(args[0], args[1], args[2]);
        JdbcClient jdbc = JdbcClient.create(dataSource);
        ChunkRepository chunks = new ChunkRepository(jdbc, new JdbcTemplate(dataSource));
        UUID analysisId = UUID.fromString(args[3]);

        record Target(String symbol, String path, int startLine) {
        }
        List<Target> all = jdbc.sql("""
                        SELECT symbol, path, start_line FROM chunk
                        WHERE analysis_id = :id AND symbol IS NOT NULL AND kind IN ('function', 'method', 'class')
                          AND is_test = false
                        ORDER BY path, start_line""")
                .param("id", analysisId)
                .query((rs, i) -> new Target(rs.getString("symbol"), rs.getString("path"), rs.getInt("start_line")))
                .list();
        if (all.isEmpty()) {
            System.err.println("No indexed symbols for that analysis.");
            System.exit(1);
        }
        List<Target> sample = new ArrayList<>(all);
        java.util.Collections.shuffle(sample, new Random(SEED));
        sample = sample.subList(0, Math.min(SAMPLE, sample.size()));

        int found = 0, first = 0;
        List<String> misses = new ArrayList<>();
        for (Target t : sample) {
            String name = t.symbol().contains(".") ? t.symbol().substring(t.symbol().indexOf('.') + 1) : t.symbol();
            String question = "where is " + name + " defined";
            List<ChunkSearch.Result> results = ChunkSearch.rank(
                    chunks.search(analysisId, ChunkSearch.terms(question), ChunkSearch.CANDIDATES),
                    question, ChunkSearch.DEFAULT_LIMIT);
            int rank = -1;
            for (int i = 0; i < results.size(); i++) {
                var c = results.get(i).chunk();
                if (c.path().equals(t.path()) && c.startLine() == t.startLine()) {
                    rank = i + 1;
                    break;
                }
            }
            if (rank > 0) found++;
            if (rank == 1) first++;
            if (rank < 0) misses.add(t.symbol() + " (" + t.path() + ":" + t.startLine() + ")");
        }

        System.out.printf("Symbols sampled: %d of %d indexed%n", sample.size(), all.size());
        System.out.printf("Defining code in the top %d: %d (%.0f%%)%n", ChunkSearch.DEFAULT_LIMIT, found,
                100.0 * found / sample.size());
        System.out.printf("Defining code ranked first: %d (%.0f%%)%n", first, 100.0 * first / sample.size());
        if (!misses.isEmpty()) {
            System.out.println("Missed:");
            misses.stream().limit(15).forEach(m -> System.out.println("  " + m));
        }
    }
}
