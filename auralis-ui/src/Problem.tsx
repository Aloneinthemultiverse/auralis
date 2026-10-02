import { useEffect, useState } from 'react'

interface PartProgress { name: string; status: string; coveredByExisting: boolean; coverageP: number | null; found: number; kept: number; imported: number; failed: number; sources: string[] }
interface Part { name: string; description: string; kind: string; queries: string[] }
interface ProblemJob {
  id: string; notebook: string; statement: string; title: string | null; status: string; phase: string
  breakdown: { title: string; parts: Part[] } | null; parts: Record<string, PartProgress>; designId: string | null
}

/** Problem statement → parts → sources per part → design, with live progress. */
export function ProblemPanel({ onNotebook, onDesign }: { onNotebook: (nb: string) => void; onDesign: (designId: string) => void }) {
  const [statement, setStatement] = useState('')
  const [perPart, setPerPart] = useState(8)
  const [mode, setMode] = useState('fast')
  const [jobId, setJobId] = useState<string | null>(() => sessionStorage.getItem('auralis.problem'))
  const [job, setJob] = useState<ProblemJob | null>(null)

  useEffect(() => {
    if (!jobId) return
    const load = () => fetch(`/api/problems/${jobId}`).then(r => r.ok ? r.json() : null).then(j => j && setJob(j)).catch(() => {})
    load(); const t = window.setInterval(load, 4000); return () => window.clearInterval(t)
  }, [jobId])

  const start = async () => {
    const r = await fetch('/api/problems', { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ statement, sourcesPerPart: perPart, mode }) }).then(r => r.json())
    setJobId(r.id); try { sessionStorage.setItem('auralis.problem', r.id) } catch { /* ignore */ }
    onNotebook(r.notebook)
  }

  const design = async () => {
    if (!job) return
    const r = await fetch(`/api/problems/${job.id}/design`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' }).then(r => r.json())
    onDesign(r.designId)
  }

  return (
    <>
      <div className="card" style={{ display: 'grid', gap: 8 }}>
        <h4>Solve a problem</h4>
        <div className="small muted">Auralis breaks the problem into parts, gathers books, papers and open-source designs for each part (Laya decides coverage and relevance), then designs from that evidence.</div>
        <textarea className="input" rows={4} value={statement} onChange={e => setStatement(e.target.value)}
          placeholder="e.g. Design a low-power processor for an embedded workload." />
        <div className="row small">
          <label>Sources per part <input className="input" type="number" min={2} max={25} value={perPart}
            onChange={e => setPerPart(Number(e.target.value))} style={{ width: 64 }} /></label>
          <select className="input" value={mode} onChange={e => setMode(e.target.value)} style={{ width: 'auto' }}>
            <option value="fast">Fast discovery</option>
            <option value="deep">+ deep research (local)</option>
            <option value="deep-kaggle">+ deep research (Kaggle GPU)</option>
          </select>
          <span style={{ flex: 1 }} />
          <button className="btn primary" onClick={start} disabled={!statement.trim()}>Start</button>
        </div>
      </div>

      {job && (
        <div className="card" style={{ display: 'grid', gap: 8 }}>
          <div className="row"><b style={{ flex: 1 }}>{job.title ?? 'Breaking down…'}</b><span className={`status ${job.status === 'READY' ? 'VERIFIED' : job.status}`}>{job.status}</span></div>
          <div className="thinking">{job.status !== 'READY' && job.status !== 'FAILED' && <span className="spinner" />}{job.phase}</div>
          {job.breakdown?.parts.map((p, i) => {
            const pp = job.parts[p.name]
            return (
              <details key={p.name} className="card" open={i === 0}>
                <summary className="row" style={{ cursor: 'pointer' }}>
                  <span className="badge">{p.kind}</span><b style={{ flex: 1 }}>{p.name}</b>
                  <span className="small muted">{pp ? `${pp.imported} imported` : ''}</span>
                </summary>
                <div className="small" style={{ marginTop: 6 }}>{p.description}</div>
                <div className="small muted" style={{ marginTop: 4 }}>Queries: {p.queries.join(' · ')}</div>
                {pp && (
                  <div className="small" style={{ marginTop: 4 }}>
                    {pp.status} · found {pp.found} · Laya kept {pp.kept} · imported {pp.imported}{pp.failed ? ` · ${pp.failed} unavailable` : ''}
                    {pp.coverageP != null && ` · existing coverage ${Math.round(pp.coverageP * 100)}%`}
                    <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>{pp.sources.slice(0, 12).map(s => <li key={s}>{s}</li>)}</ul>
                  </div>
                )}
              </details>
            )
          })}
          {job.status === 'READY' && !job.designId && <button className="btn primary" onClick={design}>Design from this evidence</button>}
          {job.designId && <button className="btn" onClick={() => onDesign(job.designId!)}>Open design run</button>}
        </div>
      )}
    </>
  )
}
