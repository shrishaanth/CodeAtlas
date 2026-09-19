package io.github.shrishaanth.codeatlas.api;

import io.github.shrishaanth.codeatlas.jobs.AnalysisService;
import io.github.shrishaanth.codeatlas.jobs.AnalysisStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/analyses")
public class AnalysisController {

    public record SubmitRequest(@NotBlank @Size(max = 500) String repo) {
    }

    private final AnalysisService service;

    public AnalysisController(AnalysisService service) {
        this.service = service;
    }

    /** Queues an analysis. Returns 202 with the status to poll. */
    @PostMapping
    public ResponseEntity<AnalysisStatus> submit(@Valid @RequestBody SubmitRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.submit(request.repo()));
    }

    @GetMapping
    public List<AnalysisStatus> recent(@RequestParam(defaultValue = "20") int limit) {
        return service.recent(limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AnalysisStatus> status(@PathVariable UUID id) {
        return ResponseEntity.of(service.status(id));
    }

    /** The report exactly as stored; 404 until the analysis is done. */
    @GetMapping(value = "/{id}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> report(@PathVariable UUID id) {
        return ResponseEntity.of(service.reportJson(id));
    }
}
