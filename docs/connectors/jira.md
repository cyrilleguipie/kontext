# Jira

Kontext indexes Jira issues and epics visible to the configured account. It uses Jira search pagination and produces one document per issue, with a stable issue key and a link to Jira.

## Configure

```dotenv
KONTEXT_JIRA_URL=https://acme.atlassian.net
KONTEXT_ATLASSIAN_EMAIL=sync-bot@acme.example
KONTEXT_ATLASSIAN_TOKEN=your-api-token
KONTEXT_JIRA_PROJECTS=PLAT,OPS
KONTEXT_ATLASSIAN_SYNC_INTERVAL_SECONDS=86400
```

Cloud uses Basic authentication from the email and API token. For Jira Data Center, set `KONTEXT_ATLASSIAN_FLAVOR=datacenter` and use a personal access token; set the base URL to the Jira context root if it is hosted under a path. Project keys are an optional comma-separated allowlist. An empty list syncs all issues visible to the account.

Use a dedicated account with read access to the intended projects. Deleted or newly inaccessible issues are removed from the index after a complete sync. A failed API request prevents stale-document cleanup. Sync runs daily by default; the interval must be at least 30 seconds.

See the [Atlassian sync section](../../README.md#atlassian-sync) for shared credentials and flavor settings.
