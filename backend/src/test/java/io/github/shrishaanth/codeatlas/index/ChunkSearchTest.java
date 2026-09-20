package io.github.shrishaanth.codeatlas.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkSearchTest {

    private static ChunkRepository.Match match(String path, String symbol, double textScore, boolean test,
                                               double fileScore) {
        return new ChunkRepository.Match(
                new CodeChunk(path, 1, 20, symbol == null ? "module" : "function", symbol, "code", test, false,
                        fileScore),
                textScore);
    }

    @Test
    void anExactSymbolMatchBeatsASlightlyBetterTextMatch() {
        List<ChunkSearch.Result> results = ChunkSearch.rank(List.of(
                match("a/other.py", "unrelated", 1.0, false, 0),
                match("a/target.py", "send_static_file", 0.8, false, 0)), "where is send_static_file defined", 8);

        assertThat(results).extracting(r -> r.chunk().path()).containsExactly("a/target.py", "a/other.py");
    }

    @Test
    void namingAFileFindsIt() {
        List<ChunkSearch.Result> results = ChunkSearch.rank(List.of(
                match("src/app/other.py", null, 1.0, false, 0),
                match("src/app/cli.py", null, 0.9, false, 0)), "what does the cli module do", 8);

        assertThat(results.get(0).chunk().path()).isEqualTo("src/app/cli.py");
    }

    @Test
    void testFilesRankBelowEqualSourceFiles() {
        List<ChunkSearch.Result> results = ChunkSearch.rank(List.of(
                match("tests/test_cli.py", "run", 1.0, true, 0),
                match("src/cli.py", "run", 1.0, false, 0)), "run", 8);

        assertThat(results).extracting(r -> r.chunk().path()).containsExactly("src/cli.py", "tests/test_cli.py");
    }

    @Test
    void centralFilesBreakTiesBetweenEqualMatches() {
        List<ChunkSearch.Result> results = ChunkSearch.rank(List.of(
                match("b.py", null, 1.0, false, 0.1),
                match("a.py", null, 1.0, false, 0.9)), "something", 8);

        assertThat(results.get(0).chunk().path()).isEqualTo("a.py");
    }

    @Test
    void takesAtMostThreeChunksFromOneFileSoAnswersSpreadOut() {
        List<ChunkRepository.Match> matches = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            matches.add(new ChunkRepository.Match(
                    new CodeChunk("big.py", i * 10 + 1, i * 10 + 9, "function", "f" + i, "code", false, false, 0.5),
                    1.0 - i * 0.01));
        }
        matches.add(match("small.py", null, 0.5, false, 0));

        List<ChunkSearch.Result> results = ChunkSearch.rank(matches, "code", 8);

        assertThat(results).hasSize(4);
        assertThat(results).filteredOn(r -> r.chunk().path().equals("big.py")).hasSize(ChunkSearch.MAX_PER_FILE);
        assertThat(results).anySatisfy(r -> assertThat(r.chunk().path()).isEqualTo("small.py"));
    }

    @Test
    void aWordOfTheSymbolCountsToo() {
        // "where is the signature created" should reach Signer.get_signature, and "signing" should reach sign.
        List<ChunkSearch.Result> results = ChunkSearch.rank(List.of(
                match("docs/concepts.rst", null, 1.0, false, 0),
                match("src/signer.py", "Signer.get_signature", 0.8, false, 0)),
                "how does signing work and where is the signature created", 8);

        assertThat(results.get(0).chunk().path()).isEqualTo("src/signer.py");
        assertThat(ChunkSearch.splitWords("TimestampSigner.get_signature"))
                .containsExactly("timestamp", "signer", "get", "signature");
        assertThat(ChunkSearch.stem("signing")).isEqualTo("sign");
        assertThat(ChunkSearch.stem("signed")).isEqualTo("sign");
        assertThat(ChunkSearch.stem("sign")).isEqualTo("sign");
        assertThat(ChunkSearch.stem("less")).as("double s is part of the word").isEqualTo("less");
        assertThat(ChunkSearch.stem("class")).isEqualTo("class");
    }

    @Test
    void buildsAnOrQueryThatCannotBeInjected() {
        assertThat(ChunkRepository.tsQuery(List.of("where", "fetch_movie", "defined")))
                .isEqualTo("where | fetch_movie | defined");
        assertThat(ChunkRepository.tsQuery(List.of("drop table chunk;", "a & b", "''")))
                .isEqualTo("droptablechunk | ab");
        assertThat(ChunkRepository.tsQuery(List.of("dup", "dup"))).isEqualTo("dup");
        assertThat(ChunkRepository.tsQuery(List.of("!!!"))).isEmpty();
    }

    @Test
    void splitsQuestionsIntoUsefulTerms() {
        assertThat(ChunkSearch.terms("Where is send_static_file() defined?"))
                .containsExactly("where", "send_static_file", "defined");
        assertThat(ChunkSearch.terms("a an in")).isEmpty();
    }
}
