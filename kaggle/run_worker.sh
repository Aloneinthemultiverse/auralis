#!/usr/bin/env bash
# Run AgentHarness deep research on a Kaggle GPU (free) and download answers.
#
#   kaggle/run_worker.sh questions.json [out_dir] [model_id|auto] [max_turns]
#
# questions.json: list of strings or {"id","question"} objects.
# Uses the kaggle CLI's existing credentials. Everything stays private.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
QUESTIONS="${1:?questions.json required}"
OUT="${2:-$ROOT/kaggle/out}"
MODEL="${3:-auto}"
TURNS="${4:-40}"
USER_NAME="$(kaggle config view 2>/dev/null | awk -F': ' '/username/{print $2}' | tr -d '\r ')"
USER_NAME="${USER_NAME:-sujitnarrayanm}"
DS_SLUG="auralis-agentharness"
KERNEL_SLUG="auralis-research-worker"

# 1. Sync the patched AgentHarness source as a private dataset.
DS="$HERE/agentharness-dataset"
rm -rf "$DS/src" && mkdir -p "$DS/src"
(cd "$ROOT/vendor/AgentHarness" && tar --exclude=.venv --exclude=.git --exclude=data \
   --exclude='benchmarks/datasets' --exclude=assets --exclude=.env --exclude='__pycache__' -cf - .) \
  | (cd "$DS/src" && tar -xf -)
cat > "$DS/dataset-metadata.json" <<EOF
{"title": "auralis-agentharness", "id": "$USER_NAME/$DS_SLUG", "licenses": [{"name": "apache-2.0"}]}
EOF
if kaggle datasets status "$USER_NAME/$DS_SLUG" >/dev/null 2>&1; then
  kaggle datasets version -p "$DS" -m "sync $(date +%F_%T)" --dir-mode tar -q
else
  kaggle datasets create -p "$DS" --dir-mode tar -q
fi

# 2. Render the worker with this batch's questions and push it.
BUILD="$HERE/research-worker/build"
rm -rf "$BUILD" && mkdir -p "$BUILD"
python - "$HERE/research-worker/worker.py" "$QUESTIONS" "$MODEL" "$TURNS" "$BUILD/worker.py" <<'PY'
import json, sys
src, qpath, model, turns, dst = sys.argv[1:]
code = open(src, encoding="utf-8").read()
qs = json.load(open(qpath, encoding="utf-8"))
code = (code.replace("__QUESTIONS__", repr(qs))
            .replace("__MODEL_ID__", model).replace("__MAX_TURNS__", turns))
open(dst, "w", encoding="utf-8").write(code)
PY
cat > "$BUILD/kernel-metadata.json" <<EOF
{
  "id": "$USER_NAME/$KERNEL_SLUG",
  "title": "$KERNEL_SLUG",
  "code_file": "worker.py",
  "language": "python",
  "kernel_type": "script",
  "is_private": true,
  "enable_gpu": true,
  "machine_shape": "NvidiaTeslaT4",
  "enable_internet": true,
  "dataset_sources": ["$USER_NAME/$DS_SLUG"],
  "competition_sources": [],
  "kernel_sources": []
}
EOF
sleep 20  # let the dataset version finish processing
kaggle kernels push -p "$BUILD"

# 3. Wait for completion, then download outputs.
while true; do
  STATUS="$(kaggle kernels status "$USER_NAME/$KERNEL_SLUG" 2>/dev/null | tail -1)"
  echo "$(date +%T) $STATUS"
  case "$STATUS" in
    *COMPLETE*|*complete*) break ;;
    *ERROR*|*error*|*CANCEL*|*cancel*) echo "kernel failed"; break ;;
  esac
  sleep 60
done
mkdir -p "$OUT"
kaggle kernels output "$USER_NAME/$KERNEL_SLUG" -p "$OUT" -o
ls -la "$OUT"
