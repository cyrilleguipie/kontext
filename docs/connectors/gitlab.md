# GitLab

Kontext indexes issues, merge requests, and wiki pages from an explicit project allowlist. It does not index project repositories or discussion comments.

## Configure

```dotenv
KONTEXT_GITLAB_TOKEN=gitlab-token
KONTEXT_GITLAB_PROJECTS=platform/api,platform/runbooks
KONTEXT_GITLAB_SYNC_INTERVAL_SECONDS=86400
```

Use a personal, project, or group access token with API read access to the selected projects. `KONTEXT_GITLAB_API_BASE_URL` defaults to `https://gitlab.com/api/v4` and can target a self-managed instance. Project paths are comma-separated. Pagination is bounded; API failures prevent stale-document cleanup. Sync runs daily by default; the interval must be at least 30 seconds.

See the [GitLab sync section](../../README.md#gitlab-sync) for the setup summary.
