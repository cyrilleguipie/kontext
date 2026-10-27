import { onMounted, onUnmounted, ref, watch } from 'vue'
import { createBrowserConversationStorage, createConversationId } from './browserStorage'
import {
  emptyConversationStore,
  removeConversation,
  saveCurrentConversation,
  selectConversation,
  type ConversationMessage,
  type ConversationStore,
  type SavedConversation,
} from './domain'

const PERSIST_DELAY_MILLISECONDS = 400

export const useConversationHistory = () => {
  const storage = createBrowserConversationStorage()
  const store = ref<ConversationStore>(emptyConversationStore())
  const messages = ref<ConversationMessage[]>([])
  const currentConversationId = ref(createConversationId())
  const warning = ref('')
  const ready = ref(false)
  const writable = ref(true)
  const conversations = ref<SavedConversation[]>([])
  let persistTimer: ReturnType<typeof setTimeout> | undefined

  const persist = () => {
    if (!ready.value || !writable.value) return
    store.value = saveCurrentConversation(store.value, currentConversationId.value, messages.value, new Date().toISOString())
    conversations.value = store.value.conversations
    const result = storage.write(store.value)
    warning.value = result.ok ? '' : result.error.message
  }

  const newConversation = () => {
    persist()
    currentConversationId.value = createConversationId()
    messages.value = []
    persist()
  }

  const openConversation = (id: string) => {
    persist()
    const result = selectConversation(store.value, id)
    if (!result.ok) {
      warning.value = result.error.message
      return
    }
    currentConversationId.value = id
    messages.value = result.value.messages.map(message => ({ ...message }))
    persist()
  }

  const deleteConversation = (id: string) => {
    const removesCurrent = currentConversationId.value === id
    const replacementId = removesCurrent ? createConversationId() : currentConversationId.value
    store.value = removeConversation(store.value, id, replacementId)
    currentConversationId.value = replacementId
    if (removesCurrent) messages.value = []
    conversations.value = store.value.conversations
    persist()
  }

  const clearHistory = () => {
    writable.value = true
    warning.value = ''
    store.value = emptyConversationStore()
    conversations.value = []
    currentConversationId.value = createConversationId()
    messages.value = []
    ready.value = true
    persist()
  }

  onMounted(() => {
    const result = storage.read()
    if (result.ok) {
      store.value = result.value
      conversations.value = result.value.conversations
      const selected = selectConversation(result.value, result.value.currentId)
      if (selected.ok) messages.value = selected.value.messages.map(message => ({ ...message }))
      else currentConversationId.value = createConversationId()
    } else {
      writable.value = false
      warning.value = result.error.message
    }
    ready.value = true
  })

  watch(messages, () => {
    clearTimeout(persistTimer)
    persistTimer = setTimeout(persist, PERSIST_DELAY_MILLISECONDS)
  }, { deep: true })

  onUnmounted(() => clearTimeout(persistTimer))

  return { messages, conversations, currentConversationId, warning, newConversation, openConversation, deleteConversation, clearHistory, persist }
}
