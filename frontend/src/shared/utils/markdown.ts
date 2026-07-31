import DOMPurify from 'dompurify'
import { marked } from 'marked'

const allowedTags = [
  'a', 'blockquote', 'br', 'code', 'del', 'em', 'h1', 'h2', 'h3', 'h4', 'hr',
  'li', 'ol', 'p', 'pre', 'strong', 'table', 'tbody', 'td', 'th', 'thead', 'tr', 'ul',
]

export function renderSafeMarkdown(source: string) {
  const parsed = marked.parse(source, { async: false, breaks: true, gfm: true }) as string
  const sanitized = DOMPurify.sanitize(parsed, {
    ALLOWED_ATTR: ['href', 'title'],
    ALLOWED_TAGS: allowedTags,
  })
  const template = document.createElement('template')
  template.innerHTML = sanitized
  template.content.querySelectorAll('a').forEach((anchor) => {
    const href = anchor.getAttribute('href')
    if (!href) return
    try {
      const url = new URL(href, window.location.origin)
      if (!['http:', 'https:'].includes(url.protocol)) {
        anchor.removeAttribute('href')
        return
      }
      anchor.setAttribute('target', '_blank')
      anchor.setAttribute('rel', 'noopener noreferrer')
    } catch {
      anchor.removeAttribute('href')
    }
  })
  return template.innerHTML
}

export function createCachedMarkdownRenderer(
  renderer: (source: string) => string = renderSafeMarkdown,
) {
  const cache = new Map<string, { content: string; html: string }>()
  return {
    render(messageId: string, content: string) {
      const current = cache.get(messageId)
      if (current?.content === content) return current.html
      const html = renderer(content)
      cache.set(messageId, { content, html })
      return html
    },
    clear() {
      cache.clear()
    },
  }
}
