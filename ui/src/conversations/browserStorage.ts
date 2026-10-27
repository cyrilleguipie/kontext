import { decodeConversationStore, type ConversationStore, type HistoryError, type Result } from './domain'

export interface ConversationStorage {
  read: () => Result<ConversationStore, HistoryError>
  write: (store: ConversationStore) => Result<void, HistoryError>
}

const STORAGE_KEY = 'kontext.conversations.v1'

export const createBrowserConversationStorage = (): ConversationStorage => ({
  read: () => {
    try {
      return decodeConversationStore(window.localStorage.getItem(STORAGE_KEY))
    } catch {
      return { ok: false, error: { code: 'storage-read', message: 'Browser storage is unavailable.' } }
    }
  },
  write: (store) => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(store))
      return { ok: true, value: undefined }
    } catch {
      return { ok: false, error: { code: 'storage-write', message: 'Browser storage could not save conversation history.' } }
    }
  },
})

export const createConversationId = (): string =>
  globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
