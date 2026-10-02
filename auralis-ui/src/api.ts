// Typed client for the Auralis core REST API.

export interface Notebook { id: string; title: string; kind: string; sources: number; chunks: number }
export interface Source { documentId: string; title: string; sourceType: string; uri: string | null; kind: string; chunks: number }
export interface Citation { n: number; documentId: string; title: string; uri: string | null; excerpt: string; relevance: number | null }
export interface Answer { answer: string; grounded: boolean; missing: string | null; route: string; citations: Citation[]; decisionId: string }
export interface StudioDoc { type: string; title: string; markdown: string; citations: Citation[] }
export interface RunSummary { id: string; kind: 'research' | 'design'; title: string; status: string; at: string }
export interface GraphNode { id: string; kind: string; name: string; namespace: string; externalId: string; uri: string | null; documentId: string | null; properties: string }
export interface GraphLink { rel: string; direction: 'in' | 'out'; node: GraphNode }
export interface NodeView { node: GraphNode; path: GraphNode[]; links: GraphLink[] }
export interface DecisionEntry { id: string; at: string; scope: string; kind: string; policy: string; question: string; layaAnswer: string | null; layaP: number | null; llmAnswer: string | null; finalAnswer: string; decidedBy: string; rationale: string | null; latencyMs: number }
export interface Agreement { kind: string; decisions: number; compared: number; agreed: number }

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: init?.body instanceof FormData ? init.headers : { 'content-type': 'application/json', ...init?.headers },
  })
  if (!res.ok) {
    throw new Error((await res.text()) || `${res.status} ${res.statusText}`)
  }
  const type = res.headers.get('content-type') ?? ''
  return (type.includes('json') ? res.json() : res.text()) as Promise<T>
}

const post = <T>(path: string, body: unknown) => call<T>(path, { method: 'POST', body: JSON.stringify(body) })

export const api = {
  status: () => call<string>('/api/status'),
  notebooks: () => call<Notebook[]>('/api/notebooks'),
  sources: (nb: string, q = '', limit = 300) =>
    call<Source[]>(`/api/notebooks/${encodeURIComponent(nb)}/sources?limit=${limit}&q=${encodeURIComponent(q)}`),
  ask: (nb: string, question: string, documentIds: string[]) =>
    post<Answer>(`/api/notebooks/${encodeURIComponent(nb)}/ask`, { question, documentIds }),
  studio: (nb: string, type: string, focus: string, documentIds: string[]) =>
    post<StudioDoc>(`/api/notebooks/${encodeURIComponent(nb)}/studio`, { type, focus, documentIds }),
  upload: (file: File, sourceType: string) => {
    const form = new FormData()
    form.append('file', file)
    form.append('sourceType', sourceType)
    return call<{ documentId: string; chunks: number; alreadyPresent: boolean }>('/api/knowledge/documents', { method: 'POST', body: form })
  },
  graphRoot: (ns: string) => call<NodeView>(`/api/graph/${encodeURIComponent(ns)}/root`),
  graphNode: (id: string) => call<NodeView>(`/api/graph/nodes/${id}`),
  runs: () => call<RunSummary[]>('/api/runs'),
  startResearch: (objective: string, deepResearch: string, maxRounds: number) =>
    post<{ id: string }>('/api/research', { objective, deepResearch, maxRounds }),
  research: (id: string) => call<any>(`/api/research/${id}`),
  startDesign: (objective: string, researchSessionId: string | null, maxIterations: number) =>
    post<{ id: string }>('/api/designs', { objective, researchSessionId, maxIterations }),
  design: (id: string) => call<any>(`/api/designs/${id}`),
  decisions: (limit = 60) => call<DecisionEntry[]>(`/api/decisions?limit=${limit}`),
  agreement: () => call<Agreement[]>('/api/decisions/agreement'),
  corpusJobs: () => call<string>('/api/corpus/jobs'),
}
