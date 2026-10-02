"""Auralis research worker — runs on a Kaggle GPU (T4 x2), fully free.

1. Installs vLLM + AgentHarness deps.
2. Serves an Apodex-1.0 model behind an OpenAI-compatible endpoint.
3. Runs AgentHarness (free tools: DuckDuckGo search, direct fetch,
   subprocess code) over QUESTIONS.
4. Writes /kaggle/working/answers.json for the Auralis core to download.

QUESTIONS is replaced by kaggle/run_worker.sh before each push.
"""
import glob
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request

QUESTIONS = __QUESTIONS__
MODEL_ID = os.environ.get("MODEL_ID", "__MODEL_ID__")
MAX_TURNS = "__MAX_TURNS__"
PORT = 1234
WORK = "/kaggle/working"


def sh(cmd: str) -> None:
    print(f"$ {cmd}", flush=True)
    subprocess.run(cmd, shell=True, check=True)


def find_harness() -> str:
    hits = glob.glob("/kaggle/input/**/auralis_research.py", recursive=True)
    if not hits:
        raise SystemExit("AgentHarness dataset not attached")
    src = os.path.dirname(hits[0])
    dst = f"{WORK}/AgentHarness"
    shutil.copytree(src, dst, dirs_exist_ok=True)
    return dst


def resolve_model() -> str:
    """'auto' → the 4B Apodex checkpoint fits two T4s in fp16."""
    if MODEL_ID != "auto":
        return MODEL_ID
    with urllib.request.urlopen("https://huggingface.co/api/models?author=apodex&limit=100") as r:
        ids = [m["id"] for m in json.load(r)]
    print("apodex models:", ids, flush=True)
    for pref in ("4B", "2B"):
        for mid in ids:
            if pref in mid and "GGUF" not in mid.upper():
                return mid
    raise SystemExit(f"no suitable Apodex model in {ids}")


def wait_for_server(proc: subprocess.Popen, timeout: int = 1500) -> None:
    t0 = time.time()
    while time.time() - t0 < timeout:
        if proc.poll() is not None:
            raise SystemExit("vLLM exited early; see vllm.log")
        try:
            urllib.request.urlopen(f"http://localhost:{PORT}/v1/models", timeout=5)
            print(f"vLLM ready after {time.time() - t0:.0f}s", flush=True)
            return
        except Exception:
            time.sleep(10)
    raise SystemExit("vLLM did not start in time")


def main() -> None:
    harness = find_harness()
    sh("pip install -q vllm ddgs")
    sh(f"pip install -q -e {harness}")

    model = resolve_model()
    print("serving", model, flush=True)
    log = open(f"{WORK}/vllm.log", "w")
    server = subprocess.Popen(
        [sys.executable, "-m", "vllm.entrypoints.openai.api_server",
         "--model", model, "--served-model-name", "apodex",
         "--port", str(PORT), "--dtype", "half",
         "--tensor-parallel-size", "2", "--max-model-len", "32768",
         "--gpu-memory-utilization", "0.90",
         "--enable-auto-tool-choice", "--tool-call-parser", "qwen3_coder",
         "--reasoning-parser", "qwen3"],
        stdout=log, stderr=subprocess.STDOUT,
    )
    try:
        wait_for_server(server)
        env = dict(os.environ,
                   OPENAI_API_KEY="local", OPENAI_BASE_URL=f"http://localhost:{PORT}/v1",
                   OPENAI_MODEL="apodex", LLM_PROVIDER="openai",
                   SUMMARY_LLM_API_KEY="local", SUMMARY_LLM_BASE_URL=f"http://localhost:{PORT}/v1",
                   SUMMARY_LLM_MODEL_NAME="apodex",
                   SEARCH_BACKEND="ddgs", CODE_SANDBOX="subprocess",
                   AURALIS_MAX_TURNS=MAX_TURNS, AURALIS_MAX_INPUT_TOKENS="28000")
        qfile = f"{WORK}/questions.json"
        with open(qfile, "w", encoding="utf-8") as f:
            json.dump(QUESTIONS, f)
        subprocess.run(
            [sys.executable, "auralis_research.py", "--input", qfile,
             "--output", f"{WORK}/answers.json"],
            cwd=harness, env=env, check=False,
        )
    finally:
        server.terminate()
    shutil.rmtree(harness, ignore_errors=True)  # keep only results in output
    print(open(f"{WORK}/answers.json", encoding="utf-8").read()[:3000])


if __name__ == "__main__":
    main()
