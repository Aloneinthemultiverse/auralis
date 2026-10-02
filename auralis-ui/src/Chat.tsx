import { useEffect, useRef, useState } from 'react'
import { api, type Answer, type Citation } from './api'
import { Markdown } from './Markdown'

interface Msg { role: 'user' | 'bot'; text: string; answer?: Answer }

const SUGGESTIONS = [
  'Compare systolic arrays and SIMD for DNN inference',
  'How do RISC-V cores handle data hazards?',
  'What limits LLM inference on edge accelerators?',
  'How is power gating applied to SRAM?',
]

/** Center column: chat grounded only in the notebook's (or selected) sources. */
export function ChatPanel(props: {
  notebook: string
  notebookTitle: string
  selected: string[]
  onCite: (c: Citation) => void
  onRun: (kind: 'research' | 'design', objective: string) => void
  className: string
}) {
  const [msgs, setMsgs] = useState<Msg[]>([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const end = useRef<HTMLDivElement>(null)

  useEffect(() => { setMsgs([]) }, [props.notebook])
  useEffect(() => { end.current?.scrollIntoView({ behavior: 'smooth' }) }, [msgs, busy])

  const send = async (q: string) => {
    if (!q.trim() || busy) return
    setInput('')
    setMsgs(m => [...m, { role: 'user', text: q }])
    setBusy(true)
    try {
      const a = await api.ask(props.notebook, q, props.selected)
      setMsgs(m => [...m, { role: 'bot', text: a.answer, answer: a }])
    } catch (e) {
      setMsgs(m => [...m, { role: 'bot', text: `Something went wrong: ${e}` }])
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className={`panel ${props.className}`}>
      <div className="panel-head">
        <h2>Chat</h2>
        <span className="spacer" />
        <span className="small muted">{props.selected.length ? `${props.selected.length} sources selected` : 'all sources in notebook'}</span>
      </div>
      <div className="scroll chat-scroll">
        {msgs.length === 0 && (
          <div className="empty">
            <h1>{props.notebookTitle}</h1>
            <div>Ask anything. Answers come only from this notebook's sources, with citations you can open. Laya routes each message and checks whether the sources really answer it.</div>
            <div className="suggest">{SUGGESTIONS.map(s => <button key={s} onClick={() => send(s)}>{s}</button>)}</div>
          </div>
        )}
        {msgs.map((m, i) => m.role === 'user' ? (
          <div key={i} className="msg user">{m.text}</div>
        ) : (
          <div key={i} className="msg bot">
            {m.answer && (
              <div className="flags">
                <span className={`flag ${m.answer.grounded ? 'ok' : 'warn'}`}>
                  {m.answer.grounded ? 'Grounded in sources' : 'Sources only partly cover this'}
                </span>
                <span className="flag">{m.answer.citations.length} citations</span>
                <span className="flag" title="Laya's routing decision">Laya route: {m.answer.route}</span>
              </div>
            )}
            <Markdown text={m.text} onCite={n => {
              const c = m.answer?.citations.find(x => x.n === n)
              if (c) props.onCite(c)
            }} />
            {m.answer && !m.answer.grounded && m.answer.missing && (
              <div className="next-step">
                <span>Missing: {m.answer.missing}</span>
                <span style={{ flex: 1 }} />
                <button className="btn small primary" onClick={() => props.onRun('research', msgs[i - 1]?.text ?? '')}>Research this</button>
              </div>
            )}
            {m.answer && m.answer.route === 'design' && (
              <div className="next-step">
                <span>This looks like a design request.</span>
                <span style={{ flex: 1 }} />
                <button className="btn small primary" onClick={() => props.onRun('design', msgs[i - 1]?.text ?? '')}>Start design run</button>
              </div>
            )}
            {m.answer && m.answer.route === 'research' && m.answer.grounded && (
              <div className="next-step">
                <span>Want to go beyond these sources?</span>
                <span style={{ flex: 1 }} />
                <button className="btn small" onClick={() => props.onRun('research', msgs[i - 1]?.text ?? '')}>Start research run</button>
              </div>
            )}
          </div>
        ))}
        {busy && <div className="thinking"><span className="spinner" />Retrieving sources, Laya is ranking them, writing a cited answer…</div>}
        <div ref={end} />
      </div>
      <div className="composer">
        <div className="composer-row">
          <textarea className="input" rows={2} placeholder="Ask about your sources…" value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(input) } }} />
          <button className="btn primary" onClick={() => send(input)} disabled={busy || !input.trim()}>Ask</button>
        </div>
        <div className="hint">Enter to send · Shift+Enter for a new line · tick sources on the left to narrow the chat</div>
      </div>
    </section>
  )
}
