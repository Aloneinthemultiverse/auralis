uv run python -m benchmarks.runner.run_subprocess \
  --benchmark browsecomp_zh \
  --pipeline react_base \
  --profile keep5 \
  --runs 1 \
  --concurrency 50 \
  --out ./results/$(date +%F)_browsecomp_zh


# uv run python -m benchmarks.runner.check_progress ./results/$(date +%F)_browsecomp_zh
