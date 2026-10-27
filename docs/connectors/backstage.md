# Backstage Software Catalog

Kontext indexes the Backstage Catalog entities visible to its configured API identity. It requests only selected fields and stores a curated textual view of each entity: kind, namespace, name, title, description, tags, common ownership/type/lifecycle fields, links, and catalog relations. Other entity fields and annotations are not requested. TechDocs HTML pages can also be indexed when explicitly enabled.

## Configure

```dotenv
KONTEXT_BACKSTAGE_BASE_URL=https://backstage.example.com
KONTEXT_BACKSTAGE_TOKEN=your-backstage-bearer-token
KONTEXT_BACKSTAGE_NAMESPACES=default,platform
KONTEXT_BACKSTAGE_SYNC_INTERVAL_SECONDS=86400
KONTEXT_BACKSTAGE_TECHDOCS_ENABLED=false
```

The base URL is the Backstage instance URL, without `/api/catalog`. A Bearer token is optional when the Catalog API permits anonymous access; otherwise use a token accepted by that Backstage installation's identity and permission policy. Do not use a broad administrative token if a narrower read identity is available. Keep credentials in `.env` and out of version control.

`KONTEXT_BACKSTAGE_NAMESPACES` is an optional comma-separated index allowlist. Empty means every entity the Backstage API returns to this identity. Use this setting to keep unrelated namespaces out of Kontext; it filters entities after each bounded API page is fetched.

TechDocs indexing is disabled by default. Set `KONTEXT_BACKSTAGE_TECHDOCS_ENABLED=true` to fetch published HTML pages for each allowed Catalog entity. The connector reads TechDocs metadata and static HTML from the Backstage TechDocs API, strips navigation and non-content markup, and indexes each page separately with a stable entity and page identifier. It skips entities whose TechDocs metadata endpoint returns 404. To bound resource use, it accepts at most 500 HTML pages per entity, 10,000 per sync, and 2 MB per HTML page; the static page response must include a Content-Length header. The Backstage identity also needs permission to read the corresponding TechDocs entities and pages.

## Sync and permissions

Kontext calls `GET /api/catalog/entities/by-query` with cursor pagination and requests up to 100 entities per page. Backstage's Catalog API applies the permissions of the configured API identity; Kontext then applies the namespace allowlist. A failed, unauthorized, rate-limited, or invalidly paginated response fails the snapshot and skips stale-document cleanup.

Entity documents use a stable `kind:namespace/name` identifier and link back to the entity in Backstage. TechDocs page documents use a stable page-specific identifier and link to their TechDocs site. The Catalog API field selection limits the response to the fields listed above, excluding arbitrary annotations and unselected specification fields. A failed, unauthorized, rate-limited, or invalid TechDocs response fails the snapshot and skips stale-document cleanup.

Use the chat UI's **Sources** panel to inspect the sync outcome and trigger an immediate sync. The daily interval can be changed to any value of at least 30 seconds.

See the [Backstage Catalog API reference](https://backstage.io/docs/features/software-catalog/software-catalog-api/) for entity query, cursor pagination, and access-token behavior.

See the [TechDocs architecture](https://backstage.io/docs/features/techdocs/architecture/) for the static-site publishing model.
