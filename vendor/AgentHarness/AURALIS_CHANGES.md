# Auralis modifications to AgentHarness

Vendored from https://github.com/ApodexAI/AgentHarness (Apache-2.0, see `LICENSE`).
Changed so it runs on free infrastructure and can be driven by the Auralis Java core:

- `plugins/tools/web_search.py` — free backends: self-hosted SearXNG (`SEARXNG_URL`)
  and keyless DuckDuckGo (`SEARCH_BACKEND=ddgs`), mapped to the Serper result shape.
- `plugins/tools/run_python_code.py` — free sandboxes: local Docker container
  (`CODE_SANDBOX=docker`, no network, capped CPU/memory) or a plain subprocess on an
  already-isolated host such as a Kaggle VM (`CODE_SANDBOX=subprocess`).
- `workflows/react_base/profiles/auralis.yaml` — profile sized for small GPUs / Ollama.
- `auralis_research.py` — entry point: free-form questions in, JSON answers with
  sources out (no benchmark dataset, ground truth or judge needed).

Serper and E2B remain supported when their keys are set.
