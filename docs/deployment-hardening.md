# Deployment hardening

Compose is configured for a single-host local deployment. Its database, API, and UI ports bind to `127.0.0.1`; expose them through a reviewed network boundary when remote access is needed.

## Required secrets

1. Copy `.env.example` to `.env` and set a unique database password. For example, generate one with `openssl rand -hex 32`.
2. Keep `.env` out of version control and restrict its file permissions to the service operator. Do not put credentials in Compose files, images, shell history, or issue reports.
3. Set a long, random `KONTEXT_ADMIN_TOKEN` before using the admin API. An empty token disables admin routes. The token grants document mutation, connector triggering, and sync-status access.
4. Use source credentials with read-only scopes and the narrowest available project, space, or repository allowlist. Rotate credentials at the source when an operator or deployment is retired.

Compose fails to start when `KONTEXT_DB_PASSWORD` is missing or empty instead of silently using a known default. PostgreSQL, the backend, and the UI are published only on loopback by default. The database is also reachable by the backend over the private Compose network.

## Remote access

- Put a maintained TLS reverse proxy in front of the UI. Terminate HTTPS there and preserve the original host and client address in forwarded headers.
- Prefer a same-origin UI/API deployment. The browser UI calls `/api` through Nginx; Ktor does not enable wildcard CORS. If a separate web origin is required, configure an explicit origin allowlist at the trusted edge and review credentials and preflight behavior before enabling it.
- Do not publish PostgreSQL to the public network. If host database access is needed, keep it on loopback or a private management network.
- Keep `KONTEXT_ADMIN_TOKEN` out of browser persistence and shared links. Enter it only in the trusted UI session and clear it when finished.
- Restrict access to local-file ingestion directories. The source preview route serves files from the configured directory, so anything mounted there may be readable to users who can reach the UI/API.

## Operations

- Back up the PostgreSQL `pgdata` volume and verify restore procedures before upgrading.
- Monitor `/api/health` and the persisted connector sync status. Investigate repeated failures and confirm a successful full sync after changing scopes or allowlists.
- Set explicit connector allowlists and practical sync intervals to control API usage and embedding load.
- Review Compose image updates and rebuild from source; pin image digests in environments that require reproducible deployments.
- The supplied Compose stack does not provide TLS, secret management, network policy, database backup, or a Sonar quality gate. Add those at the deployment layer before exposing it beyond a trusted host.

