import { describe, expect, it } from 'vitest'

import { percent, shortId } from '@/shared/utils/format'

describe('format helpers', () => {
  it('keeps probability deltas signed', () => {
    expect(percent(0.125, 1)).toBe('12.5%')
    expect(percent(-0.015, 2)).toBe('-1.50%')
  })

  it('shortens trace identifiers deterministically', () => {
    expect(shortId('abcdef0123456789', 8)).toBe('abcdef01')
    expect(shortId('short', 8)).toBe('short')
  })
})
