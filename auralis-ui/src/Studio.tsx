import { useEffect, useState } from 'react'
import { api, type Agreement, type Citation, type DecisionEntry, type GraphLink, type NodeView, type RunSummary, type StudioDoc } from './api'
import { Markdown } from './Markdown'
import { ProblemPanel } from './Problem'

type Tab = 'problem' | 'studio' | 'map' | 'runs' | 'decisions'

/** Right column: generated documents, mind map, research/design runs, Laya's decision ledger. */
export function StudioPanel(props: {
  notebook: string
  selected: string[]
  onCite: (c: Citation) => void
  runRequest: { kind: 'research' | 'design'; objective: string; nonce: number } | null
  onNotebook: (nb: string) => void
  className: string
}) {
  const [tab, setTab] = useState<Tab>('problem')
  const [openDesign, setOpenDesign] = useState<string | null>(null)
  useEffect(() => { if (props.runRequest) setTab('runs') }, [props.runRequest])

  return (
    <section className={`panel ${props.className}`}>
      <div className="panel-head"><h2>Studio</h2></div>
      <div className="studio-tabs">
        {(['problem', 'studio', 'map', 'runs', 'decisions'] as Tab[]).map(t => (
          <button key={t} className={`tab ${tab === t ? 'active' : ''}`} onClick={() => setTab(t)}>
            {t === 'problem' ? 'Problem' : t === 'studio' ? 'Create' : t === 'map' ? 'Mind map' : t === 'runs' ? 'Research & design' : 'Laya decisions'}
          </button>
        ))}
      </div>
      <div className="scroll">
        <div className="studio-body">
          {tab === 'problem' && <ProblemPanel onNotebook={props.onNotebook}
            onDesign={id => { setOpenDesign(id); setTab('runs') }} />}
          {tab === 'studio' && <Create notebook={props.notebook} selected={props.selected} onCite={props.onCite} />}
          {tab === 'map' && <MindMap notebook={props.notebook} />}
          {tab === 'runs' && <Runs request={props.runRequest} openDesign={openDesign} />}
          {tab === 'decisions' && <Decisions />}
        </div>
      </div>
    </section>
  )
}

const DOCS = [
  { type: 'briefing', title: 'Briefing', desc: 'Key findings, numbers, open questions' },
  { type: 'faq', title: 'FAQ', desc: 'Questions an engineer would ask' },
  { type: 'study_guide', title: 'Study guide', desc: 'Concepts, examples, self-test' },
  { type: 'design_brief', title: 'Design brief', desc: 'Requirements, architectures, trade-offs' },
]

function Create({ notebook, selected, onCite }: { notebook: string; selected: string[]; onCite: (c: Citation) => void }) {
  const [focus, setFocus] = useState('')
  const [busy, setBusy] = useState('')
  const [doc, setDoc] = useState<StudioDoc | null>(null)
  const [err, setErr] = useState('')

  const make = async (type: string) => {
    setBusy(type); setErr('')
    try { setDoc(await api.studio(notebook, type, focus, selected)) } catch (e) { setErr(String(e)) } finally { setBusy('') }
  }

  return (
    <>
      <input className="input" placeholder="Focus (optional), e.g. KV-cache compression" value={focus} onChange={e => setFocus(e.target.value)} />
      <div className="tiles">
        {DOCS.map(d => (
          <button key={d.type} className="tile" onClick={() => make(d.type)} disabled={!!busy}>
            <b>{d.title}</b><span>{busy === d.type ? 'Writing…' : d.desc}</span>
          </button>
        ))}
      </div>
      {busy && <div className="thinking"><span className="spinner" />Gathering passages and writing a cited {busy.replace('_', ' ')}…</div>}
      {err && <div className="flag warn">{err}</div>}
      {doc && (
        <div className="card">
          <h4>{doc.title}</h4>
          <Markdown text={doc.markdown} onCite={n => { const c = doc.citations.find(x => x.n === n); if (c) onCite(c) }} />
        </div>
      )}
    </>
  )
}

function MindMap({ notebook }: { notebook: string }) {
  const [root, setRoot] = useState<NodeView | null>(null)
  const [err, setErr] = useState('')
  useEffect(() => {
    setRoot(null); setErr('')
    if (notebook === 'all') { setErr('Pick a notebook to see its map.'); return }
    api.graphRoot(notebook).then(setRoot).catch(() => setErr('This notebook has no map yet.'))
  }, [notebook])
  if (err) return <div className="muted small">{err}</div>
  if (!root) return <div className="thinking"><span className="spinner" />Loading map…</div>
  return (
    <div className="card">
      <div className="small muted" style={{ marginBottom: 6 }}>Walk the knowledge graph: topics → works, repos → files, citations between papers.</div>
      <ul className="tree root"><TreeNode view={root} depth={0} /></ul>
    </div>
  )
}

function TreeNode({ view, depth }: { view: NodeView; depth: number }) {
  const [open, setOpen] = useState(depth === 0)
  const [kids, setKids] = useState<GraphLink[] | null>(depth === 0 ? children(view) : null)
  const toggle = async () => {
    if (!open && kids === null) setKids(children(await api.graphNode(view.node.id)))
    setOpen(o => !o)
  }
  const n = view.node
  return (
    <li>
      <button className="node" onClick={toggle} title={n.kind}>
        <span className="caret">{open ? '▾' : '▸'}</span>
        <span className={`badge ${n.kind}`}>{n.kind.toLowerCase()}</span>
        <span>{n.name.length > 70 ? n.name.slice(0, 70) + '…' : n.name}</span>
      </button>
      {n.uri && <a className="small" href={n.uri} target="_blank" rel="noreferrer" style={{ marginLeft: 6 }}>open</a>}
      {open && kids && kids.length > 0 && (
        <ul className="tree">
          {kids.slice(0, 80).map(l => (
            <TreeNode key={l.node.id + l.rel} view={{ node: l.node, path: [], links: [] }} depth={depth + 1} />
          ))}
          {kids.length > 80 && <li className="small muted">… {kids.length - 80} more</li>}
        </ul>
      )}
    </li>
  )
}

function children(v: NodeView): GraphLink[] {
  return v.links.filter(l => l.direction === 'out')
}

function Runs({ request, openDesign }: { request: { kind: 'research' | 'design'; objective: string; nonce: number } | null; openDesign: string | null }) {
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [objective, setObjective] = useState('')
  const [kind, setKind] = useState<'research' | 'design'>('research')
  const [deep, setDeep] = useState('NONE')
  const [open, setOpen] = useState<RunSummary | null>(null)
  const [detail, setDetail] = useState<any>(null)

  const load = () => api.runs().then(setRuns).catch(() => {})
  useEffect(() => { load(); const t = window.setInterval(load, 8000); return () => window.clearInterval(t) }, [])
  useEffect(() => { if (request) { setKind(request.kind); setObjective(request.objective) } }, [request])
  useEffect(() => { if (openDesign) setOpen({ id: openDesign, kind: 'design', title: '', status: 'RUNNING', at: '' }) }, [openDesign])
  useEffect(() => {
    if (!open) return
    const get = () => (open.kind === 'research' ? api.research(open.id) : api.design(open.id)).then(setDetail).catch(() => {})
    get(); const t = window.setInterval(get, 6000); return () => window.clearInterval(t)
  }, [open])

  const start = async () => {
    const r = kind === 'research' ? await api.startResearch(objective, deep, 2) : await api.startDesign(objective, null, 3)
    setObjective(''); await load()
    setOpen({ id: r.id, kind, title: objective, status: 'RUNNING', at: '' })
  }

  return (
    <>
      <div className="card" style={{ display: 'grid', gap: 8 }}>
        <div className="row">
          <button className={`tab ${kind === 'research' ? 'active' : ''}`} onClick={() => setKind('research')}>Research</button>
          <button className={`tab ${kind === 'design' ? 'active' : ''}`} onClick={() => setKind('design')}>Design (RTL)</button>
        </div>
        <textarea className="input" rows={3} value={objective} onChange={e => setObjective(e.target.value)}
          placeholder={kind === 'research' ? 'Research objective…' : 'What to design, with interfaces and constraints…'} />
        <div className="row">
          {kind === 'research' && (
            <select className="input" value={deep} onChange={e => setDeep(e.target.value)} style={{ width: 'auto' }}>
              <option value="NONE">Knowledge base only</option>
              <option value="LOCAL">+ deep web research (local)</option>
              <option value="KAGGLE">+ deep research on Kaggle GPU</option>
            </select>
          )}
          <span style={{ flex: 1 }} />
          <button className="btn primary" onClick={start} disabled={!objective.trim()}>Start</button>
        </div>
      </div>

      {open && detail && <RunDetail run={open} detail={detail} onClose={() => { setOpen(null); setDetail(null) }} />}

      <div className="card" style={{ padding: 6 }}>
        {runs.length === 0 && <div className="small muted" style={{ padding: 8 }}>No runs yet.</div>}
        {runs.map(r => (
          <div key={r.id} className="run" onClick={() => { setDetail(null); setOpen(r) }}>
            <div className="row"><span className="badge">{r.kind}</span><span className={`status ${r.status}`}>{r.status}</span><span className="small muted">{r.at}</span></div>
            <div className="t">{r.title}</div>
          </div>
        ))}
      </div>
    </>
  )
}

function RunDetail({ run, detail, onClose }: { run: RunSummary; detail: any; onClose: () => void }) {
  const isDesign = run.kind === 'design'
  const status = isDesign ? detail.design.status : detail.session.status
  const trace = (detail.trace ?? []).map((e: any) => `${e.at} ${isDesign ? 'i' + e.iteration : 'r' + e.round} ${e.type}: ${e.message}`).join('\n')
  const versions = isDesign ? detail.versions : []
  const last = versions[versions.length - 1]
  return (
    <div className="card" style={{ display: 'grid', gap: 8 }}>
      <div className="row"><b style={{ flex: 1 }}>{isDesign ? 'Design' : 'Research'} run</b><span className={`status ${status}`}>{status}</span><button className="btn small" onClick={onClose}>Close</button></div>
      <div className="small">{isDesign ? detail.design.objective : detail.session.objective}</div>
      <div className="trace">{trace || 'Starting…'}</div>
      {isDesign && detail.alternatives?.length > 0 && (
        <div className="small">
          <b>Alternatives</b> (Laya fit)
          {detail.alternatives.map((a: any) => <div key={a.id}>{a.selected ? '★ ' : '· '}{a.name}{a.fitScore != null ? ` — ${Math.round(a.fitScore * 100)}%` : ''}</div>)}
        </div>
      )}
      {last && (
        <>
          <div className="small"><b>{last.label}</b> verdict: <span className={`status ${last.verdict === 'PASS' ? 'VERIFIED' : 'EXHAUSTED'}`}>{last.verdict ?? 'pending'}</span></div>
          {Object.entries(last.artifacts as Record<string, string>).map(([name, code]) => (
            <details key={name}><summary className="small"><code>{name}</code></summary><pre className="files">{code}</pre></details>
          ))}
          {last.verification && <details><summary className="small">Verification output</summary><pre className="files">{last.verification}</pre></details>}
        </>
      )}
      {!isDesign && detail.session.report && <Markdown text={detail.session.report} />}
    </div>
  )
}

function Decisions() {
  const [rows, setRows] = useState<DecisionEntry[]>([])
  const [agree, setAgree] = useState<Agreement[]>([])
  useEffect(() => {
    const load = () => { api.decisions(80).then(setRows).catch(() => {}); api.agreement().then(setAgree).catch(() => {}) }
    load(); const t = window.setInterval(load, 10000); return () => window.clearInterval(t)
  }, [])
  return (
    <>
      <div className="card">
        <h4>Laya vs LLM agreement</h4>
        <div className="small muted" style={{ marginBottom: 6 }}>Every control decision goes through Laya first. Where the LLM also judged, agreement shows how far Laya can be trusted alone.</div>
        <table className="ledger"><thead><tr><th>Decision</th><th>Count</th><th>Agreed</th></tr></thead>
          <tbody>{agree.map(a => <tr key={a.kind}><td>{a.kind}</td><td>{a.decisions}</td><td>{a.compared ? `${Math.round(100 * a.agreed / a.compared)}% of ${a.compared}` : '—'}</td></tr>)}</tbody>
        </table>
      </div>
      <div className="card">
        <h4>Recent decisions</h4>
        <table className="ledger"><thead><tr><th>When</th><th>Decision</th><th>Answer</th><th>By</th></tr></thead>
          <tbody>{rows.map(r => (
            <tr key={r.id} title={r.question}>
              <td className="muted">{r.at.slice(11)}</td>
              <td>{r.kind}</td>
              <td>{r.finalAnswer.length > 40 ? r.finalAnswer.slice(0, 40) + '…' : r.finalAnswer}{r.layaP != null && r.decidedBy !== 'LAYA' ? <span className="muted"> (Laya: {r.layaAnswer})</span> : null}</td>
              <td>{r.decidedBy}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
    </>
  )
}
