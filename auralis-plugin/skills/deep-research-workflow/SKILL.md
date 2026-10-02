---
name: deep-research-workflow
description: The Auralis way to solve an engineering problem — problem statement → break into parts → gather books, papers, theses and open-source designs for EACH part (Laya decides coverage and relevance) → NotebookLM-style grounded study of the sources → only then design and verify. Use whenever the user gives a problem statement, asks to research a topic in depth, build a knowledge base, or design something new (chips, processors, accelerators, systems).
---

# Auralis deep-research workflow

The user is designing things that do not exist yet. That requires a deep, sourced
understanding of every part of the problem **before** any design. Never jump from a
problem statement straight to a design or an answer from memory.

## 0. Check the stack
Call `auralis_status`. If anything is down, tell the user to run `scripts/start-auralis.sh`
(Auralis repo) and stop; do not improvise the research from memory.

## 1. Problem → parts → sources per part
For a problem statement, call `problem_start` with the full statement (keep the user's
constraints verbatim; `sourcesPerPart` 8 by default, more for unfamiliar territory;
`mode: deep` or `deep-kaggle` when the topic is new or fast-moving).

Auralis decomposes it into 4-8 parts (subsystems, techniques, interfaces, constraints,
verification). For each part Laya judges whether the existing library already covers
it, then sources are discovered (arXiv, OpenAlex, web, GitHub designs), **Laya scores
each one**, and the best are imported under that part in a dedicated notebook.

Poll `problem_status` every 1-2 minutes. Report the breakdown to the user as soon as
it appears and summarise progress per part briefly. If a part ends with few imported
sources, run `notebook_discover` with a sharper query (or `mode: deep`) and
`notebook_import` the relevant results into the same notebook.

## 2. Study the sources (NotebookLM-style)
Before designing, build understanding from the notebook:
- `notebook_studio` type `design_brief` (requirements, candidate architectures,
  trade-offs, key numbers) and, for unfamiliar parts, `briefing` or `study_guide`
  with a `focus` on that part.
- `notebook_ask` for specific questions; it answers only from the sources with [n]
  citations and says when the sources do not cover something.
Share the key findings with citations. Gaps are research tasks, not guesses: discover
and import more sources for them.

## 3. Design from the evidence
When the problem is READY and the understanding is sufficient, call `problem_design`.
The design run is grounded part by part in the notebook's sources: requirements,
alternatives (Laya selects), RTL/model generation, automated validation (domain
adapter: e.g. Verilator/Icarus/Yosys for semiconductor), Laya verdict
PASS/FIX/RESEARCH/ASK_USER. Poll `design_status`; report alternatives with Laya scores,
the verification SUMMARY, and the files. Use `design_branch` for what-if questions.

## 4. Grow the library continuously
The library should keep growing. `corpus_status` shows bulk harvesting (whole arXiv
categories, Berkeley theses, open books, open-source designs); `corpus_build` with a
domain manifest starts one. `notebook_list` shows what exists.

## Laya
Laya makes the control decisions everywhere (coverage, relevance, routing, selection,
verdicts) and every decision is in the ledger (`decisions_recent`). Its scores are
signals from a small model, not proof: present them as such and keep the citations.

## Sources and honesty
Only legally free sources are collected (open access, open licenses, public domain,
author-distributed). Paid books: list them and ask the user to add copies they own with
`knowledge_add_file`. Always distinguish what the sources say from your own reasoning.
