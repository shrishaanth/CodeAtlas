package io.github.shrishaanth.codeatlas.qa;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.shrishaanth.codeatlas.index.CodeChunk;

import java.util.List;

/**
 * What a question produced: the places found in the code, and, when a model is configured, prose
 * built only from those places.
 *
 * @param answer    the model's text, or null when no model is configured or it could not be reached
 * @param model     which model wrote it, or null
 * @param note      why there is no answer text, or null
 * @param citations every {@code path:line} the answer mentioned, each marked verified or not
 * @param sources   the chunks the model was given, best first
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Answer(String question, String answer, String model, String note, List<Citation> citations,
                     List<Source> sources) {

    /** @param status {@code exact}, {@code inside} or {@code unsupported}; see {@link Citations#status} */
    public record Citation(String path, int startLine, Integer endLine, String status) {

        public static final String EXACT = "exact";
        public static final String INSIDE = "inside";
        public static final String UNSUPPORTED = "unsupported";
    }

    public record Source(String path, int startLine, int endLine, String kind, String symbol, String text,
                         double score) {

        public static Source of(CodeChunk c, double score) {
            return new Source(c.path(), c.startLine(), c.endLine(), c.kind(), c.symbol(), c.text(), score);
        }
    }
}
