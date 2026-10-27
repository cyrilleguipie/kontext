package io.github.cyrilleguipie.kontext.domain

sealed interface KontextError {
    val message: String

    sealed interface ConfigError : KontextError {
        data class Missing(val key: String) : ConfigError {
            override val message: String = "Missing required configuration: $key"
        }

        data class Invalid(val key: String, val reason: String) : ConfigError {
            override val message: String = "Invalid configuration for $key: $reason"
        }
    }

    sealed interface StorageError : KontextError {
        data class DimensionMismatch(val expected: Int, val actual: Int) : StorageError {
            override val message: String =
                "Configured embedding dimension ($expected) does not match database column vector($actual). " +
                    "To fix this, either adjust embedding-dimension in configuration or reindex your database."
        }

        data class Database(override val message: String, val cause: Throwable? = null) : StorageError
    }

    sealed interface LlmError : KontextError {
        data class Connection(override val message: String, val cause: Throwable? = null) : LlmError

        data class RateLimited(val retryAfterSeconds: Long? = null) : LlmError {
            override val message: String =
                "LLM rate limit reached (HTTP 429). Retry after: ${retryAfterSeconds ?: "unknown"}s"
        }

        data class DimensionMismatch(val expected: Int, val actual: Int) : LlmError {
            override val message: String =
                "LLM embedding model returned dimension $actual, but expected $expected."
        }

        data class Server(val statusCode: Int, override val message: String) : LlmError
    }

    sealed interface AtlassianError : KontextError {
        data class Authentication(
            override val message: String = "Authentication failed with Atlassian",
        ) : AtlassianError

        data class NotFound(val resource: String) : AtlassianError {
            override val message: String = "Resource not found: $resource"
        }

        data class RateLimited(val retryAfterSeconds: Long? = null) : AtlassianError {
            override val message: String =
                "Atlassian rate limit reached. Retry after: ${retryAfterSeconds ?: "unknown"}s"
        }

        data class Server(val statusCode: Int, override val message: String) : AtlassianError

        data class Network(override val message: String, val cause: Throwable? = null) : AtlassianError
    }

    sealed interface GitLabError : KontextError {
        data object Authentication : GitLabError {
            override val message: String = "GitLab authentication failed; check the configured access token and scope"
        }

        data class RateLimited(val retryAfter: String?) : GitLabError {
            override val message: String = "GitLab rate limit reached${retryAfter?.let { "; retry after $it" } ?: ""}"
        }

        data class HttpFailure(val statusCode: Int, val detail: String) : GitLabError {
            override val message: String = "GitLab API returned HTTP $statusCode: $detail"
        }
    }

    sealed interface LinearError : KontextError {
        data object Authentication : LinearError {
            override val message: String = "Linear authentication failed; check the configured personal API key"
        }

        data class ApiFailure(override val message: String) : LinearError

        data object Pagination : LinearError {
            override val message: String = "Linear pagination returned an incomplete or repeated cursor"
        }
    }

    sealed interface ZendeskError : KontextError {
        data object Authentication : ZendeskError {
            override val message: String = "Zendesk authentication failed; check the account email and API token"
        }

        data class RateLimited(val retryAfterSeconds: String?) : ZendeskError {
            override val message: String =
                "Zendesk rate limit reached" +
                    (retryAfterSeconds?.let { "; retry after $it seconds" } ?: "")
        }

        data class HttpFailure(val statusCode: Int, val detail: String) : ZendeskError {
            override val message: String = "Zendesk API returned HTTP $statusCode: $detail"
        }

        data object Pagination : ZendeskError {
            override val message: String = "Zendesk pagination returned a missing or repeated next link"
        }
    }

    sealed interface IntercomError : KontextError {
        data object Authentication : IntercomError {
            override val message: String =
                "Intercom authentication failed; check the access token and read_articles scope"
        }

        data class RateLimited(val retryAfterSeconds: String?) : IntercomError {
            override val message: String =
                "Intercom rate limit reached" +
                    (retryAfterSeconds?.let { "; retry after $it seconds" } ?: "")
        }

        data class HttpFailure(val statusCode: Int, val detail: String) : IntercomError {
            override val message: String = "Intercom API returned HTTP $statusCode: $detail"
        }

        data object Pagination : IntercomError {
            override val message: String = "Intercom pagination returned invalid or excessive page metadata"
        }
    }

    sealed interface FreshdeskError : KontextError {
        data object Authentication : FreshdeskError {
            override val message: String = "Freshdesk authentication failed; check the API key and agent permissions"
        }

        data class RateLimited(val retryAfterSeconds: String?) : FreshdeskError {
            override val message: String =
                "Freshdesk rate limit reached" + (retryAfterSeconds?.let { "; retry after $it seconds" } ?: "")
        }

        data class HttpFailure(val statusCode: Int, val detail: String) : FreshdeskError {
            override val message: String = "Freshdesk API returned HTTP $statusCode: $detail"
        }

        data object Pagination : FreshdeskError {
            override val message: String = "Freshdesk pagination returned invalid or excessive page metadata"
        }
    }

    sealed interface BackstageError : KontextError {
        data object Authentication : BackstageError {
            override val message = "Backstage authentication failed; check the token and catalog read permissions"
        }

        data class RateLimited(val retryAfter: String?) : BackstageError {
            override val message =
                "Backstage rate limit reached" + (retryAfter?.let { "; retry after $it seconds" } ?: "")
        }

        data class HttpFailure(val statusCode: Int, val detail: String) : BackstageError {
            override val message = "Backstage API returned HTTP $statusCode: $detail"
        }

        data object Pagination : BackstageError {
            override val message = "Backstage catalog pagination returned an invalid or repeated cursor"
        }

        data object TechDocsMetadata : BackstageError {
            override val message = "Backstage TechDocs metadata contains no safe HTML pages"
        }

        data class TechDocsPageTooLarge(val maxBytes: Int) : BackstageError {
            override val message = "Backstage TechDocs page exceeds the configured $maxBytes byte limit"
        }
    }

    sealed interface ConnectorConfigurationError : KontextError {
        data class Invalid(val reason: String) : ConnectorConfigurationError {
            override val message: String = reason
        }

        data object EncryptionUnavailable : ConnectorConfigurationError {
            override val message: String =
                "Connector settings require KONTEXT_SECRET_ENCRYPTION_KEY to be configured"
        }

        data object StoredConfigurationInvalid : ConnectorConfigurationError {
            override val message: String =
                "Stored connector settings cannot be decrypted; verify the secret encryption key"
        }

        data object PersistenceFailure : ConnectorConfigurationError {
            override val message: String = "Connector settings could not be stored"
        }
    }

    class Exception(
        val error: KontextError,
        cause: Throwable? = null,
    ) : RuntimeException(error.message, cause)
}
