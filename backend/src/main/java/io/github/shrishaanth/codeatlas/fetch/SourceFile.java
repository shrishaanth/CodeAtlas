package io.github.shrishaanth.codeatlas.fetch;

import org.eclipse.jgit.lib.ObjectId;

/**
 * A file tracked at the analyzed commit.
 *
 * @param path          repo-relative path with {@code /} separators
 * @param blobId        git object holding the content, used to read it later
 * @param language      from the extension, or null if unknown
 * @param sizeBytes     size of the content
 * @param binary        true if the content looks binary (not read further)
 * @param lines         total lines, 0 for binary files
 * @param nonBlankLines lines with at least one non-whitespace character
 * @param test          whether the file is test code (see docs/metrics.md)
 * @param skipReason    why the content is not parsed, or null if it can be
 */
public record SourceFile(String path, ObjectId blobId, String language, long sizeBytes, boolean binary,
                         int lines, int nonBlankLines, boolean test, String skipReason) {

    public boolean isPython() {
        return "python".equals(language);
    }

    public boolean parseable() {
        return skipReason == null && !binary;
    }
}
