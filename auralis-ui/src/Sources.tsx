import { useEffect, useRef, useState } from 'react'
import { api, type Source } from './api'

export interface Found {
  id: string; kind: string; title: string; url: string; pdfUrl: string | null; summary: string
  origin: string; year: number | null; citedBy: number; relevance: number | null
}

/** Left column: the notebook's sources, plus NotebookLM-style "Discover sources". */
export function SourcesPanel(props: {
  notebook: string
  selected: Set<string>
  setSelected: (s: Set<string>) => void
  onOpen: (s: Source) => void
  refreshKey: number
  onImported: () => void
  className: string
}) {
  const { notebook, selected, setSelected } = props
  const [sources, setSources] = useState<Source[]>([])
  const [filter, setFilter] = useState('')
  const [discovering, setDiscovering] = useState(false)
  const [uploading, setUploading] = useState('')
  const fileRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    let live = true
    api.sources(notebook, filter).then(s => live && setSources(s)).catch(() => live && setSources([]))
    return () => { live = false }
  }, [notebook, filter, props.refreshKey])

  const toggle = (id: string) => {
    const next = new Set(selected)
    next.has(id) ? next.delete(id) : next.add(id)
    setSelected(next)
  }

  const upload = async (files: FileList | null) => {
    if (!files) return
    for (const f of Array.from(files)) {
      setUploading(`Adding ${f.name}…`)
      try { await api.upload(f, 'document') } catch (e) { setUploading(`Failed: ${f.name}`) }
    }
    setUploading('')
    props.onImported()
  }

  return (
    <section className={`panel ${props.className}`}>
      <div className="panel-head">
        <h2>Sources</h2>
        <span className="spacer" />
        <button className="btn small primary" onClick={() => setDiscovering(d => !d)}>
          {discovering ? 'Close' : '✦ Discover'}
        </button>
      </div>

      {discovering ? (
        <Discover notebook={notebook} onImported={() => { setDiscovering(false); props.onImported() }} />
      ) : (
        <>
          <div className="src-tools">
            <input className="input" placeholder="Filter sources" value={filter} onChange={e => setFilter(e.target.value)} />
          </div>
          <div className="src-tools">
            <span>{sources.length} shown · {selected.size === 0 ? 'chat uses all' : `${selected.size} selected`}</span>
            <span className="spacer" style={{ flex: 1 }} />
            {selected.size > 0 && <button className="btn small" onClick={() => setSelected(new Set())}>Clear</button>}
          </div>
          <div className="scroll">
            <ul className="src-list">
              {sources.map(s => (
                <li key={s.documentId} className="src" title={s.title}>
                  <input type="checkbox" checked={selected.has(s.documentId)} onChange={() => toggle(s.documentId)}
                    aria-label={`Use ${s.title}`} />
                  <div onClick={() => props.onOpen(s)}>
                    <div className="t">{s.title}</div>
                    <div className="meta">
                      <span className={`badge ${s.kind}`}>{label(s.kind, s.sourceType)}</span>
                      <span>{s.chunks} passages</span>
                    </div>
                  </div>
                </li>
              ))}
              {sources.length === 0 && (
                <li className="muted small" style={{ padding: 12 }}>
                  No sources yet. Use <b>Discover</b> to find papers, web pages and open-source designs for this notebook.
                </li>
              )}
            </ul>
          </div>
          <div className="drop" onClick={() => fileRef.current?.click()}>
            {uploading || '+ Add your own file (PDF, DOCX, HTML, MD)'}
            <input ref={fileRef} type="file" multiple hidden onChange={e => upload(e.target.files)} />
          </div>
        </>
      )}
    </section>
  )
}

function label(kind: string, type: string) {
  if (kind === 'WORK') return type === 'paper' ? 'paper' : type
  if (kind === 'PART' || kind === 'BOOK') return 'book'
  if (kind === 'RTL') return 'rtl'
  if (kind === 'DOC') return 'design doc'
  return type || kind
}

function Discover({ notebook, onImported }: { notebook: string; onImported: () => void }) {
  const [query, setQuery] = useState('')
  const [mode, setMode] = useState<'fast' | 'deep' | 'deep-kaggle'>('fast')
  const [job, setJob] = useState<{ status: string; phase: string; sources: Found[]; report?: string } | null>(null)
  const [pick, setPick] = useState<Set<string>>(new Set())
  const [importing, setImporting] = useState<{ done: number; failed: number; total: number; status: string } | null>(null)
  const [error, setError] = useState('')
  const timer = useRef<number | undefined>(undefined)

  useEffect(() => () => window.clearInterval(timer.current), [])

  const run = async () => {
    if (!query.trim()) return
    setError(''); setJob({ status: 'RUNNING', phase: 'searching', sources: [] }); setPick(new Set())
    try {
      const { jobId } = await post<{ jobId: string }>(`/api/notebooks/${encodeURIComponent(notebook)}/discover`, { query, mode })
      window.clearInterval(timer.current)
      timer.current = window.setInterval(async () => {
        const j = await fetch(`/api/discover/${jobId}`).then(r => r.json())
        setJob(j)
        if (j.status !== 'RUNNING') {
          window.clearInterval(timer.current)
          setPick(new Set((j.sources as Found[]).filter(s => (s.relevance ?? 0) >= 0.5).slice(0, 15).map(s => s.id)))
        }
      }, 2500)
    } catch (e) { setError(String(e)); setJob(null) }
  }

  const doImport = async () => {
    if (!job) return
    const chosen = job.sources.filter(s => pick.has(s.id))
    const { jobId } = await post<{ jobId: string }>(`/api/notebooks/${encodeURIComponent(notebook)}/import`, { sources: chosen })
    setImporting({ done: 0, failed: 0, total: chosen.length, status: 'RUNNING' })
    const t = window.setInterval(async () => {
      const j = await fetch(`/api/imports/${jobId}`).then(r => r.json())
      setImporting({ done: j.done, failed: j.failed, total: j.total, status: j.status })
      if (j.status !== 'RUNNING') { window.clearInterval(t); onImported() }
    }, 2000)
  }

  const toggle = (id: string) => { const n = new Set(pick); n.has(id) ? n.delete(id) : n.add(id); setPick(n) }

  return (
    <div className="scroll" style={{ padding: '0 14px 14px', display: 'grid', gap: 10, alignContent: 'start' }}>
      <div className="muted small">Describe what you want to research. Auralis searches papers (arXiv, OpenAlex), the web and open-source designs (GitHub); Laya ranks every source by relevance.</div>
      <textarea className="input" rows={3} placeholder="e.g. LLM inference accelerators: memory bandwidth, KV-cache, systolic arrays"
        value={query} onChange={e => setQuery(e.target.value)}
        onKeyDown={e => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) run() }} />
      <div className="row">
        {(['fast', 'deep', 'deep-kaggle'] as const).map(m => (
          <button key={m} className={`tab ${mode === m ? 'active' : ''}`} onClick={() => setMode(m)}>
            {m === 'fast' ? 'Fast' : m === 'deep' ? 'Deep' : 'Deep · Kaggle GPU'}
          </button>
        ))}
        <span style={{ flex: 1 }} />
        <button className="btn primary" onClick={run} disabled={!query.trim() || job?.status === 'RUNNING'}>Search</button>
      </div>
      <div className="muted small">
        {mode === 'fast' ? 'About a minute.' : mode === 'deep' ? 'Adds an agentic web research report (several minutes, local).'
          : 'Deep research on a free Kaggle GPU with Apodex-4B (10+ minutes).'}
      </div>
      {error && <div className="flag warn">{error}</div>}
      {job && (
        <>
          <div className="thinking">{job.status === 'RUNNING' && <span className="spinner" />}{job.phase}</div>
          {job.report && (
            <details className="card"><summary><b>Deep research report</b></summary><div className="small" style={{ whiteSpace: 'pre-wrap', marginTop: 8 }}>{job.report}</div></details>
          )}
          {job.sources.length > 0 && (
            <div className="row small muted">
              <span>{job.sources.length} found · {pick.size} selected</span>
              <span style={{ flex: 1 }} />
              <button className="btn small" onClick={() => setPick(new Set(job.sources.map(s => s.id)))}>All</button>
              <button className="btn small" onClick={() => setPick(new Set())}>None</button>
            </div>
          )}
          <ul className="src-list" style={{ padding: 0 }}>
            {job.sources.map(s => (
              <li key={s.id} className="src" style={{ gridTemplateColumns: '18px 1fr' }}>
                <input type="checkbox" checked={pick.has(s.id)} onChange={() => toggle(s.id)} aria-label={`Select ${s.title}`} />
                <div>
                  <a className="t" href={s.url} target="_blank" rel="noreferrer" style={{ color: 'inherit', textDecoration: 'none', fontWeight: 550 }}>{s.title}</a>
                  <div className="meta">
                    <span className={`badge ${s.kind === 'paper' ? 'WORK' : s.kind === 'repo' ? 'RTL' : ''}`}>{s.kind}</span>
                    <span>{s.origin}{s.year ? ` · ${s.year}` : ''}{s.citedBy ? ` · ${s.citedBy} cites` : ''}</span>
                  </div>
                  {s.relevance != null && (
                    <div className="row" style={{ marginTop: 4 }} title="Laya relevance">
                      <div className="bar" style={{ flex: 1 }}><i style={{ width: `${Math.round(s.relevance * 100)}%` }} /></div>
                      <span className="small muted">{Math.round(s.relevance * 100)}%</span>
                    </div>
                  )}
                  {s.summary && <div className="small muted" style={{ marginTop: 4 }}>{s.summary.slice(0, 260)}{s.summary.length > 260 ? '…' : ''}</div>}
                </div>
              </li>
            ))}
          </ul>
          {job.status !== 'RUNNING' && job.sources.length > 0 && (
            importing ? (
              <div className="card small">
                {importing.status === 'RUNNING' ? <span className="thinking"><span className="spinner" />Importing</span> : 'Imported'} {importing.done}/{importing.total}
                {importing.failed > 0 && ` · ${importing.failed} could not be downloaded (paywalled or blocked)`}
              </div>
            ) : (
              <button className="btn primary" onClick={doImport} disabled={pick.size === 0}>Import {pick.size} source{pick.size === 1 ? '' : 's'}</button>
            )
          )}
        </>
      )}
    </div>
  )
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
  if (!res.ok) throw new Error(await res.text())
  return res.json()
}
