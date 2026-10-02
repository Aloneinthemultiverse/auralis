# benchmarks/

Run LLM agents on public benchmarks. Each question runs in its own Python subprocess — no asyncio saturation, hangs can be SIGKILL'd, and any single question is independently reproducible.

## Supported benchmarks

Pipeline is `react_base` (see `workflows/react_base/`). Judges are organized by family under `benchmarks/judges/`.

| Key                          | Family            | Judge                       |
|------------------------------|-------------------|-----------------------------|
| `browsecomp`                 | BrowseComp        | `browsecomp`                |
| `browsecomp_zh`              | BrowseComp        | `browsecomp_zh`             |
| `xbench_dr_202510`           | xbench-DR         | `xbench`                    |
| `hle_text`                   | HLE               | `hle` (o3-mini pin)         |
| `superchem_text`             | MCQ (HLE schema)  | `hle`                       |
| `frontier_science_research`  | FrontierScience   | `frontier_science`          |
| `frontier_science_olympiad`  | FrontierScience   | `frontier_science_olympiad` |
| `deepsearchqa`               | DeepSearchQA      | `deepsearchqa`              |
| `widesearch`                 | WideSearch        | `widesearch` (structural F1)|

Full list = `benchmarks.core.registry.REGISTRY`.

## Run

```bash
uv sync
cp .env.example .env   # fill OPENAI_BASE_URL / OPENAI_API_KEY / OPENAI_MODEL / SERPER_API_KEY / JINA_API_KEY / E2B_API_KEY

# Smoke (1 question)
uv run python -m benchmarks.runner.run_subprocess \
  --benchmark browsecomp --pipeline react_base --profile default \
  --limit 1 --concurrency 1 \
  --out ./tmp/smoke

# Full run (5 parallel runs x 30 concurrency each)
uv run python -m benchmarks.runner.run_subprocess \
  --benchmark browsecomp --pipeline react_base --profile default \
  --runs 5 --concurrency 30 \
  --out ./bc-runs
```

## Check progress / aggregate accuracy

```bash
uv run python -m benchmarks.runner.check_progress ./bc-runs
```

Shows completed count, accuracy, ETA, and per-run distribution.

## Layout

```
benchmarks/
├── core/         shared infra (registry / question / kernel_adapter / answer_extractor / harbor_task_generator)
├── families/     DatasetConfig per family (one file per family, auto-aggregated)
├── judges/       LLM-as-judge (one file per family + _common.py shared client/retry)
├── runner/       CLI entry points (run_one_task / run_subprocess / check_progress)
├── harbor_agent/ Agent implementation invoked by the runner
├── datasets/     raw JSONL + attachments (one directory per dataset)
└── results/      run artifacts
```

## Add a new benchmark

### Step 1 — drop data into `benchmarks/datasets/<DirName>/`

```
benchmarks/datasets/<DirName>/standardized_data.jsonl    # required
benchmarks/datasets/<DirName>/<attachment>                # optional
```

JSONL fields:

| Field                  | Meaning                                                |
|------------------------|--------------------------------------------------------|
| `task_id`              | unique id                                              |
| `task_question`        | question text                                          |
| `ground_truth`         | reference answer                                       |
| `answer_type`          | `exactMatch` / `multipleChoice` / family-specific      |
| `image_path` *(opt.)*  | relative path                                          |
| `file_name` *(opt.)*   | relative path                                          |
| `category` *(opt.)*    | filter key                                             |

### Step 2 — register a `DatasetConfig` in `benchmarks/families/<family>.py`

Existing family: append a tuple to `CONFIGS`:

```python
("my_benchmark", DatasetConfig(
    name="MyBenchmark",
    key="MyBenchmark",                # = directory name under datasets/
    default_pipeline="react_base",
    **_STD_SCHEMA,
)),
```

New family: create `benchmarks/families/<family>.py` exporting `CONFIGS: list[tuple[str, DatasetConfig]]`. `families/__init__.py` auto-discovers it.

### Step 3 *(optional)* — add an LLM judge

Reuse an existing judge: add one line to `JUDGE_REGISTRY` in `benchmarks/judges/__init__.py`.

New judge: implement `async def verify_<name>(question, target, predicted) -> Verdict` in `benchmarks/judges/<family>.py`, then import + register in `benchmarks/judges/__init__.py`.
