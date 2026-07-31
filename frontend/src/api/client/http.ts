import type { ProblemDetail } from '@/shared/types/api'
import { apiProblemMessage } from '@/shared/utils/displayText'
import { handleShowcaseRequest, showcaseMode } from '@/showcase/runtime'

const API_BASE = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')

let tokenProvider: () => string | null = () => null
let unauthorizedHandler: () => void = () => undefined

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly problem: ProblemDetail | null,
  ) {
    super(apiProblemMessage(problem, status))
    this.name = 'ApiError'
  }
}

export function configureHttp(options: {
  getToken: () => string | null
  onUnauthorized: () => void
}) {
  tokenProvider = options.getToken
  unauthorizedHandler = options.onUnauthorized
}

export function apiUrl(path: string) {
  return `${API_BASE}${path.startsWith('/') ? path : `/${path}`}`
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (showcaseMode) {
    const response = await handleShowcaseRequest(path, init)
    if (response.status === 401) unauthorizedHandler()
    if (response.status < 200 || response.status >= 300) {
      throw new ApiError(response.status, response.body as ProblemDetail)
    }
    return response.body as T
  }
  const token = tokenProvider()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json, application/problem+json')
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(apiUrl(path), { ...init, headers })
  if (response.status === 401) {
    unauthorizedHandler()
  }
  if (!response.ok) {
    let problem: ProblemDetail | null = null
    try {
      problem = (await response.json()) as ProblemDetail
    } catch {
      problem = null
    }
    throw new ApiError(response.status, problem)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export function currentToken() {
  return tokenProvider()
}
