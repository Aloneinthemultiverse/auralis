# Auralis — AI Engineering Scientist

Give Auralis an engineering objective; it plans research questions, gathers evidence
from its knowledge base and (optionally) deep web research, decides with a fast
decision model whether the evidence is enough, and returns a cited brief with a full
trace of why. Everything runs on free, local or free-tier infrastructure.

## Architecture

| Part | What | Tech |
|---|---|---|
| `auralis-core/` | Orchestration, hybrid RAG, research loop, knowledge graph, REST + MCP server | Java 21, Spring Boot 4, Spring AI 2 |
| Laya (`vendor/laya`, submodule) | Fast typed decisions: reranking, sufficiency, routing | Python, zero-shot, CPU |
| AgentHarness (`vendor/AgentHarness`) | Deep web research (ReAct: search, fetch, code) | Python, patched for free tools — see `AURALIS_CHANGES.md` |
| OSINT Framework (`vendor/osint-framework`, submodule) | Catalog of free investigation sources, imported as a traversable graph | JSON |
| `kaggle/` | Runs deep research on a free Kaggle T4 GPU with Apodex-1.0-4B | Kaggle CLI, vLLM |
| `auralis-plugin/` | Claude Code plugin: MCP connection, skills, `/auralis:research` | |

Models: Ollama `qwen3:4b` (16k context, `infra/ollama`) for reasoning, `nomic-embed-text`
for embeddings. Storage: PostgreSQL 17 + pgvector, Redis. Search: self-hosted SearXNG.

## Run

```bash
git clone --recurse-submodules <this repo>
docker compose up -d                       # postgres :5433, redis, searxng :8888
ollama pull qwen3:4b && ollama pull nomic-embed-text
cd vendor/laya && uv venv --python 3.12 && uv pip install -e ".[serve]" && cd ../..
cd vendor/AgentHarness && uv sync --python 3.12 && cd ../..
docker build -t auralis-pysandbox infra/pysandbox
scripts/start-auralis.sh                   # starts Ollama, Laya, core; `status` to check
```

Laya's checkpoint downloads from Hugging Face on first use; where Hugging Face is
blocked, use `vendor/laya/docker/prefetch_modelscope.py --model english` and run Laya
with `HF_HUB_OFFLINE=1` (the start script does).

## Use

- REST: `POST /api/knowledge/documents` (file), `POST /api/knowledge/search`,
  `POST /api/research` `{objective, maxRounds, deepResearch: NONE|LOCAL|KAGGLE}`,
  `GET /api/research/{id}`, `GET /api/graph/osint/root`, `GET /api/graph/nodes/{id}`.
- MCP at `http://localhost:8080/mcp`: `knowledge_search`, `knowledge_graph_search`, `knowledge_graph_status`, `knowledge_add_text`,
  `knowledge_add_file`, `research_start`, `research_status`, `graph_browse`, `graph_find`.
- Claude Code: `/plugin marketplace add <repo path>` then `/plugin install auralis@auralis-local`.

## 2-bit graph RAG

`knowledge_search` fuses three retrievers with RRF: TurboVec (2-bit dense), Postgres full
text, and a 2-bit relationship graph (`knowledge/graphvec`, pure Java). The graph links
chunks, documents and technical terms (AXI, CVA6, LPDDR4…) with MENTIONS / PART_OF / NEXT
edges; every node is a TurboQuant-style 2-bit code (randomized Hadamard rotation, Lloyd-Max
levels, per-vector gain) plus a 2-bit neighbourhood code, so one scan scores nodes on
themselves and on their neighbours. Hops are gated by the query shifted by a TransE relation
vector; Postgres re-scores the short list exactly. ~52k nodes take ~40 MB and ~40 s to build;
it rebuilds when the chunk count changes. `knowledge_graph_search` returns the relation path
behind each passage.

The chip-design UI is a separate app, **Auralis VSD** (`../auralis-vsd`), an MCP client of
this server and the semiconductor server.

## Honest limits

- Zero-shot Laya judges topic well but not whether a passage answers a specific
  question; answerability is decided by the LLM. Laya scores are control signals.
- On CPU, Laya takes ~2.5 s per call and reranked search ~13 s; deep research takes
  ~4-6 min per question. A 4B local model limits synthesis quality.
