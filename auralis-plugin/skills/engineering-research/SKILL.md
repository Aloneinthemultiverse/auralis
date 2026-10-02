---
name: engineering-research
description: Investigate an engineering objective with Auralis — evidence-gated, multi-round research that plans questions, retrieves evidence, judges sufficiency with the Laya decision model, optionally escalates to deep web research, and returns a cited brief. Use when the user wants an engineering design question, trade-off, or specification researched before deciding, rather than answered from memory.
---

# Engineering research with Auralis

Auralis is an AI engineering scientist running locally. It researches until there is
enough evidence to make an engineering decision, and keeps a trace of why.

## When to use it

Use it when the user gives an engineering **objective, requirement or design problem**
("design an SRAM for an IoT sensor that minimises standby leakage", "which ECC scheme
fits a 32-bit MCU cache") and wants findings grounded in sources. For a quick lookup in
already-ingested documents, use the `engineering-knowledge` skill instead.

## Workflow

1. **Sharpen the objective first.** If the operating environment, budgets (power, area,
   cost), targets (reliability, performance) or constraints are missing and matter, ask
   the user for them before starting. A vague objective produces vague research.
2. **Check the knowledge base** with `knowledge_search` for the core topic. If it is
   thin, tell the user and offer to ingest documents (`knowledge_add_file`) or to use
   deep research.
3. **Start the session** with `research_start`:
   - `deepResearch`: `NONE` (knowledge base only, fastest), `LOCAL` (web research on this
     machine, minutes per question), `KAGGLE` (free Kaggle GPU with a stronger model; a
     batch takes 10+ minutes to boot). Default to `NONE` unless the user wants web
     research or the knowledge base is thin; say which you chose.
   - `maxRounds`: 2-3 is usually enough.
4. **Follow progress** with `research_status` every minute or two. Relay the important
   trace events briefly (questions planned, which ones were escalated, Laya's
   sufficiency verdict). Do not dump the whole trace.
5. **Report.** When status is `SUFFICIENT`, `EXHAUSTED` or `FAILED`, summarise the
   report for the user:
   - lead with the key findings and their numbers, keeping the `[n]` citations;
   - state clearly which questions are `UNRESOLVED` — these are open risks, not facts;
   - `EXHAUSTED` means the round budget ran out before the evidence was judged
     sufficient — say so.

## Reading the signals honestly

- Laya's probabilities (`P(answered)`, `sufficient`, `contradiction`) are **control
  signals from a small zero-shot model, not guarantees**. Do not present them as
  confidence in the engineering answer.
- Answers can only be as good as the evidence. If an answer cites nothing, or the
  sources do not support a number, point that out rather than repeating it.
- Deep-research answers come from a small model browsing the web; spot-check
  surprising numbers or citations before the user relies on them.

## If the tools are unavailable

The Auralis server must be running (`scripts/start-auralis.sh` in the Auralis repo).
If MCP calls fail to connect, tell the user to start it; do not fall back to answering
the research question from memory as if it were researched.
