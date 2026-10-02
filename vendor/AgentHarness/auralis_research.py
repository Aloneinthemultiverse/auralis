#!/usr/bin/env python3
"""Auralis entry point: run AgentHarness deep research on free-form questions.

Unlike ``benchmarks/runner``, this needs no dataset, ground truth or judge.

Usage:
    python auralis_research.py --question "..."            # one question
    python auralis_research.py --input questions.json      # batch
    python auralis_research.py --input q.json --output answers.json

``questions.json`` is a list of strings or of ``{"id": ..., "question": ...}``.
Output is a JSON list of ``{id, question, answer, content, sources,
duration_seconds, error}``; with no ``--output`` it goes to stdout, so a
caller (the Auralis Java core) can read it directly.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv  # noqa: E402

load_dotenv(ROOT / ".env", override=False)

# Logs go to stderr so stdout stays pure JSON.
logging.basicConfig(
    level=logging.WARNING, stream=sys.stderr,
    format="%(asctime)s %(levelname)-7s %(name)s — %(message)s",
)

_URL_RE = re.compile(r"https?://[^\s\"'<>)\]\\]+")  # \\ stops at JSON-escaped \n


def _load_questions(args: argparse.Namespace) -> list[dict]:
    if args.question:
        raw = [args.question]
    else:
        raw = json.loads(Path(args.input).read_text(encoding="utf-8"))
    out = []
    for i, q in enumerate(raw):
        if isinstance(q, str):
            out.append({"id": f"q{i}", "question": q})
        else:
            out.append({"id": str(q.get("id", f"q{i}")), "question": q["question"]})
    return out


def _sources(state: dict) -> list[str]:
    """URLs the agent actually touched, in first-seen order."""
    seen: dict[str, None] = {}
    for step in state.get("react_steps") or []:
        for url in _URL_RE.findall(json.dumps(step, ensure_ascii=False, default=str)):
            seen.setdefault(url.rstrip(".,;"), None)
    return list(seen)


async def _run(questions: list[dict], profile: str) -> list[dict]:
    from benchmarks.core.kernel_adapter import BenchmarkSession

    results = []
    async with BenchmarkSession() as session:
        for q in questions:
            t0 = time.monotonic()
            row = {"id": q["id"], "question": q["question"], "answer": "",
                   "content": "", "sources": [], "error": None}
            try:
                state = await session.run(
                    q["question"], meta={"profile": profile}, pipeline_id="react_base",
                )
                row["answer"] = (state.get("final_answer") or "").strip()
                row["content"] = (state.get("final_content") or "").strip()
                row["sources"] = _sources(state)
            except Exception as exc:  # noqa: BLE001 — report per question, keep going
                row["error"] = f"{type(exc).__name__}: {exc}"
            row["duration_seconds"] = round(time.monotonic() - t0, 2)
            results.append(row)
            print(f"[auralis] {q['id']} done in {row['duration_seconds']}s", file=sys.stderr)
    return results


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--question")
    src.add_argument("--input")
    p.add_argument("--output")
    p.add_argument("--profile", default="auralis")
    args = p.parse_args()

    results = asyncio.run(_run(_load_questions(args), args.profile))
    payload = json.dumps(results, indent=2, ensure_ascii=False)
    if args.output:
        Path(args.output).write_text(payload, encoding="utf-8")
    else:
        sys.stdout.write(payload + "\n")
    return 0 if all(r["error"] is None for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
