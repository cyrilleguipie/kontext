# Notion

Kontext indexes Notion pages shared with the configured integration. It searches pages and retrieves their block children recursively, converting supported block content to Markdown-like text. Pages must be explicitly shared with the integration.

## Configure

```dotenv
KONTEXT_NOTION_TOKEN=secret_your-integration-token
KONTEXT_NOTION_SYNC_INTERVAL_SECONDS=86400
```

Create an internal integration with read-content access and share the pages or parent pages to index. Only content the integration can retrieve is included. Notion pagination is followed through its cursor; API failures fail the snapshot and skip stale-document cleanup. Sync runs daily by default; the interval must be at least 30 seconds.

See the [Notion sync section](../../README.md#notion-and-gitbook-sync) for the combined workspace setup.
