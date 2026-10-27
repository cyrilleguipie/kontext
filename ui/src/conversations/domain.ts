export type Citation = { index: number; title: string; url: string; source: string; excerpt: string }
export type ConversationMessage = {
  role: 'user' | 'assistant'
  content: string
  sources?: Citation[]
  pending?: boolean
}
export type StoredMessage = Omit<ConversationMessage, 'pending'>
export type SavedConversation = {
  id: string
  title: string
  updatedAt: string
  messages: StoredMessage[]
}
export type ConversationStore = {
  currentId: string
  conversations: SavedConversation[]
}
export type HistoryError = {
  code: 'invalid-data' | 'conversation-not-found' | 'storage-read' | 'storage-write'
  message: string
}
export type Result<Value, Error> = { ok: true; value: Value } | { ok: false; error: Error }

const MAX_CONVERSATIONS = 12
const MAX_MESSAGES_PER_CONVERSATION = 30
const MAX_MESSAGE_LENGTH = 5_000
const MAX_SOURCES_PER_MESSAGE = 5
const MAX_EXCERPT_LENGTH = 500
const MAX_TITLE_LENGTH = 64

export const emptyConversationStore = (): ConversationStore => ({ currentId: '', conversations: [] })

export const decodeConversationStore = (raw: string | null): Result<ConversationStore, HistoryError> => {
  if (raw === null) return { ok: true, value: emptyConversationStore() }
  try {
    const value: unknown = JSON.parse(raw)
    if (!isConversationStore(value)) return invalidData()
    return {
      ok: true,
      value: {
        currentId: value.currentId,
        conversations: value.conversations.slice(0, MAX_CONVERSATIONS),
      },
    }
  } catch {
    return invalidData()
  }
}

export const saveCurrentConversation = (
  store: ConversationStore,
  id: string,
  messages: readonly ConversationMessage[],
  updatedAt: string,
): ConversationStore => {
  const savedMessages = messages
    .filter((message): message is StoredMessage => !message.pending)
    .slice(-MAX_MESSAGES_PER_CONVERSATION)
    .map(message => ({
      role: message.role,
      content: message.content.slice(0, MAX_MESSAGE_LENGTH),
      sources: message.sources?.slice(0, MAX_SOURCES_PER_MESSAGE).map(source => ({
        ...source,
        excerpt: source.excerpt.slice(0, MAX_EXCERPT_LENGTH),
      })),
    }))

  if (!savedMessages.length) return { ...store, currentId: id }

  const firstPrompt = messages.find(message => message.role === 'user')?.content.trim()
  const conversation: SavedConversation = {
    id,
    title: firstPrompt ? firstPrompt.slice(0, MAX_TITLE_LENGTH) : 'New conversation',
    updatedAt,
    messages: savedMessages,
  }
  const conversations = [conversation, ...store.conversations.filter(item => item.id !== id)]
    .slice(0, MAX_CONVERSATIONS)
  return { currentId: id, conversations }
}

export const selectConversation = (
  store: ConversationStore,
  id: string,
): Result<SavedConversation, HistoryError> => {
  const conversation = store.conversations.find(item => item.id === id)
  return conversation
    ? { ok: true, value: conversation }
    : { ok: false, error: { code: 'conversation-not-found', message: 'Saved conversation was not found.' } }
}

export const removeConversation = (
  store: ConversationStore,
  id: string,
  replacementId: string,
): ConversationStore => ({
  currentId: store.currentId === id ? replacementId : store.currentId,
  conversations: store.conversations.filter(item => item.id !== id),
})

const isConversationStore = (value: unknown): value is ConversationStore => {
  if (!isRecord(value) || typeof value.currentId !== 'string' || !Array.isArray(value.conversations)) return false
  return value.conversations.every(isSavedConversation)
}

const isSavedConversation = (value: unknown): value is SavedConversation =>
  isRecord(value) && typeof value.id === 'string' && typeof value.title === 'string' &&
  typeof value.updatedAt === 'string' && Array.isArray(value.messages) && value.messages.every(isStoredMessage)

const isStoredMessage = (value: unknown): value is StoredMessage =>
  isRecord(value) && (value.role === 'user' || value.role === 'assistant') &&
  typeof value.content === 'string' &&
  (value.sources === undefined || (Array.isArray(value.sources) && value.sources.every(isCitation)))

const isCitation = (value: unknown): value is Citation =>
  isRecord(value) && typeof value.index === 'number' && typeof value.title === 'string' &&
  typeof value.url === 'string' && typeof value.source === 'string' && typeof value.excerpt === 'string'

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

const invalidData = (): Result<never, HistoryError> => ({
  ok: false,
  error: { code: 'invalid-data', message: 'Saved conversation history is invalid.' },
})
