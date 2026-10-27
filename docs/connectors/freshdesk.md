# Freshdesk Help Center

Kontext synchronizes published solution articles in Freshdesk folders visible to all users. It does not sync tickets, drafts, or folders restricted to logged-in users, agents, selected companies, bots, or contact/company segments.

## Configure

Create or choose a Freshdesk agent account with the least permissions that can read solution categories, folders, and articles. Copy that agent's API key from its profile. Freshdesk API access follows the permissions of the authenticated agent, so a key with broader permissions can expose more content through the API.

Set these values in `.env`:

```dotenv
KONTEXT_FRESHDESK_BASE_URL=https://your-company.freshdesk.com
KONTEXT_FRESHDESK_API_KEY=your-agent-api-key
KONTEXT_FRESHDESK_SYNC_INTERVAL_SECONDS=86400
```

Use the HTTPS account origin only. Compose passes these values to the backend; keep `.env` private and do not commit it. Restart the backend after changing configuration.

## What gets indexed

The connector walks solution categories and nested folders, requesting at most 100 records per page. It follows only folders whose visibility is `All Users` (`1`) and indexes articles whose status is `published` (`2`). Article HTML is converted to text, and extracted content is capped at 250,000 characters.

Freshdesk returns `Retry-After` on rate limits. Authentication, authorization, rate-limit, and HTTP failures fail the sync snapshot; they do not proceed to stale-document cleanup. The configured interval must be at least 30 seconds. Keep the interval reasonable for your account's Freshdesk API rate limits.

After the first run, use **Sources** in the chat UI to inspect the connector's last result and trigger a manual sync. The connector status includes indexed/deleted counts and the last error. The connector uses `article:<id>` as its stable source identifier.

See the [Freshdesk API reference](https://developers.freshdesk.com/api/) for API key authentication, pagination, solution folder visibility, and article status details.
