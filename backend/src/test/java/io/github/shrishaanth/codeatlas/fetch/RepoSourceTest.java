package io.github.shrishaanth.codeatlas.fetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoSourceTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/pallets/flask",
            "https://github.com/pallets/flask/",
            "https://github.com/pallets/flask.git",
            "  https://github.com/pallets/flask  "})
    void acceptsGitHubUrls(String input) {
        RepoSource source = RepoSource.parse(input, false);

        assertThat(source).isEqualTo(new RepoSource.GitHub("pallets", "flask"));
        assertThat(((RepoSource.GitHub) source).cloneUrl()).isEqualTo("https://github.com/pallets/flask.git");
        assertThat(source.name()).isEqualTo("flask");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://github.com/pallets/flask",
            "https://gitlab.com/pallets/flask",
            "https://github.com/pallets",
            "https://github.com/pallets/flask/tree/main",
            "https://github.com/pallets/..",
            "git@github.com:pallets/flask.git",
            "file:///etc",
            "https://github.com.evil.com/a/b"})
    void rejectsOtherUrls(String input) {
        assertThatThrownBy(() -> RepoSource.parse(input, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void localPathsOnlyWhenAllowed(@TempDir Path dir) {
        assertThatThrownBy(() -> RepoSource.parse(dir.toString(), false))
                .hasMessageContaining("Local paths are disabled");
        assertThat(RepoSource.parse(dir.toString(), true)).isEqualTo(new RepoSource.Local(dir));
    }
}
