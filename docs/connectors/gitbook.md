# GitBook

Kontext indexes pages in the configured GitBook organization and optional space allowlist. It fetches page content through the GitBook Content API and stores searchable Markdown with stable page identifiers.

## Configure

```dotenv
KONTEXT_GITBOOK_TOKEN=your-gitbook-api-token
KONTEXT_GITBOOK_ORGANIZATION_ID=organization-id
KONTEXT_GITBOOK_SPACES=space-id-one,space-id-two
KONTEXT_GITBOOK_SYNC_INTERVAL_SECONDS=86400
```

Create an API token with read access to the intended organization and spaces. `KONTEXT_GITBOOK_SPACES` is optional; when empty, all spaces visible to the token are eligible. A failed request prevents stale-document cleanup. Sync runs daily by default; the interval must be at least 30 seconds.

See the [Notion and GitBook sync section](../../README.md#notion-and-gitbook-sync) for both workspace connector settings.
