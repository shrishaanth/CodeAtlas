package io.github.shrishaanth.codeatlas.parse;

/**
 * The outcome of resolving one imported module.
 *
 * @param imp        the import as written
 * @param module     the module that was looked up, with leading dots for relative imports
 * @param targetPath repo file it resolved to, or null
 * @param external   true if the top-level package is not in the repository (stdlib or third party)
 * @param method     how it was resolved: {@code root}, {@code script-dir}, {@code relative},
 *                   {@code suffix}, or null if unresolved
 */
public record ResolvedImport(PyImport imp, String module, String targetPath, boolean external, String method) {

    public boolean resolved() {
        return targetPath != null;
    }
}
