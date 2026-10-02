import { useMemo } from 'react'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

/** Renders model Markdown safely and turns [n] / [n, m] citations into clickable chips. */
export function Markdown({ text, onCite }: { text: string; onCite?: (n: number) => void }) {
  const html = useMemo(() => {
    const raw = marked.parse(text ?? '', { async: false, breaks: false }) as string
    const withCites = raw.replace(/\[(\d+(?:\s*,\s*\d+)*)\]/g, (_m, nums: string) =>
      nums.split(',').map(n => `<button class="cite" data-cite="${n.trim()}">${n.trim()}</button>`).join(''))
    return DOMPurify.sanitize(withCites, { ADD_ATTR: ['data-cite'] })
  }, [text])

  return (
    <div
      className="md"
      onClick={e => {
        const el = (e.target as HTMLElement).closest('[data-cite]') as HTMLElement | null
        if (el && onCite) onCite(Number(el.dataset.cite))
      }}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  )
}
