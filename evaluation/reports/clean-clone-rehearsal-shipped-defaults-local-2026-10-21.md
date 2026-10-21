# Clean-clone rehearsal on the shipped defaults — local, 2026-10-21

Question: after the default embedding model and temperature changed, do the README Quick Start steps still work
from a fresh clone with no environment variables set?

## Setup

| Item | Value |
|---|---|
| Source | fresh `git clone -b chore/default-bge-m3-temp0` of `ddde736` into a scratch directory |
| Backend | started with `AI_EMBEDDING_MODEL_ID` and `AI_TEMPERATURE` **unset** (shipped defaults), `./mvnw spring-boot:run` |
| Models | `google/gemma-4-26b-a4b-qat` (Thinking off) and `text-embedding-bge-m3`, already loaded in LM Studio |
| Cache | Maven and the pgvector image were already cached; first-run download time was **not** measured |

## Deviations from the README, all machine-specific

- A `docker-compose.override.yml` renamed the database container and moved it to port 55433, because this machine
  already has `rag-knowledge-base-db` and `rag-knowledge-base-backend` containers (the known `container_name`
  limitation in the README prerequisites).
- The backend ran on port 18080 and the Quick Start commands had `8080` replaced by `18080`, because another
  desktop application holds port 8080 here. Nothing else in the commands was edited.
- The `docker compose` backend container (a ~16 min first image build) was not rehearsed; the backend ran through Maven.

## Result

| Step | Result |
|---|---|
| `docker compose up -d db` → database ready | 2 s |
| `./mvnw spring-boot:run` → `/readyz` UP | 6 s, no `ERROR` in the log |
| README steps 2 and 3 extracted from `README.md` and run verbatim | all passed, 6 s wall time in total |
| Covered question (warranty) | `found: true`, source `sample_faq.md`, 2.6 s |
| Uncovered question | `found: false`, no sources, `RETRIEVAL_MISS` |
| New employee, no grant | `found: false`, no sources |
| After grant | `found: true`, cites `sample_faq.md` |
| After revoke | `found: false` again |

No README change was needed. The Quick Start is unchanged by the new defaults, and the prerequisites section
already names the new embedding model and the upgrade path.

## Limits

- Warm caches, one machine, one run. A first-time download of Maven dependencies, the pgvector image, and the
  bge-m3 model would take far longer and is not covered here.
- Only the Quick Start was exercised, not the whole of `docs/DEMO.md`, the MCP smoke, or the Flutter client.
- Cleanup: the rehearsal database container, its volume, the clone and the backend process were removed. The
  existing `rag-knowledge-base-*` containers were not touched.
