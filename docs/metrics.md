# Metrics

How every computed number in the report is defined. If the code and this file disagree, it is a bug.
Weights marked *initial* are guesses to be calibrated in M4 against baselines, not tuned values.

## Which files are analyzed
- Files tracked by git at the analyzed commit (`HEAD`). Untracked and ignored files are never seen.
- A file is **binary** if its first 8 KB contain a NUL byte. Binary files are counted but not read.
- A file larger than 1 MB is counted but not parsed, and it is listed in `limits.skippedFiles`.
- **Language** comes from the file extension.
- A file is a **test** if any directory in its path is `test` or `tests`, or its name matches
  `test_*.py`, `*_test.py` or `conftest.py`.

## Overview numbers
- `files`: every tracked file, including binaries and docs.
- `linesOfCode`: total lines (blank lines included) in text files whose language is known and is not
  documentation or data (Markdown, reStructuredText, JSON, YAML, TOML, XML, notebooks are excluded).
- `languages`: files and lines per detected language, largest first.
- `testFileRatio`: Python test files divided by Python non-test files. It can exceed 1 (Flask: 48 test
  files, 35 source files). It is null when there are no non-test Python files.
- `contributors`: distinct author emails (before alias merging, which arrives in M2).
- `components`: in v1, simply directories; `.` is the repository root.

## Python import resolution
An import becomes an edge only if it resolves to a file in the repository. Everything else is `external`.

1. **Source roots.** The repo root, plus every directory that contains a package (a directory with
   `__init__.py`) but is not itself a package. This covers both `src/` layouts and repos with
   several services in subdirectories.
   **Script directory:** if the importing file is not inside a package, its own directory is also a
   root. Python puts a script's directory on the path, and small repos rely on this
   (`backend/main.py` doing `import tmdb` for `backend/tmdb.py`). Files inside packages do not get
   this rule, because Python 3 has no implicit relative imports.
2. **Module lookup.** Module `a.b` under root `R` is `R/a/b/__init__.py` or `R/a/b.py` (`.pyi` stubs
   are tried after `.py`). As in Python, a package beats a same-named module.
   If several roots contain the module, the file sharing the most leading directories with the
   importing file wins (in a monorepo, `services/a/app/main.py` importing `app.db` gets `services/a/app/db.py`).
3. **`import a.b.c`** resolves to the most specific existing module among `a.b.c`, `a.b`, `a`.
4. **`from a.b import c`** resolves to module `a.b.c` if it exists (a submodule), otherwise to `a.b`.
5. **Relative imports** (`from ..x import y`) are resolved by path from the importing file's directory,
   one directory up per extra dot. Going above the repository root leaves the import unresolved.
6. **Fallback.** If nothing matched, a module name with two or more parts that matches the end of
   exactly one file's dotted path is used (for namespace packages without `__init__.py`).
   Single names like `utils` are never matched this way: too ambiguous.
7. **External** means the top-level name (`numpy` in `numpy.linalg`) is not found in the repo at all.
   An import whose top-level package *is* in the repo but whose submodule is not is **unresolved**, not external.

Known gaps: imports built at runtime (`importlib.import_module(name)`), `sys.path` manipulation,
and conditional imports are all treated like normal imports if they are literal, and missed if not.

## Reading order (v1)
The goal: a newcomer should read the files that the rest of the code depends on and that are
actively worked on. v1 ranks files by importance. It does not yet order them by dependency
(read foundations before the files that use them); that is an M4 experiment.

**Candidates:** Python files that are not tests and have at least 5 non-blank lines (*initial* threshold;
it drops empty `__init__.py` files, which would otherwise rank high on imports alone, but also tiny
real modules, so M4 should check what it costs).

For each candidate file `f`, over the graph of resolved import edges between non-test files:

| Part | Definition | Normalization to 0..1 |
|---|---|---|
| `centrality` | PageRank with edges pointing from importer to imported file, damping 0.85, 50 iterations. A file is central if central files import it. | divide by the maximum |
| `fanIn` | Number of distinct non-test files that import `f` | `log(1+x) / log(1+max)` |
| `churn` | Number of non-merge commits that changed `f`, following renames | `log(1+x) / log(1+max)` |

```
score = 0.5 * centrality + 0.3 * fanIn + 0.2 * churn      (initial weights)
```
Ties are broken by path, so the order is deterministic. Logs dampen a few very large values
(one file imported by 300 others should not flatten everyone else to zero).

**Baselines** for M4: rank by file size, by raw fan-in, and by raw commit count. If the score does
not beat these on the evaluation set, the report must say so.

## Git history
- Commits are walked from `HEAD`, newest first, up to `maxCommits` (default 20,000). If the cap is hit,
  `limits.historyTruncated` is true.
- **Merge commits are skipped** for per-file counts: their changes already appear in the merged commits.
- **Renames are followed.** If a commit renames `old.py` to `new.py`, older commits that touched
  `old.py` count toward the file's current path.
- Authors are identified by lower-cased email. Alias merging arrives in M2.
