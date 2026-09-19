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
        People people) {

    public static final String SCHEMA_VERSION = "0.1";

    public record RepoInfo(String source, String name, String commit, String branch, Instant analyzedAt,
                           String toolVersion) {
    }

    public record Limits(int maxCommits, boolean historyTruncated, List<SkippedFile> skippedFiles,
                         List<ParseError> parseErrors) {
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileEntry(String path, String language, int lines, String componentId, boolean isTest,
                            List<Symbol> symbols, List<Import> imports, FileGit git) {
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

    public record People(List<Author> authors) {
    }

    public record Author(String id, String name, List<String> emails, int commits) {
    }
}
