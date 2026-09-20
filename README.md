# CodeAtlas

Point CodeAtlas at a repository and it produces a browsable report: an architecture map, where to
start reading, who owns what, which files change together, hotspots and concrete findings.
Most of the report is computed from the code (Tree-sitter) and the git history (JGit), so every
number can be traced back to a file, line or commit. An LLM is optional and only used for Q&A.

> **Status:** early development. Milestones M1-M4 of [PLAN.md](PLAN.md) are done. The reading order has
> been measured against independent sources on 20 repositories, which is how its current formula was
> chosen: see [docs/evaluation.md](docs/evaluation.md), including where it only ties simple baselines.

## What it produces today
- **Overview:** commits, contributors, files, lines of code, languages, test-file ratio, history span.
- **Reading order:** Python files ranked by churn, reach (how much of the codebase a file pulls in) and
  fan-in, each with the parts of its score and plain-language reasons. The weights were chosen on half of
  20 repositories and checked on the other half: [docs/evaluation.md](docs/evaluation.md).
- **Import graph:** interactive, grouped by directory, showing what a file imports and what imports it,
  optionally with co-change links.
- **Ownership:** who last changed each current line (git blame, whitespace ignored, `.git-blame-ignore-revs`
  honoured), per file and directory, with bus factor and single-owner / orphaned flags. One person's
  different emails and names are merged (`.mailmap`, same email, same full name); bots are excluded.
- **Change coupling:** files and top-level areas that change in the same commits, marking pairs with no
  import between them (hidden dependencies).
- **Hotspots:** code that is both frequently changed and deeply nested (indentation complexity).
- **Architecture:** directories stacked in layers by import direction, with import cycles marked.
- **Findings,** each with file and line evidence linked to GitHub: near-duplicate files and same-named
  module versions, identical function bodies, the same client rewritten in several services (by file
  name), import cycles between directories, committed build output, caches and `.env` files (never read),
  areas without tests, and modules no code imports ("no static import found", not "dead code").
- **File details:** owners, files it usually changes with, imports, importers, definitions with GitHub links.
- **JSON export** of the whole report. Format: [docs/report-schema.md](docs/report-schema.md).

Pre-computed reports in `frontend/public/demo/` open without any backend.

## Run locally

Requires Docker.

```bash
docker compose up --build
```

Then open http://localhost:3000. The API is at http://localhost:8080.

| Endpoint | Purpose |
|---|---|
| `POST /api/analyses` with `{"repo": "https://github.com/owner/repo"}` | Queue an analysis (202) |
| `GET /api/analyses/{id}` | Status and progress |
| `GET /api/analyses/{id}/report` | The report JSON, once done |
| `GET /api/analyses` | Recently completed analyses |
| `GET /api/info`, `GET /actuator/health` | Version and health |

### Offline analysis (no server or database)
Writes a report JSON file; used for the demo reports and the evaluation.
```bash
cd backend
./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" io.github.shrishaanth.codeatlas.cli.AnalyzeCommand https://github.com/pallets/flask ../frontend/public/demo/flask.json
```
(On Windows use `;` instead of `:` in the classpath.)

### Without Docker (development)
```bash
docker compose up -d db            # Postgres only
cd backend && ./mvnw spring-boot:run
cd frontend && npm install && npm run dev    # http://localhost:5173, proxies /api to :8080
```

## Configuration

All settings come from environment variables.

| Variable | Used by | Default |
|---|---|---|
| `DATABASE_URL` | backend | `jdbc:postgresql://localhost:5432/codeatlas` |
| `DATABASE_USER` / `DATABASE_PASSWORD` | backend | `codeatlas` / `codeatlas` |
| `PORT` | backend | `8080` |
| `CODEATLAS_CORS_ALLOWED_ORIGINS` | backend | `http://localhost:5173` |
| `CODEATLAS_ALLOW_LOCAL_PATHS` | backend: accept filesystem paths (never on a public server) | `false` |
| `CODEATLAS_MAX_COMMITS` | backend: history walk cap | `20000` |
| `CODEATLAS_CLONE_TIMEOUT_SECONDS` | backend | `120` |
| `CODEATLAS_MAX_QUEUED` | backend: waiting analyses before refusing | `20` |
| `CODEATLAS_MAX_BLAME_FILES` | backend: files blamed per analysis, most-changed first | `3000` |
| `CODEATLAS_THREADS` | backend: parallel blame workers, `0` = one per CPU | `0` |
| `VITE_API_BASE_URL` | frontend (build time) | empty, meaning same origin |

## How good is it?

Measured, not asserted. On 10 repositories that took no part in choosing the formula, the reading order
places documentation-mentioned files at mean percentile **0.69**, against 0.67 for sorting by file size,
0.67 for sorting by commit count and 0.50 for random order. The first version of the formula scored 0.66
and lost to both baselines; measuring it is what led to replacing it. Ownership matched CODEOWNERS for the
top owner in 6 of 16 directories, across the only 2 repositories in the set that have a CODEOWNERS file.
Method, per-repository numbers and limits: [docs/evaluation.md](docs/evaluation.md).

## Repository layout

| Path | Contents |
|---|---|
| `backend/` | Spring Boot service: analysis engine, evaluation harness and API |
| `frontend/` | React + TypeScript + Vite UI |
| `docs/` | Report schema and design docs |
| `eval/` | Evaluation inputs and results |
| `spikes/` | Throwaway experiments and their measured results |

## Tech stack
Java 17, Spring Boot 4, Tree-sitter (JNI binding), JGit, PostgreSQL, React, TypeScript, Vite, Cytoscape.js,
Docker Compose, GitHub Actions. Backend tests use Testcontainers (Postgres in Docker).
