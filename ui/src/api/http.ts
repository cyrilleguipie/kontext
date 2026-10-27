import type { Citation } from '../conversations/domain'

export type ApiError = {
  kind: 'network' | 'http' | 'invalid-response'
  message: string
  status?: number
}
export type Result<Value, Error> = { ok: true; value: Value } | { ok: false; error: Error }
export type ChatHistoryMessage = { role: 'user' | 'assistant'; content: string }

type ChatStreamHandlers = {
  onSources: (sources: Citation[]) => void
  onToken: (token: string) => void
}
type CompleteChatStreamHandlers = ChatStreamHandlers & { onDone: () => void }

export const requestJson = async <Value>(url: string, init?: RequestInit): Promise<Result<Value, ApiError>> => {
  let response: Response
  try {
    response = await fetch(url, init)
  } catch {
    return { ok: false, error: { kind: 'network', message: 'Could not reach the Kontext server.' } }
  }
  if (!response.ok) return { ok: false, error: await responseError(response) }
  try {
    return { ok: true, value: await response.json() as Value }
  } catch {
    return { ok: false, error: { kind: 'invalid-response', message: 'The server returned an invalid response.' } }
  }
}

export const streamChat = async (
  message: string,
  history: ChatHistoryMessage[],
  handlers: ChatStreamHandlers,
): Promise<Result<void, ApiError>> => {
  let response: Response
  try {
    response = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ message, history }),
    })
  } catch {
    return { ok: false, error: { kind: 'network', message: 'Could not reach the Kontext server.' } }
  }
  if (!response.ok) return { ok: false, error: await responseError(response) }
  if (!response.body) return { ok: false, error: { kind: 'invalid-response', message: 'Streaming response is unavailable.' } }

  try {
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let completed = false
    const streamHandlers = { ...handlers, onDone: () => { completed = true } }
    while (true) {
      const { value, done } = await reader.read()
      buffer = `${buffer}${decoder.decode(value, { stream: !done })}`.replaceAll('\r\n', '\n')
      const events = buffer.split('\n\n')
      buffer = events.pop() ?? ''
      for (const event of events) {
        const result = consumeEvent(event, streamHandlers)
        if (!result.ok) {
          await cancelReader(reader)
          return result
        }
      }
      if (done) break
    }
    if (buffer.trim()) {
      const result = consumeEvent(buffer, streamHandlers)
      if (!result.ok) {
        await cancelReader(reader)
        return result
      }
    }
    if (!completed) return { ok: false, error: { kind: 'invalid-response', message: 'The chat stream ended before completion.' } }
    return { ok: true, value: undefined }
  } catch {
    return { ok: false, error: { kind: 'network', message: 'The chat stream ended unexpectedly.' } }
  }
}

const cancelReader = async (reader: ReadableStreamDefaultReader<Uint8Array>): Promise<void> => {
  try {
    await reader.cancel()
  } catch {
    // Preserve the original stream error when cancellation also fails.
  }
}

const consumeEvent = (
  event: string,
  handlers: CompleteChatStreamHandlers,
): Result<void, ApiError> => {
  const name = event.match(/^event:\s*(.+)$/m)?.[1]
  const data = event.match(/^data:\s*(.*)$/m)?.[1]
  if (!name || data === undefined) return { ok: true, value: undefined }
  let payload: unknown
  try {
    payload = JSON.parse(data)
  } catch {
    return { ok: false, error: { kind: 'invalid-response', message: 'The chat stream contained invalid data.' } }
  }
  if (name === 'token' && typeof payload === 'string') handlers.onToken(payload)
  if (name === 'sources' && isCitationList(payload)) handlers.onSources(payload)
  if (name === 'done') handlers.onDone()
  if (name === 'error' && typeof payload === 'string') {
    return { ok: false, error: { kind: 'http', message: payload } }
  }
  return { ok: true, value: undefined }
}

const responseError = async (response: Response): Promise<ApiError> => {
  try {
    const payload: unknown = await response.json()
    const message = isRecord(payload) && typeof payload.error === 'string' ? payload.error : `Request failed (${response.status}).`
    return { kind: 'http', status: response.status, message }
  } catch {
    return { kind: 'http', status: response.status, message: `Request failed (${response.status}).` }
  }
}

const isCitationList = (value: unknown): value is Citation[] =>
  Array.isArray(value) && value.every(item => isRecord(item) && typeof item.index === 'number' &&
    typeof item.title === 'string' && typeof item.url === 'string' && typeof item.source === 'string' &&
    typeof item.excerpt === 'string')

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
