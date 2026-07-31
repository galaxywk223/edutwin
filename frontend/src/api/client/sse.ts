import { apiUrl, currentToken, ApiError } from './http'
import type { AssistantEvent, ProblemDetail, SseJobEvent } from '@/shared/types/api'
import { showcaseMode, simulateAnalysisStream, simulateAssistantStream } from '@/showcase/runtime'

interface ParsedEvent {
  id?: string
  event?: string
  data?: string
}

export function parseSseBlock(block: string): ParsedEvent | null {
  const event: ParsedEvent = {}
  for (const rawLine of block.split(/\r?\n/)) {
    if (!rawLine || rawLine.startsWith(':')) continue
    const separator = rawLine.indexOf(':')
    const field = separator < 0 ? rawLine : rawLine.slice(0, separator)
    const value = separator < 0 ? '' : rawLine.slice(separator + 1).replace(/^ /, '')
    if (field === 'id') event.id = value
    if (field === 'event') event.event = value
    if (field === 'data') event.data = event.data ? `${event.data}\n${value}` : value
  }
  return event.data ? event : null
}

export async function streamAnalysisJob(
  jobId: string,
  options: {
    signal?: AbortSignal
    lastEventId?: number
    onEvent: (event: SseJobEvent) => void
  },
) {
  if (showcaseMode) {
    await simulateAnalysisStream(jobId, options.onEvent)
    return
  }
  const headers = new Headers({ Accept: 'text/event-stream' })
  const token = currentToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (options.lastEventId) headers.set('Last-Event-ID', String(options.lastEventId))

  const response = await fetch(apiUrl(`/analysis/jobs/${jobId}/events`), {
    headers,
    signal: options.signal,
  })
  if (!response.ok || !response.body) {
    let problem: ProblemDetail | null = null
    try {
      problem = (await response.json()) as ProblemDetail
    } catch {
      problem = null
    }
    throw new ApiError(response.status, problem)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const parsed = parseSseBlock(buffer.slice(0, boundary))
      buffer = buffer.slice(boundary + 2)
      if (parsed?.data) options.onEvent(JSON.parse(parsed.data) as SseJobEvent)
      boundary = buffer.indexOf('\n\n')
    }
    if (done) break
  }
}

export async function streamAssistantMessage(
  messageId: string,
  options: {
    signal?: AbortSignal
    lastEventId?: number
    onEvent: (event: AssistantEvent) => void
  },
) {
  if (showcaseMode) {
    await simulateAssistantStream(messageId, options.onEvent)
    return
  }
  const headers = new Headers({ Accept: 'text/event-stream' })
  const token = currentToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (options.lastEventId !== undefined) {
    headers.set('Last-Event-ID', String(options.lastEventId))
  }

  const response = await fetch(apiUrl(`/assistant/messages/${messageId}/events`), {
    headers,
    signal: options.signal,
  })
  if (!response.ok || !response.body) {
    let problem: ProblemDetail | null = null
    try {
      problem = (await response.json()) as ProblemDetail
    } catch {
      problem = null
    }
    throw new ApiError(response.status, problem)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const parsed = parseSseBlock(buffer.slice(0, boundary))
      buffer = buffer.slice(boundary + 2)
      if (parsed?.data) options.onEvent(JSON.parse(parsed.data) as AssistantEvent)
      boundary = buffer.indexOf('\n\n')
    }
    if (done) break
  }
}
