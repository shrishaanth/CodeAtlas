package io.github.shrishaanth.codeatlas.jobs;

import java.time.Instant;
import java.util.UUID;

/** The state of one analysis request, without the report itself. */
public record AnalysisStatus(UUID id, String source, State status, String stage, int percent, String detail,
                             String error, String repoName, String commit, Instant createdAt, Instant startedAt,
                             Instant finishedAt) {

    public enum State {
        QUEUED, RUNNING, DONE, FAILED;

        public boolean active() {
            return this == QUEUED || this == RUNNING;
        }
    }
}
