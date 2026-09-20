# Metrics

How every computed number in the report is defined. If the code and this file disagree, it is a bug.
The reading-order weights come from the measured comparison in [docs/evaluation.md](evaluation.md).
Thresholds marked *initial* are still guesses: nothing has measured them.

## Which files are analyzed
- Files tracked by git at the analyzed commit (`HEAD`). Untracked and ignored files are never seen.
- A file is **binary** if its first 8 KB contain a NUL byte. Binary files are counted but not read.
- A file larger than 1 MB is counted but not parsed, and it is listed in `limits.skippedFiles`.
- **Language** comes from the file extension.
- A file is a **test** if any directory in its path is `test`, `tests` or `__tests__`, or its name
  matches a common convention: `test_*.py`, `*_test.py`, `conftest.py`, `*.test.*` and `*.spec.*`
  (JavaScript/TypeScript), `*_test.go`, or `*Test.java` / `*Tests.java`.
- A file is **generated** if its path matches build output or caches (`__pycache__/`, `*.pyc`,
  `node_modules/`, `dist/`, `build/`, `*.egg-info/`, `.pytest_cache/`, `htmlcov/`, `.coverage`,
  `*.min.js`, `*.min.css`, `*.js.map`/`*.css.map`, `.DS_Store`, `.ipynb_checkpoints/`, data files named
  only by a hash such as `cache/3e5a472df573dc695289f1adde2d59e0.json`), or if one of its first 5
  lines contains `@generated`, `DO NOT EDIT` or `Code generated`. Generated files are excluded from
  duplicate detection, hotspots and ownership.

## Overview numbers
- `files`: every tracked file, including binaries and docs.
- `linesOfCode`: total lines (blank lines included) in text files whose language is known and is not
  documentation or data (Markdown, reStructuredText, JSON, YAML, TOML, XML, notebooks are excluded).
- `languages`: files and lines per detected language, largest first.
- `testFileRatio`: Python test files divided by Python non-test files. It can exceed 1 (Flask: 48 test
  files, 35 source files). It is null when there are no non-test Python files.
- `contributors`: people after identity merging, excluding bots (see "People: merging identities").
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

## Reading order (v2)
The goal: a newcomer should read the files that shape the codebase and that are actively worked on.

**Candidates:** Python files that are not tests and have at least 5 non-blank lines (this drops empty
`__init__.py` files, which would otherwise rank high on imports alone).

For each candidate file `f`, over the graph of resolved import edges between non-test files:

| Part | Definition | Normalization to 0..1 |
|---|---|---|
| `churn` | Number of non-merge commits that changed `f`, following renames | `log(1+x) / log(1+max)` |
| `reach` | PageRank with the import edges **reversed**, damping 0.85, 50 iterations. High for files that pull in much of the codebase, directly or indirectly: entry points and orchestrators. | divide by the maximum |
| `fanIn` | Number of distinct non-test files that import `f` | `log(1+x) / log(1+max)` |

```
score = 0.55 * churn + 0.30 * reach + 0.15 * fanIn - 0.20 if the module is private
```
A **private module** is one whose file name starts with a single underscore (`_compat.py`): an
implementation detail by convention. Ties are broken by path, so the order is deterministic.
Logs dampen a few very large values (one file imported by 300 others should not flatten everyone else).

**Where these weights come from.** v1 used `0.5 * centrality + 0.3 * fanIn + 0.2 * churn`, with
centrality being ordinary PageRank (high for files that *everything depends on*). Measured against
independent sources it was no better than sorting by file size, and it put low-level shims such as
click's `_compat.py` at the top. The weights above were searched on half the evaluation repositories
and then measured on the other half, which had not been used for the search. Ordinary centrality
scored zero weight in every good variant and was dropped. Full method and numbers:
[docs/evaluation.md](evaluation.md).

**Known wart:** `docs/conf.py` and similar Python files outside the product code are still ranked.

## Git history
- Commits are walked from `HEAD`, newest first, up to `maxCommits` (default 20,000). If the cap is hit,
  `limits.historyTruncated` is true.
- **Merge commits are skipped** for per-file counts: their changes already appear in the merged commits.
- **Renames are followed.** If a commit renames `old.py` to `new.py`, older commits that touched
  `old.py` count toward the file's current path.
- Authors are identified by lower-cased email, then merged into people (next section).

## People: merging identities
One person often commits under several names or emails (work and personal email, the GitHub web UI).
Identities are merged in this order; each rule links identities, and linked identities become one person.
1. **`.mailmap`** at the analyzed commit, if present (git's own format for mapping identities).
2. **Same email**, ignoring case.
3. **Same full name**, ignoring case, extra spaces and Unicode form (NFC), but only if the name has at least two words.
   Single names like `david` or `admin` are too common to merge on.

A person's display name is the name on their most recent commit. Merging cannot be proven correct;
the report lists every email merged into each person so it can be checked.
The unit being merged is an email, so an email shared by several people (a team or CI account)
counts as one person, even if `.mailmap` names them separately.

**Bots** (name or email containing `[bot]`, or known automation accounts such as dependabot, renovate,
pre-commit-ci and github-actions) are kept in the author list, marked `isBot`, and left out of ownership.

## Ownership
- Every code file at `HEAD` (known language, not documentation or data, not binary) is blamed with
  whitespace-only changes ignored and renames followed. Without ignoring whitespace, re-indenting
  code moves its ownership to whoever re-indented it (see `spikes/README.md`).
- Commits listed in `.git-blame-ignore-revs` at `HEAD` (bulk reformatting) are skipped by blame,
  so their lines go to the previous author.
- At most `maxBlameFiles` files are blamed (default 3,000; the most-committed first). The rest are
  listed in `limits.skippedFiles` with reason `blame cap`.
- A file's **owners** are the people who last changed its current lines, with their share of the
  file's human-authored lines. Lines last changed by bots are excluded.
- **Directory ownership** adds up the blamed lines of every file below the directory.
- **Bus factor** is the smallest number of people who together own at least half of the lines.
  This is a simple per-file and per-directory measure, not the repository-level truck-factor
  algorithms from research papers.
- **Top owner active** means the top owner has a commit within 365 days before the repository's
  last commit (measured against the repository, not today, so old repositories are judged fairly).
- **Flags** (only for files and directories with at least 100 blamed lines):
  `single-owner` if the bus factor is 1 and the top owner holds at least 80% of the lines;
  `orphaned` if, additionally, the top owner is not active.

## Change coupling
Files that are often changed in the same commit, whether or not one imports the other.
- Uses non-merge commits. Commits that touch more than **30 files** are skipped (*initial*): bulk
  reformatting and mass renames would otherwise couple everything.
- Only files with at least **5 commits** are considered (*initial*), to avoid coincidences.
- For a pair A, B: `together` = commits that changed both;
  `degree = together / average(commits of A, commits of B)`, between 0 and 1.
  All counts here (including "commits of A") use only the commits that were not skipped as too large.
  Averaging keeps a file changed in every release (a changelog) from looking coupled to everything.
- A pair is reported if `together >= 5` and `degree >= 0.3` (*initial*), up to 300 pairs, strongest first.
- `hasImportEdge` says whether either file imports the other. Pairs without one are hidden
  dependencies: nothing in the code says they are related, but history does. It is **null (unknown)**
  when either file is not a parsed Python file: CodeAtlas cannot see imports in other languages, so it
  must not claim there are none.
- **Component coupling** does the same for top-level areas: the first directory of a path, or the
  first two if the first is a generic container (`src`, `lib`, `libs`, `packages`, `services`,
  `apps`, `modules`, `components`). Files at the root form `(root)`. Reported if `together >= 3`.

## Hotspots
Code that is both complicated and frequently changed, where bugs and slow reviews tend to concentrate.
- Candidates: non-test code files at `HEAD` with at least one commit.
- **Complexity** is indentation complexity: the sum over non-blank lines of the indentation depth
  (leading spaces divided by 4, a tab counting as 4 spaces). It works for every language and tracks
  nesting, which line counts do not.
- `score = log(1+commits)/log(1+max commits) * log(1+complexity)/log(1+max complexity)`.
  Multiplying means a file must be both busy and complicated to rank high.
- The top 50 are reported with their raw commits, lines and complexity.


## Layers and import cycles
Computed on directories (the v1 components) using resolved imports between non-test Python files.
- Directory A **depends on** directory B if some file in A imports a file in B. Imports made by
  `__init__.py` files are left out: a package's `__init__.py` usually re-exports its submodules while
  those submodules import from the package, which would make nearly every package look circular.
- Directories that depend on each other, directly or through others, form an **import cycle**
  (a strongly connected component). Each cycle becomes one finding.
- **Layer** of a directory: 0 if it depends on no other directory; otherwise one more than the highest
  layer it depends on, with each cycle treated as a single unit. Files at the repository root (config,
  scripts) are each treated as their own unit, because grouping them as one directory invents cycles
  between unrelated files. Low layers are foundations, high
  layers are the code built on them. Directories without Python files have no layer.

## Findings
Concrete, checkable observations. Each lists its evidence (files and line ranges) so it can be
verified by opening the files. A finding is a prompt to look, not a verdict.

**Near-duplicate files** (`duplicate-module`, warn)
- Compares non-test, non-generated code files with at least 10 significant lines. A significant line
  is a line with surrounding whitespace removed that is at least 8 characters long and is not a comment
  (`#`, `//`). Lines that appear in more than 20 files (boilerplate) are ignored.
- `containment = shared significant lines / significant lines of the smaller file`. Reported when
  containment is at least **0.8** and at least **10** lines are shared (*initial* thresholds).
- Also reported, as info: two non-test Python modules with the **same file name where one directory
  contains the other** (`src/hrp.py` and `src/core/hrp.py`) and neither imports the other. These are
  usually two versions of one module, whatever their content. Sibling directories
  (`services/a/app.py`, `services/b/app.py`) are not reported, and neither are pairs where one file
  imports the other (a deliberate layering such as `flask/app.py` building on `flask/sansio/app.py`).

**Repeated functions** (`repeated-logic`, info)
- Python function bodies of at least **6** significant lines, compared after removing whitespace and
  comments. The `def` line is left out, so a copied function that was renamed still matches.
  Identical bodies in two or more files are reported together, one finding per group. Methods are
  included; tests are excluded. A nested function inside an already reported copy is not repeated.
- **Similar file names across areas**: file names reduced to a stem by splitting camelCase and
  separators and dropping role words (service, client, tool, api, helper, util, wrapper, manager,
  handler, adapter), so `tmdbService.js` and `tmdb_client.py` both become `tmdb`. A stem shared by at
  least **3** non-test code files in at least **2** areas is reported. Stems every project has (`app`,
  `config`, `models`, `utils`, `auth` and similar) are ignored. This is a hint from names only.

**No static import found** (`unreferenced-file`, info)
- Non-test Python files that no non-test file imports, excluding likely entry points:
  `__init__.py`, `__main__.py`, `setup.py`, `conftest.py`, `manage.py`, `wsgi.py`, `asgi.py`,
  `docs/conf.py`, files with an `if __name__ == "__main__":` block, files under `scripts/`, `bin/`,
  `examples/`, `docs/`, `migrations/`, and modules named in `pyproject.toml`, `setup.cfg` or `setup.py`.
- Frameworks and plugins often load modules by name at runtime, which static analysis cannot see.
  The finding therefore says "no static import found", not "dead code".
- If only tests import the file, the finding says so ("Only tests import ..."): in an application that
  usually means unused code; in a library it may be public API.

**Area without tests** (`missing-tests`, info)
- A top-level area (as in change coupling) with at least **300** lines of non-test code, no test files
  inside it, and (for Python) no test file anywhere importing one of its files. CSS and HTML are not
  counted (they are not unit-tested), and areas named `docs`, `examples`, `scripts`, `bin` or
  `migrations` are skipped.

**Generated or local files committed** (`generated-file-committed`, warn for build output, caches and
`.env` files; info for files marked as generated, which are often committed on purpose)
- Files matching the generated rules above, plus environment files (`.env`, `.env.*` except
  `.env.example`/`.env.sample`/`.env.template`) and database files (`*.sqlite`, `*.sqlite3`, `*.db`).
  Contents of `.env` files are never read or shown. An `.env` file inside a test directory is info,
  not warn: it is most likely a fixture.

**Import cycle between directories** (`import-cycle`, warn)
- Each import cycle from "Layers and import cycles" with its directories and one example import
  per dependency, so the cycle can be followed in the code.

Findings are capped at 50 per kind, most significant first; the report says how many were left out.

## Question answering (M5)
Answers must be checkable, so the retrieval step is the product and the language model is optional.

**Chunks.** Python files are split by symbol: every top-level function and class becomes one chunk,
with its line range; a class longer than **60 lines** is split into its methods instead, each its own
chunk. That threshold is low on purpose: a model given a 190-line excerpt invents narrower line
numbers inside it, and those are usually wrong (seen with itsdangerous' `Signer` class). Code outside
any symbol (imports, module-level constants) becomes one chunk per file, capped at 200 lines.
Non-Python text files are split into 100-line windows. Test files and generated files are indexed
too, but ranked lower. Every chunk keeps its path and exact line range, so a citation can be checked.

**Search.** Postgres full-text search over chunk text, with the query also matched against symbol
names and paths. The score combines:

| Part | Weight | Why |
|---|---:|---|
| text match (`ts_rank_cd`, normalized) | 1.0 | the main signal |
| exact symbol-name match | +0.5 | "where is `send_static_file`" should find the definition |
| a word of the symbol matches | +0.35 | "signature" should find `get_signature`, "signing" should find `sign`; words are compared after trimming `-ing`, `-ed`, `-es`, `-s` |
| path match | +0.3 | "the cli module" should find `cli.py` |
| reading-order score of the file | +0.2 | prefer central files when several match |
| test or generated file | -0.3 | usually not the answer |

The top 8 chunks are returned, at most 3 from one file, so an answer draws on several places.

The full-text query matches **any** word of the question, not all of them: requiring all of them
finds nothing for "where is fetch_movie defined", because code contains no "where" or "defined".
Ranking decides relevance.

**Answers.** When a language model is configured, it receives only those chunks and must answer from
them, citing the exact location printed above each excerpt. Every citation is then checked and shown
as one of three states:

| State | Meaning |
|---|---|
| `exact` | It names an excerpt that was given, so it points at real code. |
| `inside` | The lines fall within an excerpt but are narrower than it. The excerpt is real; those exact line numbers are the model's own arithmetic and are often wrong. |
| `unsupported` | The lines are outside everything the model was given. Treat the claim as unsupported. |

This checks *where* a citation points, not whether the prose is a fair summary of it; the excerpt is
shown next to the answer so a reader can judge that.

Without a model configured, the search results are shown on their own, which is the same evidence
without the prose. Transient failures (HTTP 429 and 5xx, empty replies) are retried twice before
falling back to search results.
