#!/usr/bin/env bash
# One-command local evaluation stack: disposable pgvector database, backend, realistic-corpus fixture.
#
#   scripts/eval_stack.sh up      # preflight, start DB + backend, load the corpus fixture
#   scripts/eval_stack.sh status  # what is running and where the credentials are
#   scripts/eval_stack.sh down    # stop backend and database, delete the state directory
#
# Everything is disposable: the database has no volume, credentials are generated per `up`
# into a 0700 state directory outside the repository, and `down` removes them.
#
# Environment (all optional):
#   AI_BASE_URL           LM Studio / OpenAI-compatible endpoint   (default http://localhost:1234/v1)
#   AI_MODEL_ID           chat model                               (default google/gemma-4-26b-a4b-qat)
#   AI_EMBEDDING_MODEL_ID embedding model                          (default text-embedding-bge-m3)
#   AI_TEMPERATURE        sampling temperature                     (default 0)
#   EVAL_STACK_DIR        state directory                          (default ${TMPDIR:-/tmp}/rag-kb-eval-stack)
#   EVAL_DB_PORT          host port for the database               (default 55432)
#   EVAL_BACKEND_PORT     backend port                             (default 8080)
#   EVAL_FIXTURE          corpus (28 documents, corp.* actors) or synthetic (sample_faq + policy/stress
#                         documents, demo.* actors: golden, answer-quality, stress)  (default corpus)
#   EVAL_SKIP_PROBE=1     skip the structured-output probe (not recommended: a chat model with
#                         thinking enabled silently truncates every answer)
#
# Note: these defaults describe the evaluated configuration (bge-m3, temperature 0), which has also
# been the shipped application default since 2026-10-21.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_BASE="${TMPDIR:-/tmp}"
STATE_DIR="${EVAL_STACK_DIR:-${TMP_BASE%/}/rag-kb-eval-stack}"
STATE_DIR="${STATE_DIR%/}"
DB_PORT="${EVAL_DB_PORT:-55432}"
BACKEND_PORT="${EVAL_BACKEND_PORT:-8080}"
DB_NAME="rag-eval-stack-db"
AI_BASE_URL="${AI_BASE_URL:-http://localhost:1234/v1}"
AI_MODEL_ID="${AI_MODEL_ID:-google/gemma-4-26b-a4b-qat}"
AI_EMBEDDING_MODEL_ID="${AI_EMBEDDING_MODEL_ID:-text-embedding-bge-m3}"
AI_TEMPERATURE="${AI_TEMPERATURE:-0}"
FIXTURE="${EVAL_FIXTURE:-corpus}"
case "$FIXTURE" in
  corpus) ACTORS=(corp.sre corp.hr corp.legal corp.manager corp.outsider) ;;
  synthetic) ACTORS=(demo.employee demo.admin demo.outsider demo.auditor) ;;
  *) printf '[eval-stack] ERROR: EVAL_FIXTURE must be corpus or synthetic\n' >&2; exit 1 ;;
esac

say() { printf '[eval-stack] %s\n' "$*"; }
die() { printf '[eval-stack] ERROR: %s\n' "$*" >&2; exit 1; }

port_listening() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

preflight() {
  command -v docker >/dev/null || die "docker is not installed"
  docker info >/dev/null 2>&1 || die "Docker is not running (try: open -a Docker)"
  command -v python3 >/dev/null || die "python3 is required"
  command -v curl >/dev/null || die "curl is required"

  local models
  models="$(curl -fsS --max-time 5 "${AI_BASE_URL%/}/models")" \
    || die "cannot reach ${AI_BASE_URL%/}/models (try: lms server start)"
  for id in "$AI_MODEL_ID" "$AI_EMBEDDING_MODEL_ID"; do
    python3 -c 'import json,sys; ids=[m["id"] for m in json.load(sys.stdin)["data"]]; sys.exit(0 if sys.argv[1] in ids else 1)' \
      "$id" <<<"$models" || die "model '$id' is not loaded in the provider (try: lms load $id)"
  done

  port_listening "$DB_PORT" && die "port $DB_PORT is already in use (another database? set EVAL_DB_PORT)"
  port_listening "$BACKEND_PORT" && die "port $BACKEND_PORT is already in use (set EVAL_BACKEND_PORT)"
  [[ ! -e "$STATE_DIR/backend.pid" ]] || die "state directory $STATE_DIR already holds a stack; run: $0 down"
  return 0
}

# A structured-output probe can still pass while thinking is enabled (the model just spends more
# tokens), so check the decisive signal directly: a trivial plain request must produce no reasoning.
check_thinking_off() {
  python3 - "$AI_BASE_URL" "$AI_MODEL_ID" <<'PY' || die "thinking is enabled for $AI_MODEL_ID. Turn Enable Thinking OFF in LM Studio (reloading the model with 'lms load' resets it)"
import json, sys, urllib.request
base, model = sys.argv[1].rstrip("/"), sys.argv[2]
body = json.dumps({"model": model, "temperature": 0, "max_tokens": 400,
                   "messages": [{"role": "user", "content": "用一句话说明什么是向量数据库。"}]}).encode()
req = urllib.request.Request(base + "/chat/completions", body, {"Content-Type": "application/json"})
with urllib.request.urlopen(req, timeout=120) as r:
    data = json.load(r)
message = data["choices"][0]["message"]
reasoning = bool((message.get("reasoning_content") or "").strip())
tokens = data["usage"]["completion_tokens"]
print(f"[eval-stack] thinking check: reasoning={'present' if reasoning else 'absent'}, completion_tokens={tokens}")
sys.exit(1 if reasoning else 0)
PY
}

probe_chat_model() {
  [[ "${EVAL_SKIP_PROBE:-0}" == "1" ]] && { say "probe skipped (EVAL_SKIP_PROBE=1)"; return 0; }
  check_thinking_off
  say "probing structured output on $AI_MODEL_ID"
  python3 "$ROOT/evaluation/probe_structured_output.py" --base-url "$AI_BASE_URL" \
    --model "$AI_MODEL_ID" --repeats 2 --output "$STATE_DIR/probe.json" >/dev/null \
    || die "probe could not run"
  python3 - "$STATE_DIR/probe.json" <<'PY' || die "probe failed: the model did not return valid JSON for every request. Is Enable Thinking OFF for this model in LM Studio?"
import json, sys
rows = json.load(open(sys.argv[1]))["results"]
valid = sum(1 for r in rows if r.get("contractValid"))
print(f"[eval-stack] probe {valid}/{len(rows)} valid")
sys.exit(0 if rows and valid == len(rows) else 1)
PY
}

write_credentials() {
  python3 - "$STATE_DIR" "${ACTORS[@]}" <<'PY'
import json, os, secrets, sys
state, actors = sys.argv[1], sys.argv[2:]
def write(name, value):
    path = os.path.join(state, name)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        json.dump(value, fh, indent=2)
        fh.write("\n")
write("admin.json", {"username": "demo", "password": "demo123456"})
write("credentials.json", {
    a: {"username": a.replace(".", "_") + "_eval", "password": secrets.token_urlsafe(18)} for a in actors
})
PY
}

wait_for() { # description, attempts, command...
  local what="$1" attempts="$2"; shift 2
  local i
  for ((i = 1; i <= attempts; i++)); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  die "timed out waiting for $what"
}

cmd_up() {
  preflight
  mkdir -p "$STATE_DIR" && chmod 700 "$STATE_DIR"
  : >"$STATE_DIR/.eval-stack"
  probe_chat_model

  say "starting disposable database on port $DB_PORT"
  docker rm -f "$DB_NAME" >/dev/null 2>&1 || true
  docker run -d --rm --name "$DB_NAME" \
    -e POSTGRES_DB=rag_knowledge_base -e POSTGRES_USER=rag -e POSTGRES_PASSWORD=rag \
    -p "$DB_PORT":5432 pgvector/pgvector:pg16 >/dev/null
  wait_for "the database" 30 docker exec "$DB_NAME" pg_isready -U rag -d rag_knowledge_base

  write_credentials
  mkdir -p "$STATE_DIR/uploads"

  say "starting backend on port $BACKEND_PORT (embedding=$AI_EMBEDDING_MODEL_ID, temperature=$AI_TEMPERATURE)"
  (
    cd "$ROOT/backend"
    SERVER_PORT="$BACKEND_PORT" \
    DB_URL="jdbc:postgresql://localhost:$DB_PORT/rag_knowledge_base" \
    AI_BASE_URL="$AI_BASE_URL" AI_MODEL_ID="$AI_MODEL_ID" \
    AI_EMBEDDING_MODEL_ID="$AI_EMBEDDING_MODEL_ID" AI_TEMPERATURE="$AI_TEMPERATURE" \
    AI_DAILY_LIMIT=5000 UPLOAD_STORAGE_DIR="$STATE_DIR/uploads" \
    nohup ./mvnw -q spring-boot:run >"$STATE_DIR/backend.log" 2>&1 &
    echo $! >"$STATE_DIR/backend.pid"
  )
  wait_for "the backend (see $STATE_DIR/backend.log)" 90 curl -fsS "http://localhost:$BACKEND_PORT/readyz"

  if [[ "$FIXTURE" == synthetic ]]; then
    say "loading the synthetic fixture (sample_faq + policy/stress documents)"
    python3 "$ROOT/evaluation/prepare_api_fixture.py" \
      --base-url "http://localhost:$BACKEND_PORT" \
      --admin-credentials "$STATE_DIR/admin.json" \
      --credentials "$STATE_DIR/credentials.json" \
      --manifest "$STATE_DIR/manifest.json"
  else
    say "loading the corpus fixture (28 documents)"
    python3 "$ROOT/evaluation/prepare_corpus_fixture.py" \
      --base-url "http://localhost:$BACKEND_PORT" \
      --admin-credentials "$STATE_DIR/admin.json" \
      --credentials "$STATE_DIR/credentials.json" \
      --manifest "$STATE_DIR/manifest.json"
  fi

  cat <<EOF

[eval-stack] ready.
  backend      http://localhost:$BACKEND_PORT
  credentials  $STATE_DIR/credentials.json
  admin        $STATE_DIR/admin.json
  log          $STATE_DIR/backend.log

Example:
  python3 evaluation/run_repeated_eval.py run --dataset evaluation/datasets/corpus_v2.jsonl \\
    --credentials $STATE_DIR/credentials.json --label demo --repeats 1 --out-dir $STATE_DIR/runs \\
    --base-url http://localhost:$BACKEND_PORT

Stop and clean up with: $0 down
EOF
}

cmd_status() {
  if [[ -f "$STATE_DIR/backend.pid" ]] && kill -0 "$(cat "$STATE_DIR/backend.pid")" 2>/dev/null; then
    say "backend: running (pid $(cat "$STATE_DIR/backend.pid"))"
  else
    say "backend: not running"
  fi
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$DB_NAME"; then
    say "database: running ($DB_NAME, port $DB_PORT)"
  else
    say "database: not running"
  fi
  [[ -d "$STATE_DIR" ]] && say "state: $STATE_DIR" || say "state: none"
  curl -fsS --max-time 3 "http://localhost:$BACKEND_PORT/readyz" >/dev/null 2>&1 \
    && say "readyz: ok" || say "readyz: not reachable"
}

kill_tree() { # pid
  local pid="$1" child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do kill_tree "$child"; done
  kill "$pid" 2>/dev/null || true
}

cmd_down() {
  if [[ -f "$STATE_DIR/backend.pid" ]]; then
    local pid; pid="$(cat "$STATE_DIR/backend.pid")"
    if kill -0 "$pid" 2>/dev/null; then
      say "stopping backend (pid $pid)"
      kill_tree "$pid"
      for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    fi
  fi
  if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -qx "$DB_NAME"; then
    say "stopping database"
    docker rm -f "$DB_NAME" >/dev/null
  fi
  if [[ -f "$STATE_DIR/.eval-stack" ]]; then
    rm -rf "$STATE_DIR"
    say "removed $STATE_DIR"
  elif [[ -d "$STATE_DIR" ]]; then
    say "left $STATE_DIR in place: it was not created by this script (no .eval-stack marker)"
  fi
}

case "${1:-}" in
  up) cmd_up ;;
  status) cmd_status ;;
  down) cmd_down ;;
  *) sed -n '2,7p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
