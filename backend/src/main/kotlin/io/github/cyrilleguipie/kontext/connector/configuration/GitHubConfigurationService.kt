package io.github.cyrilleguipie.kontext.connector.configuration

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.github.cyrilleguipie.kontext.config.GitHubConfig
import io.github.cyrilleguipie.kontext.connector.impl.github.GitHubConnector
import io.github.cyrilleguipie.kontext.connector.ingestion.ConnectorJobScheduler
import io.github.cyrilleguipie.kontext.domain.KontextError.ConnectorConfigurationError
import io.github.cyrilleguipie.kontext.security.EncryptedSecret
import io.github.cyrilleguipie.kontext.security.SecretCipher
import io.github.cyrilleguipie.kontext.store.ConnectorConfigurationRepository
import io.github.cyrilleguipie.kontext.store.EncryptedConnectorConfiguration
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.sql.SQLException
import java.time.Instant

@Serializable
data class GitHubConfigurationRequest(
    val enabled: Boolean,
    val token: String = "",
    val repositories: List<String>,
    val apiBaseUrl: String,
    val syncIntervalSeconds: Long,
)

@Serializable
data class GitHubConfigurationView(
    val enabled: Boolean,
    val tokenConfigured: Boolean,
    val repositories: List<String>,
    val apiBaseUrl: String,
    val syncIntervalSeconds: Long,
    val source: String,
)

@Serializable
private data class StoredGitHubConfiguration(
    val token: String,
    val repositories: List<String>,
    val apiBaseUrl: String,
    val syncIntervalSeconds: Long,
)

class GitHubConfigurationService(
    private val repository: ConnectorConfigurationRepository,
    private val cipher: SecretCipher?,
    private val legacyConfig: GitHubConfig,
    private val httpClient: HttpClient,
    private val scheduler: ConnectorJobScheduler,
) {
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun get(): Either<ConnectorConfigurationError, GitHubConfigurationView> =
        loadEffective().map { (settings, enabled, source) -> settings.toView(enabled, source) }

    suspend fun save(
        request: GitHubConfigurationRequest,
    ): Either<ConnectorConfigurationError, GitHubConfigurationView> = either {
        val (current, _, _) = loadEffective().bind()
        val validated = validate(request, current.token).bind()
        val configuredCipher = cipher ?: raise(ConnectorConfigurationError.EncryptionUnavailable)
        val payload = json.encodeToString(validated.settings).toByteArray(Charsets.UTF_8)
        val encrypted =
            try {
                configuredCipher.encrypt(payload, CONNECTOR_ID.toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {
                raise(ConnectorConfigurationError.StoredConfigurationInvalid)
            }
        val row =
            EncryptedConnectorConfiguration(
                connectorId = CONNECTOR_ID,
                enabled = request.enabled,
                ciphertext = encrypted.ciphertext,
                nonce = encrypted.nonce,
                updatedAt = Instant.now(),
            )
        try {
            withContext(Dispatchers.IO) { repository.save(row) }
        } catch (_: SQLException) {
            raise(ConnectorConfigurationError.PersistenceFailure)
        }
        apply(validated.settings, request.enabled)
        GitHubConfigurationView(
            enabled = request.enabled,
            tokenConfigured = validated.settings.token.isNotBlank(),
            repositories = validated.settings.repositories,
            apiBaseUrl = validated.settings.apiBaseUrl,
            syncIntervalSeconds = validated.settings.syncIntervalSeconds,
            source = "database",
        )
    }

    suspend fun restore(): Either<ConnectorConfigurationError, Unit> = either {
        val row = readRow().bind() ?: return@either
        val settings = decrypt(row).bind()
        apply(settings, row.enabled)
    }

    private suspend fun loadEffective(): Either<ConnectorConfigurationError, EffectiveConfiguration> = either {
        val row = readRow().bind()
        if (row == null) {
            val settings =
                StoredGitHubConfiguration(
                    token = legacyConfig.token,
                    repositories = legacyConfig.repositories,
                    apiBaseUrl = legacyConfig.apiBaseUrl,
                    syncIntervalSeconds = legacyConfig.syncIntervalSeconds,
                )
            EffectiveConfiguration(
                settings = settings,
                enabled = settings.token.isNotBlank() && settings.repositories.isNotEmpty(),
                source = "environment",
            )
        } else {
            EffectiveConfiguration(decrypt(row).bind(), row.enabled, "database")
        }
    }

    private suspend fun readRow(): Either<ConnectorConfigurationError, EncryptedConnectorConfiguration?> =
        try {
            Either.Right(withContext(Dispatchers.IO) { repository.find(CONNECTOR_ID) })
        } catch (_: SQLException) {
            Either.Left(ConnectorConfigurationError.PersistenceFailure)
        }

    private fun decrypt(row: EncryptedConnectorConfiguration): Either<ConnectorConfigurationError, StoredGitHubConfiguration> {
        val configuredCipher = cipher ?: return Either.Left(ConnectorConfigurationError.EncryptionUnavailable)
        return try {
            val payload = configuredCipher.decrypt(EncryptedSecret(row.ciphertext, row.nonce), CONNECTOR_ID.toByteArray())
            Either.Right(json.decodeFromString(payload.toString(Charsets.UTF_8)))
        } catch (_: Exception) {
            Either.Left(ConnectorConfigurationError.StoredConfigurationInvalid)
        }
    }

    private fun validate(
        request: GitHubConfigurationRequest,
        currentToken: String,
    ): Either<ConnectorConfigurationError, ValidatedConfiguration> = either {
        val repositories = request.repositories.map(String::trim).filter(String::isNotBlank).distinct()
        val token = request.token.takeIf(String::isNotBlank) ?: currentToken
        ensure(request.syncIntervalSeconds in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS) {
            ConnectorConfigurationError.Invalid("Sync interval must be between 30 seconds and 30 days")
        }
        ensure(request.apiBaseUrl.isValidApiBaseUrl()) {
            ConnectorConfigurationError.Invalid("GitHub API base URL must be an absolute HTTP(S) URL without credentials, query, or fragment")
        }
        ensure(repositories.all(String::isGitHubRepository)) {
            ConnectorConfigurationError.Invalid("Repositories must use owner/name format")
        }
        ensure(token.length <= MAX_TOKEN_LENGTH) {
            ConnectorConfigurationError.Invalid("GitHub token is too long")
        }
        ensure(!request.enabled || (token.isNotBlank() && repositories.isNotEmpty())) {
            ConnectorConfigurationError.Invalid("An enabled GitHub connector requires a token and at least one repository")
        }
        ValidatedConfiguration(
            settings = StoredGitHubConfiguration(token, repositories, request.apiBaseUrl.trimEnd('/'), request.syncIntervalSeconds),
        )
    }

    private fun apply(
        settings: StoredGitHubConfiguration,
        enabled: Boolean,
    ) {
        if (enabled) {
            scheduler.schedule(
                GitHubConnector(
                    GitHubConfig(
                        token = settings.token,
                        repositories = settings.repositories,
                        apiBaseUrl = settings.apiBaseUrl,
                        syncIntervalSeconds = settings.syncIntervalSeconds,
                    ),
                    httpClient,
                ),
                settings.syncIntervalSeconds,
            )
        } else {
            scheduler.cancel(CONNECTOR_ID)
        }
    }

    private fun StoredGitHubConfiguration.toView(
        enabled: Boolean,
        source: String,
    ) =
        GitHubConfigurationView(
            enabled = enabled,
            tokenConfigured = token.isNotBlank(),
            repositories = repositories,
            apiBaseUrl = apiBaseUrl,
            syncIntervalSeconds = syncIntervalSeconds,
            source = source,
        )

    private data class EffectiveConfiguration(
        val settings: StoredGitHubConfiguration,
        val enabled: Boolean,
        val source: String,
    )

    private data class ValidatedConfiguration(val settings: StoredGitHubConfiguration)

    private companion object {
        const val CONNECTOR_ID = "github"
        const val MIN_INTERVAL_SECONDS = 30L
        const val MAX_INTERVAL_SECONDS = 2_592_000L
        const val MAX_TOKEN_LENGTH = 512
    }
}

private fun String.isGitHubRepository(): Boolean =
    matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))

private fun String.isValidApiBaseUrl(): Boolean {
    val uri = runCatching { URI(trim()) }.getOrNull() ?: return false
    return uri.scheme in setOf("http", "https") &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null
}
