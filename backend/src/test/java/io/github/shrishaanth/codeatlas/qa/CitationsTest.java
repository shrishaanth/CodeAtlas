package io.github.shrishaanth.codeatlas.qa;

import io.github.shrishaanth.codeatlas.index.CodeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationsTest {

    private static final List<CodeChunk> GIVEN = List.of(
            new CodeChunk("src/flask/app.py", 76, 120, "class", "Flask", "code", false, false, 0.9),
            new CodeChunk("src/flask/cli.py", 10, 40, "function", "main", "code", false, false, 0.5));

    @Test
    void marksCitationsInsideTheGivenCodeAsVerified() {
        String answer = "The application object is defined in src/flask/app.py:80 and the CLI entry point "
                + "is src/flask/cli.py:10-40.";

        List<Answer.Citation> citations = Citations.verify(answer, GIVEN);

        assertThat(citations).containsExactly(
                new Answer.Citation("src/flask/app.py", 80, null, true),
                new Answer.Citation("src/flask/cli.py", 10, 40, true));
        assertThat(Citations.unverified(citations)).isZero();
    }

    @Test
    void marksInventedFilesAndOutOfRangeLinesAsUnverified() {
        String answer = "See src/flask/wsgi.py:12 and src/flask/app.py:900.";

        List<Answer.Citation> citations = Citations.verify(answer, GIVEN);

        assertThat(citations).extracting(Answer.Citation::path, Answer.Citation::verified)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("src/flask/wsgi.py", false),
                        org.assertj.core.groups.Tuple.tuple("src/flask/app.py", false));
        assertThat(Citations.unverified(citations)).isEqualTo(2);
    }

    @Test
    void rangesCountOnlyWhenBothEndsWereGiven() {
        assertThat(Citations.verify("src/flask/app.py:80-119", GIVEN).get(0).verified()).isTrue();
        assertThat(Citations.verify("src/flask/app.py:80-400", GIVEN).get(0).verified()).isFalse();
    }

    @Test
    void repeatsAndWindowsPathsAreHandled() {
        String answer = "Both src/flask/app.py:80 and src/flask/app.py:80 say so; also src\\flask\\cli.py:12.";

        List<Answer.Citation> citations = Citations.verify(answer, GIVEN);

        assertThat(citations).hasSize(2);
        assertThat(citations.get(1).path()).as("backslashes normalised").isEqualTo("src/flask/cli.py");
    }

    @Test
    void handlesAnswersWithoutCitations() {
        assertThat(Citations.verify("I cannot tell from these excerpts.", GIVEN)).isEmpty();
        assertThat(Citations.verify(null, GIVEN)).isEmpty();
    }
}
