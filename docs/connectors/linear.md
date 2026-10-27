# Linear

Kontext indexes workspace issues, projects, and documents available to a Linear personal API key. Archived records are included. GraphQL errors fail the sync, including errors returned alongside an HTTP success response.

## Configure

```dotenv
KONTEXT_LINEAR_API_KEY=lin_api_your-key
KONTEXT_LINEAR_SYNC_INTERVAL_SECONDS=86400
```

Create a key for an account with read access to the intended workspace data. `KONTEXT_LINEAR_API_BASE_URL` can point to a compatible GraphQL endpoint or proxy. Relay cursors are followed with loop and page-count protection. A failed or incomplete query skips stale-document cleanup. Sync runs daily by default; the interval must be at least 30 seconds.

See the [Linear sync section](../../README.md#linear-sync) for the setup summary.
