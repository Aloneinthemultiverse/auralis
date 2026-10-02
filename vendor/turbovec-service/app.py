"""TurboVec service for Auralis: a TurboQuant-compressed vector index over HTTP.

The Java core keeps chunk text and exact embeddings in Postgres; this service is the
fast, compressed nearest-neighbour stage (2-bit TurboQuant by default). Ids are the
chunk's numeric ``vec_id``. Scoped (notebook) searches pass an ``allowlist`` of ids.

Run:  .venv/Scripts/uvicorn app:app --host 127.0.0.1 --port 8100
"""
from __future__ import annotations

import os
import threading
from pathlib import Path

import numpy as np
import turbovec
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

DIM = int(os.environ.get("TURBOVEC_DIM", "768"))
BITS = int(os.environ.get("TURBOVEC_BITS", "2"))
PATH = Path(os.environ.get("TURBOVEC_PATH", Path(__file__).parent / "data" / "auralis.tvim"))
PATH.parent.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="Auralis TurboVec")
_lock = threading.Lock()


def _new_index() -> turbovec.IdMapIndex:
    try:
        return turbovec.IdMapIndex(DIM, BITS)
    except TypeError:
        return turbovec.IdMapIndex(dim=DIM, bit_width=BITS)


index = turbovec.IdMapIndex.load(str(PATH)) if PATH.exists() else _new_index()
count = 0


class AddRequest(BaseModel):
    ids: list[int]
    vectors: list[list[float]]


class SearchRequest(BaseModel):
    vector: list[float]
    k: int = 30
    allowlist: list[int] | None = None


class CalibrateRequest(BaseModel):
    vectors: list[list[float]]


def _matrix(rows: list[list[float]]) -> np.ndarray:
    m = np.asarray(rows, dtype=np.float32)
    if m.ndim != 2 or m.shape[1] != DIM:
        raise HTTPException(400, f"expected vectors of dimension {DIM}, got {m.shape}")
    norms = np.linalg.norm(m, axis=1, keepdims=True)
    return m / np.where(norms == 0, 1, norms)  # cosine similarity via unit vectors


@app.get("/health")
def health():
    return {"status": "ok", "dim": DIM, "bits": BITS, "path": str(PATH),
            "bytes": PATH.stat().st_size if PATH.exists() else 0}


@app.post("/calibrate")
def calibrate(req: CalibrateRequest):
    with _lock:
        index.calibrate(_matrix(req.vectors))
    return {"calibrated": len(req.vectors)}


@app.post("/add")
def add(req: AddRequest):
    if len(req.ids) != len(req.vectors):
        raise HTTPException(400, "ids and vectors differ in length")
    if not req.ids:
        return {"added": 0}
    ids = np.asarray(req.ids, dtype=np.uint64)
    with _lock:
        fresh = np.asarray([not index.contains(int(i)) for i in ids])
        if fresh.any():
            index.add_with_ids(_matrix(req.vectors)[fresh], ids[fresh])
            index.sync(str(PATH))
    return {"added": int(fresh.sum()), "skipped": int((~fresh).sum())}


@app.post("/remove")
def remove(ids: list[int]):
    with _lock:
        n = sum(1 for i in ids if index.remove(int(i)))
        index.sync(str(PATH))
    return {"removed": n}


@app.post("/search")
def search(req: SearchRequest):
    q = _matrix([req.vector])
    allow = None if req.allowlist is None else np.asarray(req.allowlist, dtype=np.uint64)
    if allow is not None and allow.size == 0:
        return {"ids": [], "scores": []}
    with _lock:
        scores, ids = index.search(q, req.k, allowlist=allow) if allow is not None else index.search(q, req.k)
    ids = np.asarray(ids)[0]
    scores = np.asarray(scores)[0]
    keep = ids != np.iinfo(np.uint64).max  # padding when fewer than k results exist
    return {"ids": [int(i) for i in ids[keep]], "scores": [float(s) for s in scores[keep]]}
