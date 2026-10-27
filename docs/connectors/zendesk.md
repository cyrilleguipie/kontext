# Zendesk Help Center

Kontext indexes published Help Center articles available to all users. Draft articles and articles restricted by user segments are excluded. Tickets are not indexed.

## Configure

```dotenv
KONTEXT_ZENDESK_BASE_URL=https://acme.zendesk.com
KONTEXT_ZENDESK_EMAIL=sync-bot@acme.example
KONTEXT_ZENDESK_API_TOKEN=your-api-token
KONTEXT_ZENDESK_SYNC_INTERVAL_SECONDS=86400
```

The connector authenticates with Basic auth in Zendesk's `email/token:api_token` format and uses cursor pagination. Use an account with read access to Help Center articles. Authentication, rate-limit, or API failures prevent stale-document cleanup. Sync runs daily by default; the interval must be at least 30 seconds.

See the [Zendesk sync section](../../README.md#zendesk-help-center-sync) for the setup summary.
