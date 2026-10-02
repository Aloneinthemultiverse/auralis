import { useEffect, useState } from 'react'
import { api, type Citation, type Notebook, type Source } from './api'
import { SourcesPanel } from './Sources'
import { ChatPanel } from './Chat'
import { StudioPanel } from './Studio'

type Mobile = 'sources' | 'chat' | 'studio'

export default function App() {
  const [notebooks, setNotebooks] = useState<Notebook[]>([])
  const [nb, setNb] = useState<string>(() => localStorage.getItem('auralis.nb') ?? 'all')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [drawer, setDrawer] = useState<{ title: string; uri: string | null; text: string } | null>(null)
  const [health, setHealth] = useState<{ name: string; up: boolean }[]>([])
  const [refresh, setRefresh] = useState(0)
  const [runRequest, setRunRequest] = useState<{ kind: 'research' | 'design'; objective: string; nonce: number } | null>(null)
  const [mobile, setMobile] = useState<Mobile>('chat')
  const [creating, setCreating] = useState(false)
  const [newTitle, setNewTitle] = useState('')

  const loadNotebooks = () => api.notebooks().then(setNotebooks).catch(() => {})
  useEffect(() => { loadNotebooks() }, [refresh])
  useEffect(() => { try { localStorage.setItem('auralis.nb', nb) } catch { /* private mode */ } setSelected(new Set()) }, [nb])
  useEffect(() => {
    const load = () => api.status().then(t => setHealth(t.split('\n').filter(l => /^[✓✗]/.test(l))
      .map(l => ({ up: l.startsWith('✓'), name: l.slice(2).replace(/:.*/, '').replace(/ \(.*\)/, '') })))).catch(() => setHealth([]))
    load(); const t = window.setInterval(load, 30000); return () => window.clearInterval(t)
  }, [])

  const current = notebooks.find(n => n.id === nb)
  const openCitation = (c: Citation) => setDrawer({ title: `[${c.n}] ${c.title}`, uri: c.uri, text: c.excerpt })
  const openSource = (s: Source) => setDrawer({ title: s.title, uri: s.uri, text: `${s.chunks} passages · ${s.sourceType}` })

  const create = async () => {
    if (!newTitle.trim()) return
    const r = await fetch('/api/notebooks', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ title: newTitle }) }).then(r => r.json())
    setCreating(false); setNewTitle(''); await loadNotebooks(); setNb(r.notebook); setMobile('sources')
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <svg viewBox="0 0 32 32" aria-hidden="true"><rect width="32" height="32" rx="8" fill="var(--accent)" /><path d="M9 23l7-14 7 14M12 18h8" stroke="#fff" strokeWidth="2.6" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
          Auralis <small>engineering notebook</small>
        </div>
        {creating ? (
          <div className="row">
            <input className="input" autoFocus placeholder="Notebook topic, e.g. AI accelerators for LLM inference" value={newTitle}
              onChange={e => setNewTitle(e.target.value)} onKeyDown={e => e.key === 'Enter' && create()} style={{ width: 360 }} />
            <button className="btn primary" onClick={create}>Create</button>
            <button className="btn" onClick={() => setCreating(false)}>Cancel</button>
          </div>
        ) : (
          <div className="row">
            <select className="input" value={nb} onChange={e => setNb(e.target.value)} style={{ width: 'auto', maxWidth: 380 }} aria-label="Notebook">
              {notebooks.map(n => <option key={n.id} value={n.id}>{n.title} — {n.sources.toLocaleString()} sources</option>)}
            </select>
            <button className="btn" onClick={() => setCreating(true)}>+ New notebook</button>
          </div>
        )}
        <span className="spacer" />
        <div className="health">{health.map(h => <span key={h.name} className={`dot ${h.up ? 'up' : 'down'}`}>{h.name}</span>)}</div>
      </header>

      <div style={{ display: 'grid', gridTemplateRows: 'auto 1fr', minHeight: 0 }}>
        <nav className="mobile-tabs">
          {(['sources', 'chat', 'studio'] as Mobile[]).map(m => (
            <button key={m} className={`tab ${mobile === m ? 'active' : ''}`} onClick={() => setMobile(m)}>{m[0].toUpperCase() + m.slice(1)}</button>
          ))}
        </nav>
        <main className="columns">
          <SourcesPanel notebook={nb} selected={selected} setSelected={setSelected} onOpen={openSource}
            refreshKey={refresh} onImported={() => setRefresh(r => r + 1)} className={mobile === 'sources' ? 'active' : ''} />
          <ChatPanel notebook={nb} notebookTitle={current?.title ?? 'Auralis'} selected={[...selected]} onCite={openCitation}
            onRun={(kind, objective) => { setRunRequest({ kind, objective, nonce: Date.now() }); setMobile('studio') }}
            className={mobile === 'chat' ? 'active' : ''} />
          <StudioPanel notebook={nb} selected={[...selected]} onCite={openCitation} runRequest={runRequest}
            onNotebook={id => { setRefresh(r => r + 1); setNb(id) }}
            className={mobile === 'studio' ? 'active' : ''} />
        </main>
      </div>

      {drawer && (
        <aside className="drawer" role="dialog" aria-label="Source">
          <div className="drawer-head">
            <h3>{drawer.title}</h3>
            {drawer.uri && <a className="btn small" href={drawer.uri} target="_blank" rel="noreferrer">Open source</a>}
            <button className="btn small" onClick={() => setDrawer(null)}>Close</button>
          </div>
          <div className="drawer-body">{drawer.text}</div>
        </aside>
      )}
    </div>
  )
}
