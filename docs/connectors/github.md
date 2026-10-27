# GitHub

Kontext indexes repository README files, issues, and pull requests from an explicit repository allowlist. Closed issues and pull requests are included. Discussions, wikis, and comments are not indexed.

## Configure

```dotenv
KONTEXT_GITHUB_TOKEN=github-token
KONTEXT_GITHUB_REPOSITORIES=acme/platform,acme/runbooks
KONTEXT_GITHUB_SYNC_INTERVAL_SECONDS=86400
```

Use a fine-grained token with read access to repository metadata and issues. For GitHub Enterprise Server, set `KONTEXT_GITHUB_API_BASE_URL` to the instance REST API base. Repositories are specified as `owner/name`; the allowlist must not be empty. Requests are paginated and bounded. A failed API request prevents stale-document cleanup. Sync runs daily by default.

See the [GitHub sync section](../../README.md#github-sync) for the setup summary.
