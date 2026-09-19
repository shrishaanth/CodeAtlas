package io.github.shrishaanth.codeatlas.fetch;

import io.github.shrishaanth.codeatlas.testutil.TestRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FileInventoryTest {

    @Test
    void listsTrackedFilesAtHeadWithMeasurements(@TempDir Path dir) throws Exception {
        try (TestRepo repo = TestRepo.create(dir)) {
            repo.commit("a@x.org", "init", Map.of(
                    "pkg/core.py", "import os\n\n\ndef f():\n    return 1\n",
                    "pkg/__init__.py", "",
                    "tests/test_core.py", "from pkg.core import f\n",
                    "README.md", "# hi"));
            repo.commit("a@x.org", "drop readme", java.util.Collections.singletonMap("README.md", null));
            // Untracked file must not appear.
            Files.writeString(dir.resolve("scratch.py"), "x = 1\n", StandardCharsets.UTF_8);

            try (FetchedRepo fetched = new RepoFetcher(dir, 30).fetch(new RepoSource.Local(dir))) {
                Map<String, SourceFile> files = new FileInventory().scan(fetched.repository(), fetched.head())
                        .stream().collect(Collectors.toMap(SourceFile::path, Function.identity()));

                assertThat(files).containsOnlyKeys("pkg/core.py", "pkg/__init__.py", "tests/test_core.py");
                SourceFile core = files.get("pkg/core.py");
                assertThat(core.language()).isEqualTo("python");
                assertThat(core.lines()).isEqualTo(5);
                assertThat(core.nonBlankLines()).isEqualTo(3);
                assertThat(core.test()).isFalse();
                assertThat(files.get("tests/test_core.py").test()).isTrue();
                assertThat(files.get("pkg/__init__.py").lines()).isZero();
                assertThat(fetched.branch()).isEqualTo("main");
            }
        }
    }

    @Test
    void countsLinesWithAndWithoutTrailingNewline() {
        assertThat(FileInventory.countLines("a\nb\n".getBytes())).containsExactly(2, 2);
        assertThat(FileInventory.countLines("a\n\n  \nb".getBytes())).containsExactly(4, 2);
        assertThat(FileInventory.countLines(new byte[0])).containsExactly(0, 0);
        assertThat(FileInventory.countLines("a\r\n\r\n".getBytes())).containsExactly(2, 1);
    }

    @Test
    void detectsBinaryContent() {
        assertThat(FileInventory.looksBinary(new byte[]{'P', 'N', 'G', 0, 1})).isTrue();
        assertThat(FileInventory.looksBinary("plain text".getBytes())).isFalse();
    }

    @Test
    void classifiesLanguagesAndTests() {
        assertThat(FileClassifier.language("src/a.py")).isEqualTo("python");
        assertThat(FileClassifier.language("Makefile")).isNull();
        assertThat(FileClassifier.language(".gitignore")).isNull();
        assertThat(FileClassifier.isTest("tests/unit/helpers.py")).isTrue();
        assertThat(FileClassifier.isTest("src/test_utils.py")).isTrue();
        assertThat(FileClassifier.isTest("src/utils_test.py")).isTrue();
        assertThat(FileClassifier.isTest("conftest.py")).isTrue();
        assertThat(FileClassifier.isTest("src/contest.py")).isFalse();
        assertThat(FileClassifier.isTest("src/testing.py")).isFalse();
    }
}
