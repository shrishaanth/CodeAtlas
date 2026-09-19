package io.github.shrishaanth.codeatlas.report;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The analysis result: the one contract between the engine and everything that displays it.
 * Field-by-field documentation lives in docs/report-schema.md; keep the two in sync.
 * Sections that later milestones add (coupling, hotspots, findings, ownership) are null until then.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Report(
        String schemaVersion,
        RepoInfo repo,
        Limits limits,
        Overview overview,
        List<FileEntry> files,
        List<Component> components,
        List<Edge> edges,
        List<ReadingItem> readingOrder,
        People people,
        Coupling coupling,
        List<Hotspot> hotspots) {

    public static final String SCHEMA_VERSION = "0.1";

    public record RepoInfo(String source, String name, String commit, String branch, Instant analyzedAt,
                           String toolVersion) {
    }

    /** @param ignoredRevisions commits from .git-blame-ignore-revs that blame skipped */
    public record Limits(int maxCommits, boolean historyTruncated, List<String> ignoredRevisions,
                         List<SkippedFile> skippedFiles, List<ParseError> parseErrors) {
    }

    public record SkippedFile(String path, String reason) {
    }

    public record ParseError(String path, int startLine) {
    }

    public record Overview(int commits, int contributors, Instant firstCommitAt, Instant lastCommitAt, int files,
                           int linesOfCode, List<LanguageStat> languages, Double testFileRatio) {
    }

    public record LanguageStat(String language, int files, int lines) {
    }

    /** {@code language} is always written (null if unknown); the optional sections are omitted when absent. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FileEntry(String path, String language, int lines, String componentId, boolean isTest,
                            @JsonInclude(JsonInclude.Include.NON_NULL) List<Symbol> symbols,
                            @JsonInclude(JsonInclude.Include.NON_NULL) List<Import> imports,
                            @JsonInclude(JsonInclude.Include.NON_NULL) FileGit git) {
    }

    public record Symbol(String kind, String name, int startLine, int endLine, String parent) {
    }

    public record Import(String text, int line, String module, String resolvedPath, boolean external) {
    }

    public record FileGit(int commits, int authorCount, Instant firstChangedAt, Instant lastChangedAt) {
    }

    public record Component(String id, String path, int files, int lines) {
    }

    public record Edge(String source, String target, String kind, int weight) {
    }

    public record ReadingItem(int rank, String path, double score, Map<String, Double> parts, List<String> reasons) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record People(List<Author> authors, List<Ownership> fileOwnership, List<Ownership> directoryOwnership) {
    }

    /** A person after identity merging; {@code emails} lists every identity merged in. */
    public record Author(String id, String name, List<String> emails, int commits, boolean isBot,
                         Instant lastCommitAt) {
    }

    /**
     * @param totalLines   human-authored lines (bot lines excluded)
     * @param owners       top owners, most lines first (at most 5)
     * @param otherLines   lines owned by people not listed
     * @param ownerCount   number of people owning at least one line
     * @param flags        {@code single-owner}, {@code orphaned}
     */
    public record Ownership(String path, int totalLines, List<Owner> owners, int otherLines, int ownerCount,
                            int busFactor, boolean topOwnerActive, List<String> flags) {
    }

    public record Owner(String authorId, int lines, double share) {
    }

    /** @param skippedLargeCommits commits left out for touching too many files */
    public record Coupling(List<FilePair> files, List<AreaPair> components, int skippedLargeCommits) {
    }

    /**
     * @param degree        together / average(aCommits, bCommits)
     * @param hasImportEdge whether either file imports the other; null (unknown) unless both are parsed files
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FilePair(String a, String b, int together, int aCommits, int bCommits, double degree,
                           Boolean hasImportEdge) {
    }

    public record AreaPair(String a, String b, int together, int aCommits, int bCommits, double degree) {
    }

    /** @param complexity indentation complexity (docs/metrics.md, "Hotspots") */
    public record Hotspot(int rank, String path, double score, int commits, int lines, int complexity) {
    }
}
