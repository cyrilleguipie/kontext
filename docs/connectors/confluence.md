# Confluence

Kontext indexes Confluence pages visible to the configured account. Each page has a stable page identifier and links back to its Confluence URL. The connector converts page content to searchable text and paginates through the configured spaces.

## Configure

```dotenv
KONTEXT_CONFLUENCE_URL=https://acme.atlassian.net/wiki
KONTEXT_ATLASSIAN_EMAIL=sync-bot@acme.example
KONTEXT_ATLASSIAN_TOKEN=your-api-token
KONTEXT_CONFLUENCE_SPACES=ENG,RUNBOOKS
KONTEXT_ATLASSIAN_SYNC_INTERVAL_SECONDS=86400
```

Cloud uses Basic authentication from the email and API token. For Confluence Data Center, set `KONTEXT_ATLASSIAN_FLAVOR=datacenter` and use a personal access token; set the URL to the Confluence context root. Space keys are an optional comma-separated allowlist; an empty list syncs every space visible to the account.

Use a dedicated read-only account with access limited to the intended spaces. A failed API request prevents stale-document cleanup; pages removed or no longer visible are deleted after a complete sync. Sync runs daily by default; the interval must be at least 30 seconds.

See the [Atlassian sync section](../../README.md#atlassian-sync) for shared credentials and flavor settings.
