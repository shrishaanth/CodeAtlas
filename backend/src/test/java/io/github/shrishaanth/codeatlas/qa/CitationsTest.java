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
    void citingAnExcerptExactlyIsTheTrustworthyCase() {
        String answer = "The CLI entry point is src/flask/cli.py:10-40.";

        List<Answer.Citation> citations = Citations.verify(answer, GIVEN);

        assertThat(citations).containsExactly(
                new Answer.Citation("src/flask/cli.py", 10, 40, Answer.Citation.EXACT));
        assertThat(Citations.unsupported(citations)).isZero();
    }

    @Test
    void narrowerLinesInsideAnExcerptAreMarkedAsSuch() {
        // Seen with a real model: it invented src/itsdangerous/signer.py:175-180 inside a 76-266 excerpt,
        // and those lines held something else entirely.
        String answer = "It is defined in src/flask/app.py:95-99.";

        List<Answer.Citation> citations = Citations.verify(answer, GIVEN);

        assertThat(citations).extracting(Answer.Citation::status).containsExactly(Answer.Citation.INSIDE);
        assertThat(Citations.unsupported(citations)).isZero();
    }

    @Test
    void invisibleFilesAndOutOfRangeLinesAreUnsupported() {
        String answer = "See src/flask/wsgi.py:12 and src/flask/app.py:900.";

        List<Answer.Citation> citations = Citations.verify(answer, GIVEN);

        assertThat(citations).extracting(Answer.Citation::path, Answer.Citation::status).containsExactly(
                org.assertj.core.groups.Tuple.tuple("src/flask/wsgi.py", Answer.Citation.UNSUPPORTED),
                org.assertj.core.groups.Tuple.tuple("src/flask/app.py", Answer.Citation.UNSUPPORTED));
        assertThat(Citations.unsupported(citations)).isEqualTo(2);
    }

    @Test
    void aRangeNeedsBothEndsInsideTheSameExcerptSet() {
        assertThat(Citations.verify("src/flask/app.py:80-119", GIVEN).get(0).status())
                .isEqualTo(Answer.Citation.INSIDE);
        assertThat(Citations.verify("src/flask/app.py:80-400", GIVEN).get(0).status())
                .isEqualTo(Answer.Citation.UNSUPPORTED);
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
