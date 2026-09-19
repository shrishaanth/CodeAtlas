# Report schema (draft v0.1)

The report is the one contract in CodeAtlas. The analysis engine writes it, and everything else
(the web UI, the JSON export, the static demo and later the CLI or MCP server) only reads it.
The static demo is literally a set of these files, so the UI must never need anything that is not in here.

Status: **draft**. Fields are expected to change during M1-M3. `schemaVersion` changes when they do.
Example values below are illustrative only, not real measurements.

**Produced today (M2):** `repo`, `limits`, `overview`, `files`, `components`, `edges`, `readingOrder`,
`people`, `coupling`, `hotspots`. Sections and fields marked *(M3)* are planned, not yet written.
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
  "findings":    [ ... ]    // (M3)
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
  "parseErrors":  [ { "path": "tests/bad.py", "startLine": 3 } ]
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
{ "id": "src/flask", "path": "src/flask", "files": 24, "lines": 9800 }
```
In v1 a component is a directory (`.` for the root). *(M3)* adds `layer`, from import direction:
components that import nothing internal sit at layer 0.

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
  "parts": { "centrality": 0.81, "fanIn": 0.92, "churn": 0.75 },
  "reasons": ["Imported by 18 non-test files", "Changed in 812 commits"]
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
  "id": "dup-001",
  "kind": "duplicate-module",      // duplicate-module | unreferenced-file | missing-tests |
                                   // generated-file-committed | repeated-logic
  "severity": "warn",              // info | warn
  "title": "Two modules named hrp.py with near-identical content",
  "detail": "src/hrp.py and src/core/hrp.py share 92% of their lines.",
  "evidence": [
    { "path": "src/hrp.py", "startLine": 1, "endLine": 80 },
    { "path": "src/core/hrp.py", "startLine": 1, "endLine": 85 }
  ]
}
```
