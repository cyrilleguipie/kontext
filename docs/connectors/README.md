# Connector development guide

This guide describes the path from an external API to searchable Kontext documents. A connector is an outbound adapter: it reads one source, converts records to `RawDocument`, and leaves chunking, embeddings, persistence, and cleanup to the shared ingestion pipeline.

## Connector setup guides

| Source | Guide |
|---|---|
| Jira | [Jira Cloud and Data Center](jira.md) |
| Confluence | [Confluence Cloud and Data Center](confluence.md) |
| Notion | [Notion](notion.md) |
| GitBook | [GitBook](gitbook.md) |
| GitHub | [GitHub](github.md) |
| GitLab | [GitLab](gitlab.md) |
| Linear | [Linear](linear.md) |
| Backstage | [Catalog and optional TechDocs](backstage.md) |
| Zendesk | [Help Center](zendesk.md) |
| Intercom | [Help Center](intercom.md) |
| Freshdesk | [Help Center](freshdesk.md) |

Local-folder ingestion is configured through the shared [README setup](../../README.md#local-folder-sync); manual ingestion is covered in the [Admin API section](../../README.md#admin-api).

## Data contract

Implement `SourceConnector` in `backend/src/main/kotlin/io/github/cyrilleguipie/kontext/connector/impl/<source>/`:

```kotlin
class ExampleConnector(
    private val config: ExampleConfig,
    private val httpClient: HttpClient,
) : SourceConnector {
    override val connectorId: String = "example"
    override val source: DocumentSource = DocumentSource.EXAMPLE

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> = flow {
        // Fetch one page at a time and emit normalized records as they arrive.
    }
}
```

Each emitted record must have a stable `externalId` within its `DocumentSource`, a useful title, plain text or Markdown body, a source URL, bounded string metadata, and the source's update time. Keep response-to-document mapping in small deterministic functions; keep HTTP, persistence, clock, and filesystem effects at the adapter boundary.

## Safe synchronization rules

- Read only the minimum required scopes. Allowlist projects or workspaces when the source supports it.
- Exclude unpublished or audience-restricted content unless the product explicitly supports those permissions end to end.
- Use the shared Ktor client, follow the API's pagination contract, cap page traversal, and avoid accumulating a whole source snapshot in memory.
- Convert expected authentication, rate-limit, HTTP, and pagination failures into a source-specific `KontextError`. Preserve coroutine cancellation; do not report cancellation as a successful partial snapshot.
- Never emit a record until its page has been validated. A failed fetch must fail the connector flow so stale-document cleanup is not reached.
- Leave `allowEmptySnapshotCleanup` false unless an empty result is a valid complete snapshot for that source. This protects an existing index from accidental deletion after a misconfigured or incomplete fetch.
- Normalize dates deterministically. Avoid `Instant.now()` fallbacks for malformed upstream timestamps because they create needless re-indexing; use an explicit documented fallback or fail the snapshot.

`ConnectorSyncService` ingests each record through the common chunking and embedding pipeline. After a complete fetch, it deletes records from that connector's `DocumentSource` that were not observed. Failed fetches do not reach this cleanup step. The current snapshot flow updates documents as they arrive, so an interrupted run can contain partial upserts; the next complete sync reconciles the snapshot.

## Integration checklist

1. Add an immutable config data class with a redacted `toString`, validated non-secret settings, and a minimum sync interval.
2. Add the source to `DocumentSource` and add a Flyway migration to the source constraint.
3. Define source-specific typed failures under `KontextError`; include safe, bounded response details and never include credentials.
4. Implement the connector with bounded, sequential pagination and pure mapping functions.
5. Register it in startup scheduling and wire its environment variables through `application.conf`, Compose, and `.env.example`.
6. Add a README support-matrix row and a setup section describing required scopes, indexed record types, exclusions, and interval configuration.
7. Add focused tests for mapping, pagination, auth/rate-limit errors, restricted-content filtering, and empty-snapshot behavior. Use mocked HTTP responses; do not require live credentials.
8. Run Kotlin compilation, ktlint, detekt, the focused tests, Compose config validation, and `git diff --check`. Verify migrations against a disposable database before release.

## Connector setup page template

Create `docs/connectors/<source>.md` for integrations with source-specific setup complexity. Include API scopes and credential creation, allowlist behavior, indexed and excluded records, environment variables and defaults, pagination and rate limits, and how to observe the first synchronization safely.
