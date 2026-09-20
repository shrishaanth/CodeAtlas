package io.github.shrishaanth.codeatlas.api;

import io.github.shrishaanth.codeatlas.index.ChunkSearch;
import io.github.shrishaanth.codeatlas.jobs.AnalysisService;
import io.github.shrishaanth.codeatlas.qa.Answer;
import io.github.shrishaanth.codeatlas.qa.QaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class QaController {

    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    /** @param model null when no language model is configured; search still works */
    public record QaStatus(boolean modelConfigured, String model) {
    }

    private final QaService qa;
    private final AnalysisService analyses;

    public QaController(QaService qa, AnalysisService analyses) {
        this.qa = qa;
        this.analyses = analyses;
    }

    @GetMapping("/qa/status")
    public QaStatus status() {
        return new QaStatus(qa.modelConfigured(), qa.modelName());
    }

    /** The code that matches a query, with no model involved. */
    @GetMapping("/analyses/{id}/search")
    public ResponseEntity<List<Answer.Source>> search(@PathVariable UUID id, @RequestParam("q") String query,
                                                      @RequestParam(defaultValue = "8") int limit) {
        if (analyses.status(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(qa.search(id, query, limit).stream()
                .map(r -> Answer.Source.of(r.chunk(), r.score())).toList());
    }

    @PostMapping("/analyses/{id}/ask")
    public ResponseEntity<Answer> ask(@PathVariable UUID id, @Valid @RequestBody AskRequest request,
                                      HttpServletRequest http) {
        if (analyses.status(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(qa.ask(id, request.question(), callerOf(http)));
    }

    /** The caller for rate limiting: the proxy's forwarded address when present, else the socket. */
    private static String callerOf(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].strip();
        return http.getRemoteAddr();
    }

    /** Kept so the UI can show how many places were searched. */
    public static int defaultLimit() {
        return ChunkSearch.DEFAULT_LIMIT;
    }
}
