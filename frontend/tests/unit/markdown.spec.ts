import { describe, expect, it, vi } from 'vitest'

import { createCachedMarkdownRenderer } from '@/shared/utils/markdown'

describe('cached Markdown renderer', () => {
  it('recomputes only the message whose content changed', () => {
    const renderer = vi.fn((source: string) => `<p>${source}</p>`)
    const cache = createCachedMarkdownRenderer(renderer)

    expect(cache.render('message-1', 'first')).toBe('<p>first</p>')
    expect(cache.render('message-2', 'second')).toBe('<p>second</p>')
    expect(cache.render('message-1', 'first')).toBe('<p>first</p>')
    expect(cache.render('message-2', 'second delta')).toBe('<p>second delta</p>')

    expect(renderer).toHaveBeenCalledTimes(3)
    expect(renderer).toHaveBeenNthCalledWith(3, 'second delta')
  })
})
