<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { useConversationHistory } from './conversations/useConversationHistory'
import { requestJson, streamChat } from './api/http'

const {
  messages,
  conversations,
  currentConversationId,
  warning: persistenceWarning,
  newConversation,
  openConversation,
  deleteConversation,
  clearHistory,
  persist: persistConversation,
} = useConversationHistory()
const prompt = ref('')
const busy = ref(false)
const error = ref('')
const feed = ref<HTMLElement>()
const ingestOpen = ref(false)
const ingestBusy = ref(false)
const ingestError = ref('')
const ingestNotice = ref('')
const adminToken = ref('')
type ConnectorStatus = { connectorId: string; source: string; running: boolean; lastAttemptAt?: string; lastSuccessAt?: string; indexed?: number; deleted?: number; error?: string }
const connectorsOpen = ref(false)
const connectorStatuses = ref<ConnectorStatus[]>([])
const connectorsBusy = ref(false)
const connectorsError = ref('')
const document = ref({ externalId: '', title: '', url: '', body: '' })

async function loadConnectors() {
  connectorsError.value = ''
  if (!adminToken.value) { connectorsError.value = 'Enter the admin token to view connector status.'; return }
  connectorsBusy.value = true
  const result = await requestJson<ConnectorStatus[]>('/api/admin/connectors', {
    headers: { Authorization: `Bearer ${adminToken.value}` },
  })
  if (result.ok) connectorStatuses.value = result.value
  else connectorsError.value = result.error.message
  connectorsBusy.value = false
}

async function syncConnector(connectorId: string) {
  connectorsBusy.value = true
  connectorsError.value = ''
  const result = await requestJson<ConnectorStatus>(`/api/admin/connectors/${encodeURIComponent(connectorId)}/sync`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${adminToken.value}` },
  })
  if (result.ok) {
    const index = connectorStatuses.value.findIndex(item => item.connectorId === connectorId)
    if (index >= 0) connectorStatuses.value[index] = result.value
  } else connectorsError.value = result.error.message
  connectorsBusy.value = false
}

async function ask(value = prompt.value) {
  const question = value.trim()
  if (!question || busy.value) return
  prompt.value = ''
  error.value = ''
  const history = messages.value.filter(m => !m.pending).map(({ role, content }) => ({ role, content }))
  messages.value.push({ role: 'user', content: question }, { role: 'assistant', content: '', pending: true })
  const answer = messages.value[messages.value.length - 1]
  busy.value = true
  const result = await streamChat(question, history, {
    onSources: (sources) => { answer.sources = sources },
    onToken: (token) => { answer.content += token },
  })
  if (!result.ok) {
    error.value = result.error.message
    if (!answer.content) answer.content = 'I could not complete that request.'
  }
  await nextTick()
  feed.value?.scrollTo({ top: feed.value.scrollHeight, behavior: 'smooth' })
  answer.pending = false
  busy.value = false
  persistConversation()
}

async function ingestDocument() {
  ingestError.value = ''
  ingestNotice.value = ''
  ingestBusy.value = true
  const result = await requestJson<{ unchanged: boolean; chunkCount: number }>('/api/admin/documents', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken.value}` },
    body: JSON.stringify(document.value),
  })
  if (result.ok) {
    ingestNotice.value = result.value.unchanged
      ? 'This document was already up to date.'
      : `Document indexed in ${result.value.chunkCount} chunks.`
    document.value = { externalId: '', title: '', url: '', body: '' }
  } else ingestError.value = result.error.message
  ingestBusy.value = false
}
</script>

<template>
  <div class="shell">
    <aside class="rail">
      <a class="brand" href="#" aria-label="Kontext home"><span class="brand-mark">K</span><span>kontext</span></a>
      <button class="new-chat" @click="newConversation(); error = ''"><span>＋</span> New conversation</button>
      <div class="rail-label">RECENT CONVERSATIONS</div>
      <nav class="conversation-list" aria-label="Saved conversations"><div v-for="item in conversations" :key="item.id" class="conversation-row" :class="{ selected: item.id === currentConversationId }"><button class="conversation-select" @click="openConversation(item.id); error = ''" :title="item.title">{{ item.title }}</button><button class="conversation-delete" :aria-label="`Delete ${item.title}`" @click="deleteConversation(item.id)">×</button></div><span v-if="!conversations.length" class="empty-history">No saved conversations yet.</span></nav>
      <div v-if="persistenceWarning" class="storage-warning">{{ persistenceWarning }}</div>
      <div class="rail-bottom"><span>Saved in this browser</span><button v-if="conversations.length || persistenceWarning" class="clear-history" @click="clearHistory">Clear</button></div>
    </aside>
    <main>
      <header class="topbar"><div><span class="crumb">Kontext</span><span class="slash">/</span><span>Ask your knowledge</span></div><div class="topbar-actions"><span v-if="ingestNotice" class="ingest-notice">{{ ingestNotice }}</span><button class="add-document" @click="connectorsOpen = true; loadConnectors()">Sources</button><button class="add-document" @click="ingestOpen = true">＋ Add a document</button><div class="connection"><i></i> Connected to your workspace</div></div></header>
      <section ref="feed" class="conversation" :class="{ 'is-empty': !messages.length }">
        <template v-if="!messages.length">
          <div class="welcome"><div class="eyebrow"><span></span> YOUR TEAM'S KNOWLEDGE, IN ONE PLACE</div><h1>What can I help<br/>you <em>figure out?</em></h1><p>Ask a question across your engineering docs.<br/>Every answer comes with sources you can verify.</p>
            <div class="suggestions"><button @click="ask('How do we deploy the production service?')"><span class="suggestion-icon">↗</span><span><b>How do we deploy?</b><small>Find the latest release steps</small></span><span class="arrow">↗</span></button><button @click="ask('What is the architecture of our platform?')"><span class="suggestion-icon">⌘</span><span><b>Explain our architecture</b><small>Connect the dots across docs</small></span><span class="arrow">↗</span></button><button @click="ask('What changed in the latest project updates?')"><span class="suggestion-icon">◷</span><span><b>Catch me up on a project</b><small>Surface decisions and updates</small></span><span class="arrow">↗</span></button></div>
          </div>
        </template>
        <div v-else class="thread">
          <article v-for="(message, index) in messages" :key="index" class="message" :class="message.role">
            <div class="avatar">{{ message.role === 'user' ? 'Y' : 'K' }}</div><div class="message-body"><div class="message-label">{{ message.role === 'user' ? 'YOU' : 'KONTEXT' }} <span v-if="message.pending" class="typing">THINKING<span>...</span></span></div><p class="message-text">{{ message.content }}<span v-if="message.pending && message.content" class="cursor"></span></p>
              <div v-if="message.sources?.length" class="sources"><div class="sources-heading">SOURCES <span>{{ message.sources.length }}</span></div><a v-for="source in message.sources" :key="source.index" :href="source.url" target="_blank" rel="noreferrer" class="source-card"><span class="source-number">{{ String(source.index).padStart(2, '0') }}</span><span class="source-info"><b>{{ source.title }}</b><small>{{ source.source }} · {{ source.excerpt.slice(0, 115) }}{{ source.excerpt.length > 115 ? '…' : '' }}</small></span><span class="source-arrow">↗</span></a></div>
            </div>
          </article>
        </div>
      </section>
      <div class="composer-wrap"><div v-if="error" class="error">{{ error }}</div><form class="composer" @submit.prevent="ask()"><textarea v-model="prompt" rows="1" placeholder="Ask anything about your team's knowledge..." :disabled="busy" @keydown.enter.exact.prevent="ask()"></textarea><div class="composer-footer"><span><kbd>↵</kbd> to send <span class="hint-separator">·</span> Answers grounded in your docs</span><button class="send" type="submit" :disabled="!prompt.trim() || busy" aria-label="Send message">↑</button></div></form><div class="disclaimer">Kontext can make mistakes. Verify important information in the linked sources.</div></div>
    </main>
    <div v-if="ingestOpen" class="modal-backdrop" @click.self="ingestOpen = false">
      <section class="ingest-modal" role="dialog" aria-modal="true" aria-labelledby="ingest-title">
        <div class="modal-head"><div><div class="eyebrow"><span></span> WORKSPACE KNOWLEDGE</div><h2 id="ingest-title">Add a document</h2></div><button class="modal-close" aria-label="Close" @click="ingestOpen = false">×</button></div>
        <p class="modal-copy">Add a runbook, design note, or other reference. Kontext will index it for future answers.</p>
        <form class="ingest-form" @submit.prevent="ingestDocument">
          <label>Admin token<input v-model="adminToken" type="password" autocomplete="off" placeholder="KONTEXT_ADMIN_TOKEN" required /></label>
          <div class="form-row"><label>Document ID<input v-model="document.externalId" maxlength="512" placeholder="platform-deploy-runbook" required /></label><label>Title<input v-model="document.title" maxlength="500" placeholder="Production deploy runbook" required /></label></div>
          <label>Source URL<input v-model="document.url" type="url" placeholder="https://docs.example.com/runbook" required /></label>
          <label>Document text<textarea v-model="document.body" maxlength="250000" rows="8" placeholder="Paste the document text here..." required /></label>
          <p v-if="ingestNotice" class="ingest-success">{{ ingestNotice }}</p>
          <p v-if="ingestError" class="error">{{ ingestError }}</p>
          <div class="modal-actions"><span class="privacy-note">Token stays in this tab and is not saved.</span><button class="primary-button" :disabled="ingestBusy">{{ ingestBusy ? 'Indexing…' : 'Index document' }}</button></div>
        </form>
      </section>
    </div>
    <div v-if="connectorsOpen" class="modal-backdrop" @click.self="connectorsOpen = false">
      <section class="ingest-modal" role="dialog" aria-modal="true" aria-labelledby="connectors-title">
        <div class="modal-head"><div><div class="eyebrow"><span></span> KNOWLEDGE SOURCES</div><h2 id="connectors-title">Connector sync</h2></div><button class="modal-close" aria-label="Close" @click="connectorsOpen = false">×</button></div>
        <p class="modal-copy">Review configured sources or start a sync. Enter the admin token used for document ingestion.</p>
        <form class="ingest-form" @submit.prevent="loadConnectors"><label>Admin token<input v-model="adminToken" type="password" autocomplete="off" placeholder="KONTEXT_ADMIN_TOKEN" required /></label><div class="modal-actions"><span class="privacy-note">Token stays in this tab and is not saved.</span><button class="primary-button" :disabled="connectorsBusy">{{ connectorsBusy ? 'Loading…' : 'Refresh status' }}</button></div></form>
        <p v-if="connectorsError" class="error connector-error">{{ connectorsError }}</p>
        <div v-if="connectorStatuses.length" class="connector-list"><article v-for="item in connectorStatuses" :key="item.connectorId" class="connector-card"><div class="connector-card-head"><div><b>{{ item.connectorId }}</b><small>{{ item.source }}</small></div><span class="connector-state" :class="{ failed: item.error, active: item.running }">{{ item.running ? 'SYNCING' : item.error ? 'FAILED' : item.lastSuccessAt ? 'READY' : 'IDLE' }}</span></div><p v-if="item.lastSuccessAt" class="connector-detail">Last successful sync: {{ new Date(item.lastSuccessAt).toLocaleString() }} · {{ item.indexed ?? 0 }} updated · {{ item.deleted ?? 0 }} removed</p><p v-else class="connector-detail">No successful sync recorded yet.</p><p v-if="item.error" class="connector-failure">{{ item.error }}</p><button class="add-document" :disabled="connectorsBusy || item.running" @click="syncConnector(item.connectorId)">Sync now</button></article></div>
        <p v-else-if="!connectorsBusy && !connectorsError" class="connector-detail">No connectors are configured. Set source credentials or a local files directory to enable sync.</p>
      </section>
    </div>
  </div>
</template>
