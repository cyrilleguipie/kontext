<div align="center">

<img src="https://img.shields.io/badge/Java-21%2B-orange?style=flat-square&logo=openjdk" alt="Java 21+"/>
<img src="https://img.shields.io/badge/Kotlin-2.4-purple?style=flat-square&logo=kotlin" alt="Kotlin 2.4"/>
<img src="https://img.shields.io/badge/Ktor-3.1-blue?style=flat-square" alt="Ktor 3.1"/>
<img src="https://img.shields.io/badge/pgvector-0.8-336791?style=flat-square&logo=postgresql" alt="pgvector"/>
<img src="https://img.shields.io/badge/license-Apache%202.0-green?style=flat-square" alt="License"/>

# Kontext

**Self-hosted RAG assistant for engineering teams.**  
Connect your company's knowledge tools, ask questions in natural language, get answers grounded in your own docs — no data sent to the cloud.

</div>

---

## Why Kontext?

Engineering teams accumulate knowledge across a dozen tools — Jira tickets, Confluence pages, Notion wikis, GitHub issues, Slack threads, GitBook docs… Finding an answer means opening five tabs and hoping someone wrote it down.

Kontext ingests all of it, embeds it into a local vector store, and exposes a single chat interface backed by your own LLM (LM Studio, Ollama, or any OpenAI-compatible server). Everything runs in your infrastructure. No SaaS subscription, no data leaving your network.

---

## Features

- 🔌 **Pluggable connector system** — add any knowledge source via a clean SPI; connectors configured with environment variables
- 🔍 **Hybrid search** — dense vector search + BM25 full-text search fused with Reciprocal Rank Fusion (RRF)
- 🤖 **Local LLM** — streams answers via SSE; works with LM Studio, Ollama, llama.cpp, or any OpenAI-compatible endpoint
- 📦 **Self-hosted** — single `docker compose up`; PostgreSQL + pgvector; no external dependencies
- 🔄 **Incremental sync** — configurable cron per connector; only re-indexes changed documents
- 💬 **Streaming chat UI** — Vue 3 frontend with SSE-based token streaming
- 🧩 **Arrow-based error handling** — typed errors throughout, no unchecked exceptions leaking

---

## Supported Connectors

Connectors are grouped into four tiers based on implementation status.

### ✅ Tier 1 — Atlassian (full implementation)

| Connector | Type | Auth | Notes |
|---|---|---|---|
| **Jira Cloud** | Issues, Epics | Basic `email:token` | API v3, `nextPageToken` pagination |
| **Jira Data Center** | Issues, Epics | Bearer PAT | API v2, `startAt/maxResults` |
| **Confluence Cloud** | Pages, Spaces | Basic `email:token` | API v2, `_links.next` |
| **Confluence Data Center** | Pages, Spaces | Bearer PAT | API v1, `start/limit` |

### ✅ Tier 2 — Modern Workspaces (full implementation)

| Connector | Type | Auth | Notes |
|---|---|---|---|
| **Notion** | Pages, Databases | Bearer integration token | `POST /v1/search`, cursor pagination; blocks → Markdown |
| **GitBook** | Spaces, Pages | Bearer API key | `GET /v1/spaces/{id}/pages`; Markdown native |

### 🔧 Tier 3 — Microsoft 365 (planned)

| Connector | Type | Auth | Notes |
|---|---|---|---|
| **SharePoint** | Sites, Pages, Libraries | OAuth2 (client credentials) | Microsoft Graph API v1.0 |
| **OneDrive** | Files (`.docx`, `.md`, `.txt`, `.pdf`) | OAuth2 (client credentials) | Graph API; text extraction |
| **Microsoft Teams** | Channel messages, Wiki tabs | OAuth2 | Graph API; conversation threads |

### 🔧 Tier 4 — Google Workspace (planned)

| Connector | Type | Auth | Notes |
|---|---|---|---|
| **Google Docs** | Documents | OAuth2 / Service Account | Docs API v1; exports to Markdown |
| **Google Drive** | Files (`.docx`, `.md`, `.txt`, `.pdf`) | OAuth2 / Service Account | Drive API v3; MIME type filtering |

### 🔧 Tier 5 — Developer Platforms (planned)

| Connector | Type | Auth | Notes |
|---|---|---|---|
| **GitHub** | Issues, PRs, Discussions, Wiki, READMEs | PAT or GitHub App | REST API v3 + GraphQL |
| **GitLab** | Issues, MRs, Wiki, Snippets | PAT or OAuth2 | REST API v4 |
| **Linear** | Issues, Projects, Documents | API key | GraphQL API |
| **Backstage** | TechDocs, Catalog entities | Token | Backstage REST API |

### 🔧 Tier 6 — Customer Support & Help Centers (planned)

| Connector | Type | Auth | Notes |
|---|---|---|---|
| **Zendesk** | Articles, Tickets | Basic `email:token` | Help Center API v2 |
| **Intercom** | Articles, Conversations | Bearer token | REST API |
| **Freshdesk** | Articles, Tickets | API key | REST API v2 |
| **HubSpot** | Knowledge Base Articles | Private App token | CMS API |
| **ServiceNow** | Knowledge Articles, Incidents | Basic / OAuth2 | Table API |

### 🔧 Tier 7 — Visual & Async Collaboration (planned)

| Connector | Type | Auth | Notes |
|---|---|---|---|
| **Miro** | Board content, sticky notes | OAuth2 / API key | REST API v2; text extraction |
| **Figma / FigJam** | FigJam board text, comments | OAuth2 / PAT | REST API v1 |
| **Loom** | Video transcripts | API key | Transcript API |

### 🔧 Tier 8 — Self-Hosted Wikis (planned)

| Connector | Type | Auth | Notes |
|---|---|---|---|
| **Outline** | Documents, Collections | API key | REST API |
| **BookStack** | Pages, Chapters, Books | API token | REST API |
| **Wiki.js** | Pages | API key | GraphQL |
| **MediaWiki** | Pages | Anonymous / Bot token | Action API |
| **DokuWiki** | Pages | Basic auth | XML-RPC API |

### 🔧 Tier 9 — Emerging & Niche (planned)

| Connector | Type | Auth | Notes |
|---|---|---|---|
| **Coda** | Docs, Tables | Bearer token | REST API |
| **ClickUp** | Docs, Tasks | API key | REST API v2 |
| **Slab** | Posts, Topics | API key | GraphQL |
| **Nuclino** | Items, Collections | API key | REST API |
| **Slite** | Notes, Channels | API key | REST API |
| **Guru** | Cards, Collections | Basic auth | REST API |
| **Document360** | Articles, Categories | API key | REST API v2 |
| **Tettra** | Pages, Categories | API key | REST API |

> **Adding your own connector?** Implement the `SourceConnector` interface — see [Connector Development Guide](#connector-development-guide) below.

---

## Architecture

```
┌────────────────────────────────────────────────────────────┐
│                        Kontext                             │
│                                                            │
│  ┌──────────────┐    ┌─────────────────┐    ┌──────────┐  │
│  │  Connectors  │───▶│   Ingestion     │───▶│ pgvector │  │
│  │  (SPI)       │    │   Orchestrator  │    │ (Postgres)│  │
│  └──────────────┘    └────────┬────────┘    └──────────┘  │
│                               │                            │
│                       ┌───────▼────────┐                   │
│                       │  Embedding     │                   │
│                       │  Client        │                   │
│                       │  (local LLM)   │                   │
│                       └───────┬────────┘                   │
│                               │                            │
│  ┌──────────────┐    ┌────────▼────────┐                   │
│  │  Chat UI     │◀───│   RAG Engine    │                   │
│  │  (Vue 3/SSE) │    │  Hybrid Search  │                   │
│  └──────────────┘    │  + Chat Client  │                   │
│                       └────────────────┘                   │
└────────────────────────────────────────────────────────────┘
          │                                    │
          ▼                                    ▼
   Source tools                      Local LLM server
   (Jira, Notion…)                 (LM Studio / Ollama)
```

**Stack:**
- **Backend** — Kotlin 2.4, Ktor 3.1, Arrow 2.2, HikariCP, Flyway
- **Storage** — PostgreSQL 17 + pgvector 0.8 (HNSW index + tsvector for FTS)
- **Search** — RRF fusion of cosine similarity (pgvector) + BM25 (tsvector)
- **LLM** — any OpenAI-compatible endpoint; SSE streaming
- **Frontend** — Vue 3, TypeScript, Vite (Phase 6)
- **Packaging** — Docker Compose

---

## Quick Start

**Prerequisites:** Docker 24+, a running local LLM server (LM Studio or Ollama).

```bash
# 1. Clone
git clone https://github.com/cyrilleguipie/kontext.git
cd kontext

# 2. Configure
cp .env.example .env
# Edit .env: set LLM_BASE_URL, EMBEDDING_MODEL, and at least one connector

# 3. Launch
docker compose up -d

# 4. Check health
curl http://localhost:8080/api/health
```

The chat UI is available at `http://localhost:5173` once the frontend container starts (Phase 6).

---

## Configuration

### LLM & Embedding

```bash
# LM Studio (default port)
LLM_BASE_URL=http://host.docker.internal:1234/v1
LLM_CHAT_MODEL=gemma-3-27b-it
LLM_EMBEDDING_MODEL=text-embedding-bge-m3
LLM_EMBEDDING_DIM=1024

# Ollama
LLM_BASE_URL=http://host.docker.internal:11434/v1
LLM_CHAT_MODEL=llama3.1:8b
LLM_EMBEDDING_MODEL=nomic-embed-text
LLM_EMBEDDING_DIM=768
```

### Connectors

Each connector is configured with numbered env vars (`CONNECTOR_N_*`).  
`CONNECTOR_N_TYPE` determines which connector is instantiated.

```bash
# --- Jira Cloud ---
CONNECTOR_1_TYPE=jira-cloud
CONNECTOR_1_ID=acme-jira               # unique identifier (used in API & logs)
CONNECTOR_1_BASE_URL=https://acme.atlassian.net
CONNECTOR_1_EMAIL=bot@acme.com
CONNECTOR_1_API_TOKEN=your_api_token
CONNECTOR_1_PROJECTS=ACME,DEMO,PLAT   # comma-separated project keys (empty = all)
CONNECTOR_1_CRON=0 2 * * *            # daily at 02:00 UTC

# --- Confluence Cloud ---
CONNECTOR_2_TYPE=confluence-cloud
CONNECTOR_2_ID=acme-confluence
CONNECTOR_2_BASE_URL=https://acme.atlassian.net
CONNECTOR_2_EMAIL=bot@acme.com
CONNECTOR_2_API_TOKEN=your_api_token
CONNECTOR_2_SPACES=ENG,INFRA          # space keys (empty = all)
CONNECTOR_2_CRON=0 3 * * *

# --- Notion ---
CONNECTOR_3_TYPE=notion
CONNECTOR_3_ID=eng-notion
CONNECTOR_3_TOKEN=secret_notion_integration_token
CONNECTOR_3_CRON=0 4 * * *

# --- GitBook ---
CONNECTOR_4_TYPE=gitbook
CONNECTOR_4_ID=eng-gitbook
CONNECTOR_4_API_KEY=your_gitbook_api_key
CONNECTOR_4_ORGANIZATION_ID=your_org_id
CONNECTOR_4_CRON=0 5 * * *

# --- GitHub ---
CONNECTOR_5_TYPE=github
CONNECTOR_5_ID=acme-github
CONNECTOR_5_TOKEN=ghp_your_pat
CONNECTOR_5_REPOS=acme/platform,acme/infra   # owner/repo pairs
CONNECTOR_5_INCLUDE=issues,wiki,readme        # what to index
CONNECTOR_5_CRON=0 6 * * *
```

> All connector types and their required fields are documented in [`docs/connectors/`](docs/connectors/).

### Admin API

```bash
# List all configured connectors and their sync status
GET /api/admin/connectors

# Trigger an immediate sync for a specific connector
POST /api/admin/connectors/{connector-id}/sync

# Get last sync details
GET /api/admin/connectors/{connector-id}/sync/status
```

---

## Connector Development Guide

Implement `SourceConnector` to add any new knowledge source:

```kotlin
class MyToolConnector(
    override val config: ConnectorConfig.MyTool,
    private val httpClient: HttpClient,
) : SourceConnector {

    override val connectorId: String = "my-tool"
    override val displayName: String = "My Tool"

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> = flow {
        // paginate your API, emit RawDocument for each page/doc
    }

    override suspend fun testConnection(): Either<ConnectorError, ConnectionInfo> =
        either {
            // verify credentials, return ConnectionInfo on success
        }
}
```

`RawDocument` is the normalized contract every connector must produce:

```kotlin
data class RawDocument(
    val externalId: String,       // stable ID in the source system
    val title: String,
    val body: String,             // Markdown or plain text
    val url: String?,
    val author: String?,
    val labels: List<String>,
    val metadata: Map<String, String>,
    val updatedAt: Instant,
    val connectorId: String,
)
```

The `IngestionOrchestrator` handles chunking → embedding → upsert automatically for any `SourceConnector`.

---

## Development

### Prerequisites

| Tool | Version | Install |
|---|---|---|
| Java | 21 (build) / 25 (runtime optional) | SDKMAN: `sdk use java 21.0.1-tem` |
| Gradle | 9.8.1 | `./gradlew` wrapper included |
| Docker | 24+ | Required for integration tests (Testcontainers) |
| Node | 22 | Required for frontend (Phase 6) |

### Build & Test

```bash
# Format + lint + test (all in one)
JAVA_HOME=~/.sdkman/candidates/java/21.0.1-tem \
  ./gradlew ktlintFormat detekt ktlintCheck test

# Backend only (faster)
JAVA_HOME=~/.sdkman/candidates/java/21.0.1-tem \
  ./gradlew :backend:test

# Run locally (needs Docker for Postgres)
docker compose up -d postgres
./gradlew :backend:run
```

> **Note on Java versions:** detekt 1.23.x doesn't support Java 25 — always run Gradle tasks with `JAVA_HOME` pointing to Java 21.

### Project Layout

```
kontext/
├── backend/                  # Kotlin/Ktor backend
│   └── src/
│       ├── main/kotlin/…/
│       │   ├── api/          # HTTP route handlers
│       │   ├── config/       # HOCON config loading
│       │   ├── connector/    # Connector SPI + all implementations
│       │   │   ├── spi/
│       │   │   ├── registry/
│       │   │   ├── ingestion/
│       │   │   └── impl/
│       │   ├── domain/       # Shared models & error hierarchy
│       │   ├── llm/          # Embedding + Chat clients
│       │   └── store/        # DocumentRepository + HybridSearch
│       └── test/kotlin/…/    # Mirrors main/ structure
├── ui/                       # Vue 3 / Vite frontend (Phase 6)
├── docs/
│   └── connectors/           # Per-connector setup guides
├── docker-compose.yml
├── .env.example
└── README.md
```

### Code Style

- **Language:** All code and comments in English
- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `chore:`)
- **Linting:** detekt + ktlint (must both pass before commit)
- **Error handling:** Arrow `Either` / `Raise` — no unchecked exceptions in business logic
- **Tests:** Testcontainers for DB integration; WireMock for all external HTTP

---

## Roadmap

| Phase | Status | Description |
|---|---|---|
| 1 — Foundation | ✅ Done | Ktor server, config, pgvector schema, health API |
| 2 — Storage & LLM | ✅ Done | DocumentRepository, HybridSearch (RRF), EmbeddingClient, ChatClient |
| 3 — Connector SPI | 🔧 In progress | SPI, ConnectorRegistry, IngestionOrchestrator, Jira + Confluence + Notion + GitBook |
| 4 — RAG Engine | ⏳ Planned | Context assembly, prompt builder, streaming answer API |
| 5 — Chat API | ⏳ Planned | `POST /api/chat` SSE endpoint, conversation history |
| 6 — Frontend | ⏳ Planned | Vue 3 chat UI, connector status dashboard |
| 7 — Docker & Docs | ⏳ Planned | Production Compose, per-connector setup guides, CONTRIBUTING |

---

## Contributing

Contributions are welcome! The easiest way to contribute is to **add a new connector** for a tool your team uses.

1. Fork the repository
2. Read the [Connector Development Guide](#connector-development-guide)
3. Implement `SourceConnector` under `connector/impl/your-tool/`
4. Add WireMock tests
5. Document the connector under `docs/connectors/your-tool.md`
6. Open a pull request

Please follow the [code style](#code-style) guidelines and ensure `ktlintFormat detekt ktlintCheck test` all pass.

---

## License

Apache 2.0 — see [LICENSE](LICENSE).

---

<div align="center">
Built with ☕ Kotlin · 🏹 Arrow · 🐘 pgvector · 🤖 local LLMs
</div>
