uv run python -m benchmarks.runner.run_subprocess \
  --benchmark widesearch \
  --pipeline react_base \
  --profile default \
  --runs 1 \
  --concurrency 50 \
  --out ./results/$(date +%F)_widesearch


# uv run python -m benchmarks.runner.check_progress ./results/$(date +%F)_widesearch
