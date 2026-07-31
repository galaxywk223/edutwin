import { describe, expect, it, vi } from 'vitest'

import { parseSseBlock, streamAssistantMessage } from '@/api/client/sse'

describe('parseSseBlock', () => {
  it('parses durable event identifiers and JSON data', () => {
    expect(
      parseSseBlock(
        'id: 7\nevent: twin.snapshot-created\ndata: {"sequence":7,"eventType":"twin.snapshot-created"}',
      ),
    ).toEqual({
      id: '7',
      event: 'twin.snapshot-created',
      data: '{"sequence":7,"eventType":"twin.snapshot-created"}',
    })
  })

  it('ignores heartbeat comments and joins multi-line data', () => {
    expect(parseSseBlock(': keepalive')).toBeNull()
    expect(parseSseBlock('data: {"a":1,\ndata: "b":2}')).toEqual({
      data: '{"a":1,\n"b":2}',
    })
  })

  it('replays assistant events after the last durable event identifier', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(
      'id: 4\nevent: message.delta\ndata: {"sequence":4,"messageId":"message-1","type":"message.delta","occurredAt":"2026-07-15T00:00:00Z","data":{"text":"已恢复"}}\n\n',
      { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
    ))
    const events: unknown[] = []

    await streamAssistantMessage('message-1', { lastEventId: 3, onEvent: (event) => events.push(event) })

    const headers = new Headers(fetchMock.mock.calls[0][1]?.headers)
    expect(headers.get('Last-Event-ID')).toBe('3')
    expect(events).toMatchObject([{ sequence: 4, type: 'message.delta', data: { text: '已恢复' } }])
    fetchMock.mockRestore()
  })
})
