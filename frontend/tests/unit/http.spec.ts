import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiError, request } from '@/api/client/http'

describe('HTTP client', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('accepts JSON success bodies and RFC Problem Details for no-content operations', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(request<void>('/auth/password', { method: 'POST' })).resolves.toBeUndefined()

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(init.headers).get('Accept')).toBe(
      'application/json, application/problem+json',
    )
  })

  it('converts English problem details into Chinese messages', () => {
    const error = new ApiError(401, {
      title: 'Unauthorized',
      status: 401,
      detail: 'The username or password is invalid.',
      code: 'INVALID_CREDENTIALS',
      traceId: 'trace-1',
      violations: [],
    })

    expect(error.message).toBe('账号或密码错误。')
  })
})
