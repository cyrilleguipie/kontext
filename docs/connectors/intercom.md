# Intercom Help Center

Kontext indexes published Help Center articles whose audience is `everyone`. Draft and audience-restricted articles are excluded; conversations are not indexed.

## Configure

```dotenv
KONTEXT_INTERCOM_ACCESS_TOKEN=your-access-token
KONTEXT_INTERCOM_SYNC_INTERVAL_SECONDS=86400
```

Create a token with the `read_articles` scope and access to the Help Center content to index. The API version defaults to `2.17` and can be changed with `KONTEXT_INTERCOM_API_VERSION`; `KONTEXT_INTERCOM_API_BASE_URL` can point to a compatible proxy. Pagination is bounded. API errors prevent stale-document cleanup. Sync runs daily by default; the interval must be at least 30 seconds.

See the [Intercom sync section](../../README.md#intercom-help-center-sync) for the setup summary.
