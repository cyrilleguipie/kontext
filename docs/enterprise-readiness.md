# Enterprise and managed configuration

This document captures the product direction for individual and organization-hosted Kontext deployments. Each self-hosted installation is treated as one workspace and one organization. A multi-tenant hosted service is a separate deployment model and is not implied by this design.

## Separate the credentials and trust boundaries

Kontext has four independent integrations to manage:

1. **Knowledge connectors** authorize Kontext to read data from Jira, GitHub, and other sources. Workspace administrators configure them in the UI, test access, enable or disable scheduled sync, and request an immediate sync.
2. **Model providers** authorize chat and embedding requests. Chat and embeddings are separate capabilities: a chat provider need not provide embeddings. The current implementation uses an OpenAI-compatible endpoint; direct OpenAI API and Anthropic Claude adapters are future work. A ChatGPT consumer subscription is distinct from an OpenAI API credential.
3. **Employee sign-in** establishes who is using the workspace. OIDC is the first target because it is a common web identity protocol; SAML 2.0 is the second target for enterprise deployments that require it. Both need signed, validated identity assertions, secure sessions, logout, and an explicit mapping from claims/groups to Kontext roles.
4. **User lifecycle provisioning** creates, updates, disables, and removes employee accounts. SCIM 2.0 is the target integration for identity-governance tools such as SailPoint; provisioning must not be confused with interactive SSO.

Ping Identity should work through a standards-based OIDC or SAML integration, subject to that tenant's configuration. SailPoint can provision accounts through a SCIM target integration; it remains independent of the employee's interactive sign-in provider.

## UI-managed connector configuration

The current Sources panel only displays connector sync status and starts a sync. Connectors are constructed at server startup from environment variables, and the admin API is protected by one static bearer token. This is not yet the desired configuration flow.

The target management experience is:

- Show a catalog of supported connector types and which are configured and enabled.
- Render fields from connector-specific schemas, validate before saving, and offer a credential/access check before activation.
- Store non-secret settings as typed values and secrets encrypted at rest. Never return secret values from read APIs; expose only whether a secret is set. Key rotation, encryption-key recovery, and audit history need documented operational procedures.
- Apply enable/disable and interval changes without restarting the service. Report configuration, credential-check, and sync errors as separate states.
- Scope connectors and their indexed documents to the organization represented by this installation. Do not treat a source token as employee authentication.

The encryption key should come from a deployment secret or managed key service, never from the browser or the application database. A database-only backup without the matching encryption key must not be presented as a complete recovery backup.

## Employee roles and administrative controls

Replace the static admin token as the employee-facing authorization model with authenticated sessions and roles such as `workspace_admin` and `member`. Only workspace administrators can edit connector/model configuration, rotate credentials, change identity settings, or inspect audit records. Members can use chat and their own permitted knowledge. Administrative operations should record actor, action, target, and timestamp without recording secret values.

Keep a documented break-glass path for the initial administrator and recovery when the identity provider is unavailable. Do not let remote SSO configuration lock out the local recovery administrator before the new provider has been verified.

## Delivery sequence

1. Add encrypted persistence and UI CRUD for connector configurations, initially migrating the existing connectors one by one from environment-only startup settings. Preserve environment configuration as a bootstrap/legacy path until parity is reached.
2. Add model-provider adapters and separate chat from embedding configuration. Keep the local OpenAI-compatible endpoint as a supported option and make external data transfer explicit in the admin UI.
3. Add OIDC sign-in, workspace roles, sessions, and audit events; validate issuer, audience, signature, state, nonce, redirect URI, and session protections.
4. Add SAML 2.0 where required, then SCIM 2.0 user/group provisioning and deprovisioning. Define how IdP groups map to Kontext roles before enabling automatic provisioning.
5. Add enterprise deployment documentation for TLS, backups including the encryption key, secret rotation, recovery, retention, and security review.

The current code is at step 1's starting point. Do not mark the above capabilities shipped until the UI, backend, persistence, runtime reconfiguration, and their security controls are implemented and verified together.

## Protocol references

- [OpenID Connect overview](https://openid.net/developers/how-connect-works/)
- [OpenID Connect Core](https://openid.net/specs/openid-connect-core-1_0.html)
- [SailPoint SCIM 2.0 connector capabilities](https://documentation.sailpoint.com/connectors/scim_2_0/help/integrating_scim2/supported_features.html)
- [Ping Identity SSO protocols](https://docs.pingidentity.com/solution-guides/workforce_use_cases/htg_delegate_authn_to_external_idp.html)
- [OpenAI API model catalog](https://platform.openai.com/docs/models)
