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

    class Exception(
        val error: KontextError,
        cause: Throwable? = null,
    ) : RuntimeException(error.message, cause)
}
