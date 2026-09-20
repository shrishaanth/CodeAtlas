package io.github.shrishaanth.codeatlas.index;

import io.github.shrishaanth.codeatlas.fetch.SourceFile;
import io.github.shrishaanth.codeatlas.parse.ParsedPythonFile;
import io.github.shrishaanth.codeatlas.parse.PythonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    private final PythonParser parser = new PythonParser();

    @AfterEach
    void close() {
        parser.close();
    }

    private static SourceFile file(String path, boolean test) {
        return new SourceFile(path, null, path.endsWith(".py") ? "python" : "text", 100, false, 10, 10, 0,
                test, null, null);
    }

    private static List<CodeChunk> chunk(String path, String text, boolean parse) {
        ParsedPythonFile parsed = parse ? new PythonParser().parse(path, text) : null;
        return Chunker.chunk(file(path, false), text, parsed, 0.5);
    }

    @Test
    void splitsPythonBySymbolAndKeepsLineRanges() {
        String src = """
                import os

                CONSTANT = 1


                def first(a):
                    return a + 1


                def second(b):
                    return b * 2
                """;

        List<CodeChunk> chunks = chunk("m.py", src, true);

        assertThat(chunks).extracting(CodeChunk::kind, CodeChunk::symbol, CodeChunk::startLine, CodeChunk::endLine)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("module", null, 1, 5),
                        org.assertj.core.groups.Tuple.tuple("function", "first", 6, 7),
                        org.assertj.core.groups.Tuple.tuple("function", "second", 10, 11));
        assertThat(chunks.get(1).text()).isEqualTo("def first(a):\n    return a + 1\n");
        assertThat(chunks.get(1).location()).isEqualTo("m.py:6-7");
    }

    @Test
    void breaksALongClassIntoItsMethods() {
        StringBuilder src = new StringBuilder("class Big:\n    \"\"\"Doc.\"\"\"\n\n");
        for (int i = 0; i < 12; i++) {
            src.append("    def method_").append(i).append("(self):\n");
            for (int j = 0; j < 18; j++) src.append("        x = ").append(j).append("\n");
            src.append("\n");
        }

        List<CodeChunk> chunks = chunk("big.py", src.toString(), true);

        assertThat(chunks).hasSize(13); // the class header plus 12 methods
        assertThat(chunks.get(0).kind()).isEqualTo("class");
        assertThat(chunks).filteredOn(c -> c.kind().equals("method")).extracting(CodeChunk::symbol)
                .contains("Big.method_0", "Big.method_11");
        assertThat(chunks).allSatisfy(c -> assertThat(c.lines()).isLessThanOrEqualTo(Chunker.MAX_CHUNK_LINES));
    }

    @Test
    void keepsAShortClassWhole() {
        String src = "class Small:\n    def a(self):\n        return 1\n\n    def b(self):\n        return 2\n";

        List<CodeChunk> chunks = chunk("s.py", src, true);

        assertThat(chunks).extracting(CodeChunk::kind, CodeChunk::symbol)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("class", "Small"));
    }

    @Test
    void splitsNonPythonFilesIntoWindows() {
        String src = "line\n".repeat(250);

        List<CodeChunk> chunks = chunk("README.md", src, false);

        assertThat(chunks).extracting(CodeChunk::startLine, CodeChunk::endLine)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 100),
                        org.assertj.core.groups.Tuple.tuple(101, 200),
                        org.assertj.core.groups.Tuple.tuple(201, 250));
        assertThat(chunks).allSatisfy(c -> assertThat(c.kind()).isEqualTo("text"));
    }

    @Test
    void skipsBlankStretchesAndEmptyFiles() {
        assertThat(chunk("empty.py", "", true)).isEmpty();
        assertThat(chunk("blank.py", "\n\n\n\n", true)).isEmpty();
    }

    @Test
    void everyChunkCanBeCheckedAgainstACitation() {
        List<CodeChunk> chunks = chunk("m.py", "def f():\n    return 1\n", true);

        CodeChunk c = chunks.get(0);
        assertThat(c.contains("m.py", 1)).isTrue();
        assertThat(c.contains("m.py", 2)).isTrue();
        assertThat(c.contains("m.py", 3)).isFalse();
        assertThat(c.contains("other.py", 1)).isFalse();
    }
}
