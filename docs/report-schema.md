# Report schema (draft v0.1)

The report is the one contract in CodeAtlas. The analysis engine writes it, and everything else
(the web UI, the JSON export, the static demo and later the CLI or MCP server) only reads it.
The static demo is literally a set of these files, so the UI must never need anything that is not in here.

Status: **draft**. Fields are expected to change during M1-M3. `schemaVersion` changes when they do.
Example values below are illustrative only, not real measurements.

**Produced today (M3):** every section below.
Absent sections are omitted from the JSON rather than written as null.

## Conventions
- **Paths** are repo-relative, use `/`, and are the identifier for a file everywhere in the report.
- **Lines** are 1-based and inclusive (`startLine: 3, endLine: 5` is three lines).
- **Timestamps** are ISO-8601 UTC strings.
- **Authors** are referenced by `authorId`, defined once in `people.authors`.
- **Every score carries its parts.** A ranked item includes the inputs that produced the score,
  so the UI can show *why* and a reader can check it.
- **Every finding carries evidence**: the files, line ranges or commits that support it.
- Anything skipped or truncated is listed under `limits`, never dropped silently.

## Top level
```jsonc
{
  "schemaVersion": "0.1",
  "repo":        { ... },   // what was analyzed
  "limits":      { ... },   // what was skipped or capped
  "overview":    { ... },
  "files":       [ ... ],   // one entry per analyzed file
  "components":  [ ... ],   // groups of files (top-level packages / directories)
  "edges":       [ ... ],   // import and co-change edges between files
  "readingOrder":[ ... ],
  "people":      { ... },   // people and ownership
  "coupling":    { ... },
  "hotspots":    [ ... ],
  "findings":    [ ... ]
}
```

## `repo`
```jsonc
{
  "source": "https://github.com/pallets/flask",  // URL or "local"
  "name": "flask",
  "commit": "a1b2c3d...",       // HEAD that was analyzed
  "branch": "main",
  "analyzedAt": "2026-09-19T16:00:00Z",
  "toolVersion": "0.1.0"
}
```

## `limits`
```jsonc
{
  "maxCommits": 20000,
  "historyTruncated": false,
  "ignoredRevisions": ["9f1e..."],          // from .git-blame-ignore-revs, skipped by blame
  "skippedFiles": [ { "path": "big.py", "reason": "larger than 1 MB" },
                    { "path": "old/x.js", "reason": "blame cap" } ],
  "parseErrors":  [ { "path": "tests/bad.py", "startLine": 3 } ],
  "findingsOmitted": { "unreferenced-file": 12 }   // over the cap of 50 per kind
}
```

## `overview`
```jsonc
{
  "commits": 5557,
  "contributors": 640,             // people after identity merging, bots excluded
  "firstCommitAt": "2010-04-06T...",
  "lastCommitAt": "2026-09-10T...",
  "files": 312,
  "linesOfCode": 18345,
  "languages": [ { "language": "python", "files": 83, "lines": 18345 } ],
  "testFileRatio": 0.41            // test files / source files
}
```

## `files[]`
```jsonc
{
  "path": "src/flask/app.py",
  "language": "python",            // null if not parsed
  "lines": 1536,
  "componentId": "src/flask",
  "isTest": false,
  "isGenerated": false,            // build output, cache or generator marker (docs/metrics.md)
  "symbols": [
    { "kind": "class", "name": "Flask", "startLine": 76, "endLine": 1530, "parent": null },
    { "kind": "function", "name": "run", "startLine": 540, "endLine": 660, "parent": "Flask" }
  ],
  "imports": [
    { "text": "from .globals import request", "line": 30,
      "module": ".globals", "resolvedPath": "src/flask/globals.py", "external": false }
  ],
  "git": {
    "commits": 812,                 // non-merge commits, following renames
    "authorCount": 190,             // distinct people (after identity merging), bots excluded
    "firstChangedAt": "...", "lastChangedAt": "..."
  }
}
```
`resolvedPath` is null for imports that point outside the repo (`external: true`) or could not be resolved.
One import statement can produce several entries (`from pkg import a, b` where both are submodules).
`symbols` and `imports` are present only for parsed (Python) files; `git` only for files some commit touched.

## `components[]`
```jsonc
{ "id": "src/flask", "path": "src/flask", "files": 24, "lines": 9800, "layer": 1 }
```
In v1 a component is a directory (`.` for the root). `layer` comes from import direction: 0 means the
directory imports no other directory; it is null for directories without Python files and for `.`
(root files are layered individually). Definitions: `docs/metrics.md`, "Layers and import cycles".

## `edges[]`
```jsonc
{ "source": "src/flask/app.py", "target": "src/flask/globals.py", "kind": "import", "weight": 1 }
{ "source": "src/flask/app.py", "target": "tests/test_basic.py",  "kind": "cochange", "weight": 143 }
```
For `import`, source imports target. For `cochange`, the pair is unordered and `weight` is the
number of commits that changed both; there is one `cochange` edge per pair in `coupling.files`.

## `readingOrder[]`
```jsonc
{
  "rank": 1,
  "path": "src/flask/app.py",
  "score": 0.87,
  "parts": { "churn": 0.75, "reach": 0.81, "fanIn": 0.92 },
  "reasons": ["Changed in 812 commits", "Imported by 18 non-test files",
              "Pulls in much of the codebase, directly or indirectly"]
}
```
The scoring formula and weights are documented in `docs/metrics.md`. Each part is already scaled to 0..1.

## `people`
```jsonc
{
  "authors": [
    { "id": "a1", "name": "Jane Doe", "emails": ["jane@example.org", "jane@home.org"],
      "commits": 1200, "isBot": false, "lastCommitAt": "2026-09-01T10:00:00Z" }
  ],
  "fileOwnership": [
    { "path": "src/flask/app.py", "totalLines": 1536,
      "owners": [ { "authorId": "a1", "lines": 900, "share": 0.586 } ],   // at most 5, most first
      "otherLines": 120, "ownerCount": 23,
      "busFactor": 1, "topOwnerActive": true, "flags": [] }             // "single-owner", "orphaned"
  ],
  "directoryOwnership": [ { "path": "src/flask", ... same fields ... } ]  // "." is the whole repo
}
```
Authors are people after identity merging; `emails` lists every identity merged into one person.
`totalLines` counts human-authored lines only (bots are excluded from ownership).
`busFactor` is the smallest number of people who together own at least half of the lines.
Blame ignores whitespace-only changes (see `spikes/README.md` for why). Full definitions: `docs/metrics.md`.

## `coupling`
```jsonc
{
  "files": [
    { "a": "src/flask/app.py", "b": "src/flask/scaffold.py",   // a < b alphabetically
      "together": 57, "aCommits": 812, "bCommits": 140, "degree": 0.41, "hasImportEdge": true }
  ],
  "components": [ { "a": "src/flask", "b": "tests", "together": 900, "aCommits": 1400, "bCommits": 950,
                    "degree": 0.76 } ],
  "skippedLargeCommits": 16
}
```
Commits that touch more than 30 files (bulk reformatting, mass renames) are excluded, and counted in
`skippedLargeCommits`. `degree = together / average(aCommits, bCommits)`; thresholds in `docs/metrics.md`.
`hasImportEdge` is `true`, `false`, or `null` when unknown (either file is not a parsed Python file).
`false` pairs are hidden dependencies: they change together without importing each other.

## `hotspots[]`
```jsonc
{ "rank": 1, "path": "src/flask/app.py", "score": 0.93, "commits": 812, "lines": 1536, "complexity": 3222 }
```
`complexity` is indentation complexity; `score` multiplies normalized commits and complexity
(`docs/metrics.md`, "Hotspots"). At most 50, non-test code files only.

## `findings[]`
```jsonc
{
  "id": "import-cycle-1",          // kind + position within the kind
  "kind": "import-cycle",          // duplicate-module | repeated-logic | import-cycle |
                                   // generated-file-committed | missing-tests | unreferenced-file
  "severity": "warn",              // warn | info
  "title": "Import cycle between 2 directories: src, src/core",
  "detail": "Each of these directories depends on the others through imports, ...",
  "evidence": [
    { "path": "src/dashboard.py", "startLine": 15, "endLine": 15, "note": "imports src/core/backtest.py" },
    { "path": "src/core/backtest.py", "startLine": 7, "endLine": 7, "note": "imports src/covariance.py" }
  ]
}
```
Every finding carries evidence: a path, optionally a line range, optionally a note. `startLine`,
`endLine` and `note` are omitted when not applicable; a note without a path summarises files not listed
("and 12 more"). Rules for every kind: `docs/metrics.md`, "Findings". At most 50 findings per kind.
