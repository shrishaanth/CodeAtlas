# CodeAtlas: project plan

Point CodeAtlas at a repository and it produces a browsable report: architecture map, reading order,
ownership and bus-factor, change coupling, hotspots and findings, with optional Q&A that cites files and lines.
Most output is computed from code and git history (deterministic). The LLM is optional and used only for Q&A.

## Decisions (locked)
| Topic | Decision |
|---|---|
| Purpose | Student resume project, not a product |
| Backend | Java 17 + Spring Boot 4, Maven. Tree-sitter via the `bonede` JNI binding, JGit (see `spikes/README.md`) |
| Analyzed language (v1) | Python only. Git-based features work on any repo. |
| Frontend | React + TypeScript + Vite, Cytoscape.js, Monaco |
| Storage | PostgreSQL (pgvector only if Q&A is built) |
| LLM Q&A | Optional. The app works fully without an API key. |
| Integration | One of CLI or MCP server (decide at M6). No GitHub App. |
| Hosting | Vercel (frontend) + Render (backend) + static pre-computed demo as the default |
| Time budget | 20+ h/week, solo |

## Design rules (cheap now, painful later)
1. **The report JSON is the contract.** The analysis engine writes it, and the UI, CLI and MCP only read it. Define the schema first (`docs/report-schema.md`).
2. **UI has two modes:** load a report from a static file (demo) or from the API (live). Same components.
3. **All config from environment variables.** No hardcoded URLs or secrets.
4. **Every number is traceable** to a file, commit or edge, and the UI shows why.
5. **No LLM in the core pipeline.**

## Pipeline
fetch repo -> parse (Tree-sitter) -> mine git (JGit) -> build graph (import + co-change edges)
-> analyzers (layers, reading order, hotspots, ownership, findings) -> report JSON (+ optional index for Q&A)

## Repo layout
```
CodeAtlas/
  backend/     Spring Boot; packages: fetch, parse, gitmine, graph, analyze, report, api, jobs, (index, qa)
  frontend/    React + TS + Vite
  eval/        evaluation harness + results
  demo/        pre-computed report JSON for the static demo
  docs/        report-schema.md, architecture.md, evaluation.md
  docker-compose.yml
  .github/workflows/
```

## Milestones (about 210-290 h; at 20 h/week roughly 10-14 weeks)

### M0: Setup and risk spikes (week 1, ~10-15 h) -- DONE 2026-09-19, both spikes GO
- Skeleton: backend and frontend boot, Docker Compose, CI that builds both.
- **Spike A: Tree-sitter from Java.** Parse one Python file and list imports. Two known bindings exist
  (the official `jtreesitter`, and JNI-based ones). From memory, the official one may need JDK 22+,
  and this machine has JDK 17. Verify before choosing, since this may mean installing a newer JDK.
- **Spike B: JGit blame speed** on a mid-size repo. If unusable, fall back to the `git` CLI for blame.
- Write `docs/report-schema.md`.
- Exit: both spikes give a clear go/no-go. If A fails, decide Java-with-workaround or a Python backend.

### M1: Vertical slice (weeks 2-3, ~40-55 h) -- DONE 2026-09-19
Clone a repo, parse Python imports and symbols, mine git, build the import graph, compute a first
reading order, and show the graph and ranked list in the UI. Ugly is fine. End to end is the goal.
- Exit: analyzing a public Python repo shows a clickable graph and a reading order with score components.

### M2: Git analyzers (weeks 4-5, ~40-50 h) -- DONE 2026-09-19
Ownership from blame, bus-factor flags, change coupling (file and component level, with counts and
confidence), hotspots. Basic author alias merging.

### M3: Findings and full report UI (weeks 6-7, ~30-40 h) -- DONE 2026-09-20
Duplicate modules, unreferenced files, missing tests, committed generated files, repeated logic.
Overview page, ownership views, JSON export, background job runner with progress.

### M4: Evaluation harness (weeks 8-9, ~25-35 h)  -- do not cut
Run on ~20 Python repos. Compare reading order and ownership against independent sources
(contributor docs, CODEOWNERS, maintainers list). Report results honestly, including failures, in `docs/evaluation.md`.
Also compare against a baseline (e.g. rank by file size or plain import count) so the claim is measured.

**Known issue to test first (seen in M1 on 3 of 3 repos):** reading order v1 puts low-level files that
everything imports at the top: `_compat.py` and `_winconsole.py` in click, `exc.py` in itsdangerous,
`globals.py` in flask. A newcomer would rather start with the core concepts (`core.py`, `serializer.py`,
`app.py`). PageRank passes importance down to whatever central files import, which favours foundations.
Ideas to evaluate against baselines, not to adopt on a hunch: lower the centrality weight, penalise
private `_module.py` files, use reverse PageRank (files that *use* much of the codebase), or order by
dependency layers instead of a single score. Also check the 5-line candidate threshold and whether
`examples/` should be ranked with library code.

### M5: Q&A, optional (weeks 10-11, ~30-40 h)
Chunk code, keyword index plus embeddings, retrieval, LLM answer with `path:line` citations and shown source code.
Cut first if time is short.

### M6: One integration (week 12, ~15-25 h)
CLI or MCP server, reading the same report JSON.

### M7: Ship (weeks 13-14, ~20-30 h)
Pre-computed demo on Vercel, live backend on Render (with size limits and a cold-start message),
README, architecture diagram, short demo video.

## Measurements so far (for deployment and the README)
- Flask (5.5k commits, 108 blamed files): full analysis about 8-10 s locally, excluding the clone.
- psf/requests (6.5k commits) in Docker capped at **512 MB and 1 CPU** (Render free-tier shape):
  finished in 56 s including the clone, peak container memory **267 MiB**, no out-of-memory kill.
  Without a cap the JVM grows to about 510 MiB simply because memory is available; that number is not a need.
- On Render set `CODEATLAS_THREADS=1` (one CPU) and keep the blame cap; very large repos still need a
  repository size limit before cloning (M7).

## Findings checked against the early prototype (M3)
The throwaway prototype (regex parsing, before this repo) reported issues in two repos. The real
implementation, run independently, finds:

| Repo | Prototype finding | CodeAtlas |
|---|---|---|
| shrishaanth/Syntropy | duplicate `src/hrp.py` / `src/core/hrp.py` | Found as *two versions* of one module: they share only 4% of lines, so the prototype's "duplicate" (by name) was imprecise |
| shrishaanth/Syntropy | stray `unit_test1.py` | Found (no static import) |
| shrishaanth/Syntropy | unused `src/domain.py` | Found ("only tests import it") |
| hemanthvnp/CineScope | five separate TMDB clients | Found by file-name stem across 4 areas (JS and Python) |
| hemanthvnp/CineScope | tests under only one service | Found (3 areas without tests) |
| hemanthvnp/CineScope | committed cache file | Found (hash-named cache entry) |
| hemanthvnp/CineScope | committed `status.txt` dump | **Missed**: no generic rule without false positives |

False-positive check on well-kept repos after tuning: Flask 4 findings (all verified by reading the
code), psf/requests 0. Two rules were tightened because of Flask (layered same-named modules, `.env`
test fixtures) and one because of requests (docs areas without tests). This is still a small sample;
M4 should include findings in the evaluation.

Known gaps: repeated-logic by function body is Python-only; JS/TS duplication is only caught by file
names. Flask's core directories form one import cycle, so its architecture view has little layering.

## Cut order if time runs short
1. M6 integration, 2. M5 Q&A, 3. some M3 findings. Never cut M4.

## Main risks
| Risk | Effect | Response |
|---|---|---|
| Tree-sitter Java binding problems | Blocks parsing | Spike A in week 1, with fallback options |
| Blame too slow on big repos | Analysis takes minutes | Use git CLI, cache, cap size, show progress |
| Render free tier (sleep, small RAM, ephemeral disk) | Poor live demo | Static demo is the default |
| Alias splitting skews ownership | Wrong bus-factor | Alias merging, and document as a limitation |
| Weak results on small single-author repos | Boring demos | Demo on large multi-author repos |

## Definition of done
- Runs locally with one Docker Compose command.
- Public demo link works even when the backend is asleep.
- Evaluation results are published with baselines and known failures.
- README explains what it does and does not do.
