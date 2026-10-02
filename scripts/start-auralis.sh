#!/usr/bin/env bash
# Start the whole free Auralis stack; safe to re-run (skips what is already up).
#
#   scripts/start-auralis.sh          # start everything
#   scripts/start-auralis.sh status   # just report what is running
#
# Services: Postgres+pgvector :5433, Redis :6379, SearXNG :8888 (Docker),
# Ollama :11434, Laya :8000, Auralis core :8080 (REST + MCP at /mcp).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/logs"
mkdir -p "$LOGS"

up() { curl -s --max-time 3 "$1" >/dev/null 2>&1; }

status() {
  for s in "postgres|docker exec auralis-postgres pg_isready -q -U auralis" \
           "searxng|up http://localhost:8888" \
           "ollama|up http://localhost:11434/api/tags" \
           "laya|up http://localhost:8000/health" \
           "core|up http://localhost:8080/actuator/health" \
           "semiconductor|up http://localhost:8090/actuator/health"; do
    name="${s%%|*}"; check="${s#*|}"
    if eval "$check" 2>/dev/null; then echo "  ✓ $name"; else echo "  ✗ $name"; fi
  done
}

wait_for() {  # url, name, seconds
  for _ in $(seq 1 "$3"); do up "$1" && { echo "  ✓ $2"; return 0; }; sleep 2; done
  echo "  ✗ $2 did not come up — see $LOGS"; return 1
}

if [ "${1:-}" = "status" ]; then status; exit 0; fi

echo "Docker services"
if ! docker info >/dev/null 2>&1; then
  echo "  Docker is not running — start Docker Desktop first."; exit 1
fi
(cd "$ROOT" && docker compose up -d >/dev/null 2>&1) && echo "  ✓ postgres, redis, searxng"

echo "Ollama"
if ! up http://localhost:11434/api/tags; then
  (ollama serve </dev/null >"$LOGS/ollama.log" 2>&1 &)
  wait_for http://localhost:11434/api/tags ollama 15 || exit 1
else echo "  ✓ ollama"; fi
ollama list | grep -q auralis-qwen3 || ollama create auralis-qwen3 -f "$ROOT/infra/ollama/qwen3-4b-16k.Modelfile"

echo "Laya"
if ! up http://localhost:8000/health; then
  (cd "$ROOT/vendor/laya" && HF_HUB_OFFLINE=1 LAYA_PORT=8000 LAYA_DEVICE=cpu \
     LAYA_MODELS=english LAYA_THREADS=4 nohup ./.venv/Scripts/laya-serve.exe \
     </dev/null >"$LOGS/laya.log" 2>&1 &)
  wait_for http://localhost:8000/health laya 60 || exit 1
else echo "  ✓ laya"; fi

SEMI="${AURALIS_SEMI_DIR:-$ROOT/../auralis-semiconductor}"
if [ -d "$SEMI/server" ]; then
  echo "Semiconductor domain server"
  docker image inspect auralis-eda >/dev/null 2>&1 || docker build -q -t auralis-eda "$SEMI/eda" >/dev/null
  if ! up http://localhost:8090/actuator/health; then
    (cd "$SEMI/server" && nohup ./mvnw -q -B spring-boot:run </dev/null >"$LOGS/semiconductor.log" 2>&1 &)
    wait_for http://localhost:8090/actuator/health "semiconductor (MCP :8090/mcp)" 90 || true
  else echo "  ✓ semiconductor"; fi
fi
docker image inspect auralis-pysandbox >/dev/null 2>&1 || docker build -q -t auralis-pysandbox "$ROOT/infra/pysandbox" >/dev/null

echo "Auralis core"
if ! up http://localhost:8080/actuator/health; then
  (cd "$ROOT/auralis-core" && nohup ./mvnw -q -B spring-boot:run </dev/null >"$LOGS/core.log" 2>&1 &)
  wait_for http://localhost:8080/actuator/health "core (REST :8080, MCP :8080/mcp)" 90 || exit 1
else echo "  ✓ core"; fi
