---
name: engineering-knowledge
description: Search and grow the Auralis engineering knowledge base — hybrid vector + keyword retrieval over ingested papers, datasheets, standards, manuals and past research, with source titles for citation. Use when the user asks a factual engineering question that their own documents may answer, or wants documents, notes or results stored for later retrieval.
---

# Auralis engineering knowledge base

## Searching

Use `knowledge_search` with the user's question, or with exact terms (part numbers,
standard names, parameter names, units) — keyword matching catches identifiers that
semantic search blurs, so it is worth a second search with the precise term.

- Cite passages by their source title when you use them.
- A passage's `relevance` is a reranking signal, not proof; read the passage.
- If nothing relevant comes back, say the knowledge base does not cover it. Do not
  fill the gap from memory without saying so; offer to ingest a source or to run the
  `engineering-research` skill with deep research.

## Traversing the knowledge graph

Some knowledge is also a graph you can walk: `graph_find` locates a node by name, and
`graph_browse` opens it with its path from the root and its neighbours (with ids, so
you can keep walking). Namespaces so far:

- `osint` — the OSINT Framework catalog of free investigation sources (search engines,
  public records, archives, threat intelligence, geolocation, training…). Tool nodes
  carry URL, pricing, `opsec` (passive/active), input and output. Use it to find **which
  source provides a kind of data** for research.
  Categories about locating individuals (usernames, emails, phone numbers, people
  search, dating, social and messaging accounts) are marked `personLookup`. Do not use
  or recommend them to look up a person unless the user states a lawful, authorised
  purpose; never run such lookups on private individuals on your own initiative.

## Adding knowledge

- `knowledge_add_file` — a local PDF, DOCX, HTML, Markdown or text file. Pass an
  absolute path the Auralis server can read, and a `sourceType` (paper, datasheet,
  standard, manual, report).
- `knowledge_add_text` — notes, an excerpt, test results or a design decision. Give it
  a descriptive title and, when known, the `uri` it came from.

Ingestion is idempotent on content: re-adding identical text reports that it is already
present. Confirm with the user before ingesting large folders.
