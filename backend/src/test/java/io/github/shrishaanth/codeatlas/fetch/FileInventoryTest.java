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
        assertThat(FileInventory.countLines("a\nb\n".getBytes())).containsExactly(2, 2, 0);
        assertThat(FileInventory.countLines("a\n\n  \nb".getBytes())).containsExactly(4, 2, 0);
        assertThat(FileInventory.countLines(new byte[0])).containsExactly(0, 0, 0);
        assertThat(FileInventory.countLines("a\r\n\r\n".getBytes())).containsExactly(2, 1, 0);
    }

    @Test
    void measuresIndentationComplexity() {
        String nested = "def f(x):\n    if x:\n        for i in x:\n            print(i)\n\n        \n    return 1\n";
        // Depths 0 + 1 + 2 + 3 + 1 = 7; blank and whitespace-only lines add nothing.
        assertThat(FileInventory.countLines(nested.getBytes())[2]).isEqualTo(7);
        assertThat(FileInventory.countLines("\tif (x) {\n\t\ty();\n".getBytes())[2]).as("tab = 4 spaces").isEqualTo(3);
        assertThat(FileInventory.countLines("  two spaces\n".getBytes())[2]).as("partial indent rounds down").isZero();
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
        assertThat(FileClassifier.isTest("web/src/App.test.tsx")).isTrue();
        assertThat(FileClassifier.isTest("web/src/api.spec.ts")).isTrue();
        assertThat(FileClassifier.isTest("web/src/__tests__/api.ts")).isTrue();
        assertThat(FileClassifier.isTest("pkg/server_test.go")).isTrue();
        assertThat(FileClassifier.isTest("src/main/java/a/UserServiceTest.java")).isTrue();
        assertThat(FileClassifier.isTest("src/main/java/a/Latest.java")).isFalse();
    }

    @Test
    void classifiesGeneratedAndLocalFiles() {
        assertThat(FileClassifier.generatedReason("pkg/__pycache__/x.cpython-311.pyc")).contains("__pycache__");
        assertThat(FileClassifier.generatedReason("pkg.egg-info/PKG-INFO")).contains("egg-info");
        assertThat(FileClassifier.generatedReason("web/dist/app.js")).isEqualTo("build output (dist/)");
        assertThat(FileClassifier.generatedReason("build/scripts/release.py")).as("real source in build/").isNull();
        assertThat(FileClassifier.generatedReason("static/app.min.js")).isEqualTo("minified file");
        assertThat(FileClassifier.generatedReason("static/app.js.map")).isEqualTo("source map");
        assertThat(FileClassifier.generatedReason("assets/.DS_Store")).isNotNull();
        assertThat(FileClassifier.generatedReason("src/app.py")).isNull();

        assertThat(FileClassifier.localFileKind("backend/.env")).isEqualTo("environment file");
        assertThat(FileClassifier.localFileKind(".env.production")).isEqualTo("environment file");
        assertThat(FileClassifier.localFileKind(".env.example")).isNull();
        assertThat(FileClassifier.localFileKind("data/cache.sqlite3")).isEqualTo("database file");
        assertThat(FileClassifier.localFileKind("src/db.py")).isNull();
    }

    @Test
    void detectsGeneratorMarkersInTheFirstLines() {
        assertThat(FileInventory.generatedMarker("# Code generated by protoc. DO NOT EDIT.\nx = 1\n".getBytes()))
                .isEqualTo("marked as generated");
        assertThat(FileInventory.generatedMarker("a\nb\nc\nd\ne\n# @generated\n".getBytes()))
                .as("marker on line 6 is ignored").isNull();
    }
}
