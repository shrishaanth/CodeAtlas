# CodeAtlas

Point CodeAtlas at a repository and it produces a browsable report: an architecture map, where to
start reading, who owns what, which files change together, hotspots and concrete findings.
Most of the report is computed from the code (Tree-sitter) and the git history (JGit), so every
number can be traced back to a file, line or commit. An LLM is optional and only used for Q&A.

> **Status:** early development (milestone M0 of [PLAN.md](PLAN.md) done: skeleton and risk spikes).
> The analysis itself is not built yet.

## Run locally

Requires Docker.

```bash
docker compose up --build
```

Then open http://localhost:3000. The API is at http://localhost:8080 (`/api/info`, `/actuator/health`).

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
| `VITE_API_BASE_URL` | frontend (build time) | empty, meaning same origin |

## Repository layout

| Path | Contents |
|---|---|
| `backend/` | Spring Boot service: analysis engine and API |
| `frontend/` | React + TypeScript + Vite UI |
| `docs/` | Report schema and design docs |
| `spikes/` | Throwaway experiments and their measured results |

## Tech stack
Java 17, Spring Boot 4, Tree-sitter (JNI binding), JGit, PostgreSQL, React, TypeScript, Vite, Docker Compose, GitHub Actions.
