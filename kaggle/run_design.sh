#!/usr/bin/env bash
# Run an Auralis block-by-block RTL design on a free Kaggle GPU and download the result.
#
#   kaggle/run_design.sh job.json out_dir
#
# job.json: {statement, evidence, guidance, maxBlocks, iterations[, blocks]}
set -euo pipefail
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8

HERE="$(cd "$(dirname "$0")" && pwd)"
JOBFILE="${1:?job.json required}"
OUT="${2:?out dir required}"
USER_NAME="$(kaggle config view 2>/dev/null | awk -F': ' '/username/{print $2}' | tr -d '\r ')"
USER_NAME="${USER_NAME:-sujitnarrayanm}"
KERNEL_SLUG="auralis-design-worker"

BUILD="$HERE/design-worker/build"
rm -rf "$BUILD" && mkdir -p "$BUILD"
python - "$HERE/design-worker/worker.py" "$JOBFILE" "$BUILD/worker.py" <<'PY'
import json, sys
src, job, dst = sys.argv[1:]
code = open(src, encoding="utf-8").read().replace("__JOB__", ascii(json.load(open(job, encoding="utf-8"))))
code = code.encode("ascii", "backslashreplace").decode("ascii")  # Kaggle CLI on Windows reads cp1252
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
  "dataset_sources": [],
  "competition_sources": [],
  "kernel_sources": []
}
EOF
kaggle kernels push -p "$BUILD"
while true; do
  STATUS="$(kaggle kernels status "$USER_NAME/$KERNEL_SLUG" 2>/dev/null | tail -1 || true)"  # a network blip must not end the wait
  echo "$(date +%T) $STATUS"
  case "$STATUS" in
    *COMPLETE*|*complete*|*ERROR*|*error*|*CANCEL*|*cancel*) break ;;
  esac
  sleep 60
done
mkdir -p "$OUT"
kaggle kernels output "$USER_NAME/$KERNEL_SLUG" -p "$OUT" -o
ls -la "$OUT"
