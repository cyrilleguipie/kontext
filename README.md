<div align="center">

<img src="https://img.shields.io/badge/Java-21%2B-orange?style=flat-square&logo=openjdk" alt="Java 21+"/>
<img src="https://img.shields.io/badge/Kotlin-2.4-purple?style=flat-square&logo=kotlin" alt="Kotlin 2.4"/>
<img src="https://img.shields.io/badge/Ktor-3.1-blue?style=flat-square" alt="Ktor 3.1"/>
<img src="https://img.shields.io/badge/pgvector-0.8-336791?style=flat-square&logo=postgresql" alt="pgvector"/>
<img src="https://img.shields.io/badge/license-Apache%202.0-green?style=flat-square" alt="License"/>

# Kontext

**Self-hosted RAG assistant for engineering teams.**  
Connect your knowledge tools, ask questions in natural language, and get answers grounded in your own docs, with deployment and model-provider choices under your control.

</div>

---

## Why Kontext?

Engineering teams accumulate knowledge across a dozen tools — Jira tickets, Confluence pages, Notion wikis, GitHub issues, Slack threads, GitBook docs… Finding an answer means opening five tabs and hoping someone wrote it down.

Kontext ingests knowledge into a local vector store and exposes a single chat interface backed by a configurable LLM endpoint. Use a local model server to keep prompts and retrieved passages in your infrastructure; a remote model provider will receive the prompt and context sent for that request. The current release supports OpenAI-compatible endpoints. Direct OpenAI and Anthropic Claude provider adapters, UI-managed connector configuration, and enterprise SSO/provisioning are on the product roadmap; see the [enterprise-readiness direction](docs/enterprise-readiness.md).

---

## Features

- 🔌 **Connector-ready storage** — normalized documents and chunks are stored independently from the chat pipeline
- 🔍 **Hybrid search** — dense vector search + PostgreSQL full-text ranking fused with Reciprocal Rank Fusion (RRF)
- 🤖 **Local LLM** — streams answers via SSE; works with LM Studio, Ollama, llama.cpp, or any OpenAI-compatible endpoint
- 📦 **Self-hosted** — Docker Compose runs PostgreSQL + pgvector, the backend, and the web UI; connect it to your local LLM server
- 📚 **Grounded answers** — retrieved passages are included as citations that link back to their source documents
- 💬 **Streaming chat UI** — Vue 3 frontend with SSE-based token streaming
- 🧩 **Arrow-based error handling** — typed errors throughout, no unchecked exceptions leaking

---

## Supported Connectors

Kontext syncs local Markdown/text/PDF/DOCX files, Jira issues, Confluence pages, Notion pages, GitBook pages, GitHub repositories, allowlisted GitLab projects, Linear issues/projects/documents, Backstage Software Catalog entities and optionally TechDocs pages, and public Zendesk, Intercom, and Freshdesk Help Center articles. Manual documents can also be indexed through the authenticated admin API.

### ✅ Tier 1 — Atlassian

| Connector | Type | Auth | Notes |
|---|---|---|---|
| **Jira Cloud** | Issues, Epics | Basic `email:token` | Enhanced API v3 search, `nextPageToken` pagination; [setup](docs/connectors/jira.md) |
| **Jira Data Center** | Issues, Epics | Bearer PAT | API v2, `startAt/maxResults` pagination; [setup](docs/connectors/jira.md) |
| **Confluence Cloud** | Pages, Spaces | Basic `email:token` | API v2, space-key lookup and cursor pagination; [setup](docs/connectors/confluence.md) |
| **Confluence Data Center** | Pages, Spaces | Bearer PAT | API v1, `start/limit` pagination; [setup](docs/connectors/confluence.md) |

### ✅ Tier 2 — Modern Workspaces

| Connector | Type | Auth | Notes |
|---|---|---|---|
| **Notion** | Pages | Bearer integration token | `POST /v1/search`, cursor pagination; recursive blocks → Markdown; [setup](docs/connectors/notion.md) |
| **GitBook** | Spaces, Pages | Bearer API key | `GET /v1/spaces/{id}/content/pages`; Markdown native; [setup](docs/connectors/gitbook.md) |

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

### ✅ Tier 5 — Developer Platforms

| Connector | Type | Auth | Notes |
|---|---|---|---|
| **GitHub** | Issues, PRs, READMEs | Fine-grained PAT or GitHub App token | REST API; configured repository allowlist; [setup](docs/connectors/github.md) |
| **GitLab** | Issues, MRs, Wiki | PAT | REST API v4; configured project allowlist; [setup](docs/connectors/gitlab.md) |
| **Linear** | Issues, Projects, Documents | Personal API key | GraphQL API; cursor pagination; [setup](docs/connectors/linear.md) |
| **Backstage Catalog** | Software catalog entities; optional TechDocs HTML pages | Optional Bearer token | Catalog cursor pagination; namespace allowlist; TechDocs disabled by default; [setup](docs/connectors/backstage.md) |

### ✅ Tier 6 — Customer Support & Help Centers

| Connector | Type | Auth | Notes |
|---|---|---|---|
| **Zendesk** | Public Help Center articles | Basic `email/token:api_token` | Help Center API v2; cursor pagination; drafts and segmented articles excluded; [setup](docs/connectors/zendesk.md) |
| **Intercom** | Public Help Center articles | Bearer token with `read_articles` scope | REST API v2.17; paginated; drafts and restricted-audience articles excluded; [setup](docs/connectors/intercom.md) |
| **Freshdesk** | Public Help Center articles | API key | REST API v2; drafts and non-public folders excluded; [setup](docs/connectors/freshdesk.md) |
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

> **Adding your own connector?** Implement the `SourceConnector` interface — see the [Connector Development Guide](docs/connectors/README.md).

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
- **Search** — RRF fusion of cosine similarity (pgvector) + PostgreSQL full-text ranking (tsvector)
- **LLM** — any OpenAI-compatible endpoint; SSE streaming
- **Frontend** — Vue 3, TypeScript, Vite
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
# Set KONTEXT_DB_PASSWORD and the KONTEXT_LLM_* values in .env.
# Generate a database password with: openssl rand -hex 32

# 3. Launch
docker compose up -d

# 4. Check health
curl http://localhost:8080/api/health
```

The chat UI is available at `http://localhost:5173`. The API is available at `http://localhost:8080`.

Compose binds its ports to localhost by default. For a network deployment, put a TLS reverse proxy in front of the UI and API and follow the [deployment hardening guide](docs/deployment-hardening.md).

The browser keeps up to 12 recent conversations locally (up to 30 messages each) so they survive a page reload. Conversation history is not sent to the server except for the selected conversation's context when a chat request is made; use **Clear** in the sidebar to remove saved history from this browser.

Jira, Confluence, Notion, GitBook, GitHub, GitLab, Linear, Backstage Catalog, Zendesk, Intercom, Freshdesk, and local-folder sync run on configured intervals when configured. You can also ingest individual documents through the authenticated admin API, then ask questions about them in the chat UI.

---

## Configuration

### Local folder sync

Put `.md`, `.markdown`, `.txt`, `.pdf`, or `.docx` documents in `./knowledge` and start Compose. Text-based PDFs and DOCX files are converted to text with Apache Tika; image-only scanned PDFs need OCR, which is not included. Each file is limited to 20 MiB and extracted text to 250,000 characters. The backend scans subdirectories every five minutes by default, embeds changed content, and removes stale indexed files after a successful non-empty scan. To change the interval, set `KONTEXT_FILES_SYNC_INTERVAL_SECONDS` to at least `30`.

### Atlassian sync

Set `KONTEXT_JIRA_URL` and/or `KONTEXT_CONFLUENCE_URL`, then configure `KONTEXT_ATLASSIAN_EMAIL` and `KONTEXT_ATLASSIAN_TOKEN`. Jira project keys and Confluence space keys are comma-separated; leave either list empty to sync all accessible records. Cloud uses email/token Basic auth. For Data Center, set `KONTEXT_ATLASSIAN_FLAVOR=datacenter` and provide a personal access token. Atlassian sync runs daily by default; change `KONTEXT_ATLASSIAN_SYNC_INTERVAL_SECONDS` to adjust it (minimum 30 seconds).

### LLM & Embedding

```bash
# LM Studio (default port)
KONTEXT_LLM_BASE_URL=http://host.docker.internal:1234/v1
KONTEXT_LLM_CHAT_MODEL=gemma-3-27b-it
KONTEXT_LLM_EMBEDDING_MODEL=text-embedding-bge-m3
KONTEXT_LLM_EMBEDDING_DIMENSION=1024

# Ollama
KONTEXT_LLM_BASE_URL=http://host.docker.internal:11434/v1
KONTEXT_LLM_CHAT_MODEL=llama3.1:8b
KONTEXT_LLM_EMBEDDING_MODEL=nomic-embed-text
KONTEXT_LLM_EMBEDDING_DIMENSION=768
```

### Notion and GitBook sync

Set `KONTEXT_NOTION_TOKEN` to enable Notion. Share pages with the integration before indexing; page text is retrieved through paginated block-child requests. Set `KONTEXT_GITBOOK_TOKEN` and `KONTEXT_GITBOOK_ORGANIZATION_ID` to enable GitBook. `KONTEXT_GITBOOK_SPACES` optionally limits sync to comma-separated space IDs. Both run daily by default; their `*_SYNC_INTERVAL_SECONDS` settings accept values of at least `30`.

### GitHub sync

Set `KONTEXT_GITHUB_TOKEN` and a comma-separated `KONTEXT_GITHUB_REPOSITORIES` allowlist such as `acme/platform,acme/runbooks`. Kontext indexes repository READMEs and all issues and pull requests, including closed items. Use a fine-grained token with repository metadata and issues read permissions; `KONTEXT_GITHUB_API_BASE_URL` can point to a GitHub Enterprise Server REST API base. Sync runs daily by default.

### GitLab sync

Set `KONTEXT_GITLAB_TOKEN` and a comma-separated `KONTEXT_GITLAB_PROJECTS` allowlist such as `platform/api,platform/runbooks`. Kontext indexes issues, merge requests, and wiki pages for those projects. Use a personal, project, or group access token with API read access. `KONTEXT_GITLAB_API_BASE_URL` defaults to `https://gitlab.com/api/v4` and can point to a self-managed GitLab API. Sync runs daily by default; set `KONTEXT_GITLAB_SYNC_INTERVAL_SECONDS` to change the interval (minimum 30 seconds).

### Linear sync

Set `KONTEXT_LINEAR_API_KEY` to a Linear personal API key. Kontext indexes workspace issues, projects, and documents the key can access, including archived records; GraphQL errors stop the sync so incomplete snapshots cannot delete existing indexed data. Sync runs daily by default; set `KONTEXT_LINEAR_SYNC_INTERVAL_SECONDS` to change the interval (minimum 30 seconds). The GraphQL URL can be overridden with `KONTEXT_LINEAR_API_BASE_URL`.

### Backstage Software Catalog and TechDocs sync

Set `KONTEXT_BACKSTAGE_BASE_URL` to the Backstage instance URL (for example `https://backstage.example.com`). If the Catalog API requires authentication, provide a valid Backstage Bearer token in `KONTEXT_BACKSTAGE_TOKEN`. `KONTEXT_BACKSTAGE_NAMESPACES` optionally limits which namespaces Kontext indexes; leave it empty to index all entities visible to the configured token. TechDocs page indexing is opt-in with `KONTEXT_BACKSTAGE_TECHDOCS_ENABLED=true`; it is disabled by default. Sync uses the Catalog API cursor and runs daily by default; set `KONTEXT_BACKSTAGE_SYNC_INTERVAL_SECONDS` to change the interval (minimum 30 seconds). See the [Backstage connector setup guide](docs/connectors/backstage.md).

### Zendesk Help Center sync

Set `KONTEXT_ZENDESK_BASE_URL` to your Zendesk origin (for example `https://acme.zendesk.com`), plus `KONTEXT_ZENDESK_EMAIL` and `KONTEXT_ZENDESK_API_TOKEN`. Kontext uses Basic authentication with Zendesk's `email/token:api_token` format and cursor pagination. It indexes only published articles available to all users, excluding drafts and articles restricted to user segments; tickets are not synced. Sync runs daily by default; set `KONTEXT_ZENDESK_SYNC_INTERVAL_SECONDS` to adjust it (minimum 30 seconds).

### Intercom Help Center sync

Set `KONTEXT_INTERCOM_ACCESS_TOKEN` to an Intercom bearer token with the `read_articles` scope. Kontext indexes published articles whose Help Center audience is `everyone`; drafts and audience-restricted articles are excluded, and conversations are not synced. The API version defaults to `2.17` and can be changed with `KONTEXT_INTERCOM_API_VERSION`; `KONTEXT_INTERCOM_API_BASE_URL` can point to a compatible proxy. Sync runs daily by default; set `KONTEXT_INTERCOM_SYNC_INTERVAL_SECONDS` to change the interval (minimum 30 seconds).

### Freshdesk Help Center sync

Set `KONTEXT_FRESHDESK_BASE_URL` to your HTTPS Freshdesk origin (for example `https://acme.freshdesk.com`) and `KONTEXT_FRESHDESK_API_KEY` to an agent API key. Kontext traverses solution categories and folders, indexes published articles only from folders visible to all users, and skips drafts and folders with login, agent, company, segment, or bot restrictions. It uses Freshdesk API v2 Basic authentication and paginated requests; tickets are not synced. Sync runs daily by default; set `KONTEXT_FRESHDESK_SYNC_INTERVAL_SECONDS` to change the interval (minimum 30 seconds).

### Admin API

Set a private `KONTEXT_ADMIN_TOKEN` in `.env` before using the admin endpoints. Documents are chunked, embedded, and stored transactionally. Re-sending an unchanged document avoids another embedding request.

```bash
curl -X POST http://localhost:8080/api/admin/documents \
  -H "Authorization: Bearer $KONTEXT_ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"externalId":"runbook-prod","title":"Production runbook","url":"https://docs.example.com/runbook","body":"Deploy the service by ...","metadata":{"team":"platform"}}'

curl http://localhost:8080/api/admin/stats \
  -H "Authorization: Bearer $KONTEXT_ADMIN_TOKEN"
```

The admin API is disabled when `KONTEXT_ADMIN_TOKEN` is blank. The chat UI's **Sources** panel uses the same token to list configured connectors, inspect recent sync outcomes, and trigger a sync. Sync status is stored in PostgreSQL and survives restarts; a sync found running after a restart is marked interrupted. The sync request waits for that run to finish. These admin endpoints are available:

```bash
# List configured connectors and their latest persisted sync status
GET /api/admin/connectors

# Trigger an immediate sync for a specific connector
POST /api/admin/connectors/{connector-id}/sync

# Get sync details for one configured connector
GET /api/admin/connectors/{connector-id}/sync/status
```

---

## Connector Development Guide

The connector SPI is available in `backend/.../connector/spi`. Implement `SourceConnector` and return a flow of normalized documents:

```kotlin
class MyToolConnector : SourceConnector {
    override val connectorId: String = "my-tool"
    override val source: DocumentSource = DocumentSource.MY_TOOL

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> = flow {
        // Paginate the source API and emit normalized documents.
    }
}
```

`RawDocument` is the normalized contract every connector must produce:

```kotlin
data class RawDocument(
    val externalId: String,       // stable ID in the source system
    val title: String,
    val body: String,             // Markdown or plain text
    val url: String,
    val metadata: Map<String, String>,
    val updatedAt: Instant,
)
```

`ConnectorSyncService` sends documents through the same chunking and embedding pipeline as manual ingestion, then removes stale source records after a complete fetch. Failed fetches do not reach cleanup. Empty-snapshot cleanup is disabled by default and should be enabled only when an empty result is a valid complete snapshot. Use a distinct `DocumentSource` value and a Flyway migration to allow it in the database constraint. See the [connector guide](docs/connectors/README.md) for pagination, privacy, error, and integration requirements.

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

# Start the database service locally
docker compose up -d db
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
│       │   ├── connector/    # Connector SPI, local files connector, sync orchestration
│       │   │   ├── spi/
│       │   │   ├── ingestion/
│       │   │   └── impl/
│       │   ├── ingestion/    # Chunking, embeddings, transactional indexing
│       │   ├── domain/       # Shared models & error hierarchy
│       │   ├── llm/          # Embedding + Chat clients
│       │   └── store/        # DocumentRepository + HybridSearch
│       └── test/kotlin/…/    # Mirrors main/ structure
├── ui/                       # Vue 3 / Vite chat interface
├── docs/connectors/          # Connector development and setup guides
├── docs/deployment-hardening.md # Deployment security and operations guide
├── docker-compose.yml
├── .env.example
└── README.md
```

### Code Style

- **Language:** All code and comments in English
- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `chore:`)
- **Linting:** detekt + ktlint (must both pass before commit)
- **Error handling:** Model expected failures with typed `KontextError` values and Arrow `Either` / `Raise`; translate transport and persistence failures at adapter boundaries and preserve cancellation.
- **Tests:** Testcontainers for DB integration; WireMock for all external HTTP

---

## Roadmap

| Phase | Status | Description |
|---|---|---|
| 1 — Foundation | ✅ Done | Ktor server, config, pgvector schema, health API |
| 2 — Storage & LLM | ✅ Done | DocumentRepository, HybridSearch (RRF), EmbeddingClient, ChatClient |
| 3 — Connector SPI | ✅ Done | Local Markdown/text/PDF/DOCX files, Jira, Confluence, Notion, GitBook, GitHub, GitLab, Linear, Backstage Catalog entities, and public Zendesk/Intercom/Freshdesk Help Center article sync; authenticated manual ingestion, transactional chunk indexing, persisted sync status and manual sync controls |
| 4 — RAG Engine | ✅ Done | Hybrid retrieval, grounded prompt, source citations |
| 5 — Chat API | ✅ Done | `POST /api/chat` SSE endpoint; conversation history supplied by client |
| 6 — Frontend | ✅ Done | Vue chat UI, citation links, browser-local conversation history, manual document ingestion, and connector sync panel |
| 7 — Docker & Docs | ✅ Done | Compose includes database, backend, and UI with loopback-only ports and a required database secret; deployment, admin API, connector-development, and setup guides cover every implemented connector |

---

## Contributing

Contributions are welcome! The easiest way to contribute is to **add a new connector** for a tool your team uses.

1. Fork the repository
2. Review the [connector development guide](docs/connectors/README.md)
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
