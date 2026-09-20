package io.github.shrishaanth.codeatlas.eval;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Results of one evaluation run, written to eval/results.json. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvalResult(Instant runAt, String toolVersion, List<RepoResult> repos) {

    /**
     * @param error            set when the repository could not be analyzed; other fields are then null
     * @param docsPercentile   mean percentile of docs-mentioned files, per ranking
     * @param newcomerPercentile mean percentile of files newcomers first touched, per ranking
     * @param docsHitsTop10    docs-mentioned files among each ranking's top 10
     * @param ownership        CODEOWNERS comparison, null when the repository has no CODEOWNERS
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RepoResult(String repo, String error, Integer commits, Integer people, Integer pythonFiles,
                             Integer candidates, Long analysisMillis,
                             Integer docsMentioned, Map<String, Double> docsPercentile,
                             Map<String, Integer> docsHitsTop10,
                             Integer newcomerFiles, Map<String, Double> newcomerPercentile,
                             Ownership ownership, Integer findings) {
    }

    /**
     * @param directoriesWithOwners directories covered by both CODEOWNERS and blame
     * @param linkedDirectories     of those, the ones where at least one listed handle matches a person in git
     * @param topOwnerMatches       linked directories whose top owner is one of the listed handles
     * @param topThreeMatches       linked directories where any of the top three owners matches
     */
    public record Ownership(int directoriesWithOwners, int linkedDirectories, int topOwnerMatches,
                            int topThreeMatches) {
    }
}
