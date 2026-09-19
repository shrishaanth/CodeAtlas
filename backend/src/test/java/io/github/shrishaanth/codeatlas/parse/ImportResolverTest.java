package io.github.shrishaanth.codeatlas.parse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportResolverTest {

    private static PyImport imp(String module) {
        return new PyImport(1, "import " + module, false, 0, module, List.of());
    }

    private static PyImport from(int level, String module, String... names) {
        return new PyImport(1, "from ...", true, level, module, List.of(names));
    }

    private static List<String> targets(ImportResolver r, String importer, PyImport i) {
        return r.resolve(importer, i).stream().map(ResolvedImport::targetPath).toList();
    }

    @Test
    void findsSourceRootsForSrcLayoutAndServiceDirectories() {
        ImportResolver r = new ImportResolver(List.of(
                "src/flask/__init__.py", "src/flask/app.py",
                "services/api/app/__init__.py", "services/api/app/main.py",
                "setup.py"));

        assertThat(r.sourceRoots()).containsExactly("", "services/api", "src");
    }

    @Test
    void resolvesSrcLayoutAbsoluteImports() {
        ImportResolver r = new ImportResolver(List.of(
                "src/flask/__init__.py", "src/flask/app.py", "src/flask/json/__init__.py",
                "src/flask/json/tag.py", "tests/test_app.py"));

        assertThat(targets(r, "tests/test_app.py", imp("flask"))).containsExactly("src/flask/__init__.py");
        assertThat(targets(r, "tests/test_app.py", imp("flask.json.tag"))).containsExactly("src/flask/json/tag.py");
        // Parent package fallback: importing a.b.c also imports a.b and a.
        assertThat(targets(r, "tests/test_app.py", imp("flask.json.missing"))).containsExactly("src/flask/json/__init__.py");
    }

    @Test
    void fromImportPrefersSubmoduleThenFallsBackToModule() {
        ImportResolver r = new ImportResolver(List.of(
                "pkg/__init__.py", "pkg/json/__init__.py", "pkg/json/tag.py", "pkg/app.py", "main.py"));

        assertThat(targets(r, "main.py", from(0, "pkg.json", "tag", "dumps")))
                .containsExactly("pkg/json/tag.py", "pkg/json/__init__.py");
        assertThat(targets(r, "main.py", from(0, "pkg", "app", "app")))
                .containsExactly("pkg/app.py");
        assertThat(targets(r, "main.py", from(0, "pkg.json", "*")))
                .containsExactly("pkg/json/__init__.py");
    }

    @Test
    void resolvesRelativeImports() {
        ImportResolver r = new ImportResolver(List.of(
                "pkg/__init__.py", "pkg/utils.py", "pkg/core/__init__.py", "pkg/core/hrp.py", "pkg/core/run.py"));

        assertThat(targets(r, "pkg/core/run.py", from(1, "", "hrp"))).containsExactly("pkg/core/hrp.py");
        assertThat(targets(r, "pkg/core/run.py", from(1, "hrp", "allocate"))).containsExactly("pkg/core/hrp.py");
        assertThat(targets(r, "pkg/core/run.py", from(2, "", "utils"))).containsExactly("pkg/utils.py");
        assertThat(targets(r, "pkg/core/run.py", from(2, "utils", "helper"))).containsExactly("pkg/utils.py");
        // A name that is not a submodule comes from the package itself.
        assertThat(targets(r, "pkg/core/run.py", from(1, "", "hrp", "VERSION")))
                .containsExactly("pkg/core/hrp.py", "pkg/core/__init__.py");
        // Inside __init__.py, "." is the package itself.
        assertThat(targets(r, "pkg/__init__.py", from(1, "core", "hrp"))).containsExactly("pkg/core/hrp.py");
    }

    @Test
    void relativeImportAboveTheRepoIsUnresolvedButNotExternal() {
        ImportResolver r = new ImportResolver(List.of("a.py"));

        ResolvedImport res = r.resolve("a.py", from(3, "x", "y")).get(0);

        assertThat(res.resolved()).isFalse();
        assertThat(res.external()).isFalse();
    }

    @Test
    void marksStdlibAndThirdPartyAsExternal() {
        ImportResolver r = new ImportResolver(List.of("pkg/__init__.py", "pkg/app.py"));

        ResolvedImport os = r.resolve("pkg/app.py", imp("os.path")).get(0);
        ResolvedImport np = r.resolve("pkg/app.py", from(0, "numpy.linalg", "norm")).get(0);

        assertThat(os.external()).isTrue();
        assertThat(os.resolved()).isFalse();
        assertThat(np.external()).isTrue();
    }

    @Test
    void scriptsCanImportSiblingModules() {
        // Common in small repos: services with plain scripts and no packages.
        ImportResolver r = new ImportResolver(List.of(
                "backend/main.py", "backend/tmdb.py", "ml-service/serve.py", "ml-service/tmdb.py"));

        ResolvedImport backend = r.resolve("backend/main.py", imp("tmdb")).get(0);
        ResolvedImport ml = r.resolve("ml-service/serve.py", imp("tmdb")).get(0);

        assertThat(backend.targetPath()).isEqualTo("backend/tmdb.py");
        assertThat(backend.method()).isEqualTo("script-dir");
        assertThat(ml.targetPath()).isEqualTo("ml-service/tmdb.py");
    }

    @Test
    void modulesInsidePackagesDoNotGetScriptDirImports() {
        // Python 3 has no implicit relative imports: inside a package, "import utils" is absolute.
        ImportResolver r = new ImportResolver(List.of("pkg/__init__.py", "pkg/app.py", "pkg/utils.py"));

        ResolvedImport res = r.resolve("pkg/app.py", imp("utils")).get(0);

        assertThat(res.resolved()).isFalse();
        assertThat(res.external()).isTrue();
    }

    @Test
    void prefersTheClosestFileWhenSeveralRootsMatch() {
        ImportResolver r = new ImportResolver(List.of(
                "services/a/app/__init__.py", "services/a/app/db.py", "services/a/app/main.py",
                "services/b/app/__init__.py", "services/b/app/db.py", "services/b/app/main.py"));

        assertThat(targets(r, "services/a/app/main.py", imp("app.db"))).containsExactly("services/a/app/db.py");
        assertThat(targets(r, "services/b/app/main.py", imp("app.db"))).containsExactly("services/b/app/db.py");
    }

    @Test
    void suffixFallbackOnlyForUniqueMultiPartNames() {
        // Namespace package (no __init__.py) under a directory that is not a source root.
        ImportResolver r = new ImportResolver(List.of(
                "code/lib/ns/tools/strings.py", "code/app.py", "other/utils.py", "more/utils.py"));

        ResolvedImport unique = r.resolve("code/app.py", imp("ns.tools.strings")).get(0);
        ResolvedImport single = r.resolve("code/app.py", imp("utils")).get(0);

        assertThat(unique.targetPath()).isEqualTo("code/lib/ns/tools/strings.py");
        assertThat(unique.method()).isEqualTo("suffix");
        assertThat(single.resolved()).isFalse();
    }
}
