package io.github.cyrilleguipie.kontext.config

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueType
import io.github.cyrilleguipie.kontext.domain.KontextError.ConfigError
import java.net.URI

enum class AtlassianFlavor {
    CLOUD,
    DATACENTER,
    ;

    companion object {
        fun fromString(value: String): AtlassianFlavor? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

data class AtlassianJiraConfig(
    val baseUrl: String,
    val projects: List<String>,
    val jqlExtra: String,
)

data class AtlassianConfluenceConfig(
    val baseUrl: String,
    val spaces: List<String>,
)

data class AtlassianAuthConfig(
    val email: String,
    val token: String,
) {
    override fun toString(): String = "AtlassianAuthConfig(email=$email, token=***)"
}

data class AtlassianConfig(
    val flavor: AtlassianFlavor,
    val jira: AtlassianJiraConfig,
    val confluence: AtlassianConfluenceConfig,
    val auth: AtlassianAuthConfig,
    val maxConcurrency: Int,
    val syncIntervalSeconds: Long,
)

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val chatModel: String,
    val embeddingModel: String,
    val embeddingDimension: Int,
    val temperature: Double,
    val maxContextChunks: Int,
) {
    override fun toString(): String =
        "LlmConfig(baseUrl=$baseUrl, apiKey=***, chatModel=$chatModel, " +
            "embeddingModel=$embeddingModel, embeddingDimension=$embeddingDimension, " +
            "temperature=$temperature, maxContextChunks=$maxContextChunks)"
}

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    override fun toString(): String = "DatabaseConfig(url=$url, user=$user, password=***)"
}

data class ServerConfig(
    val port: Int,
)

data class AdminConfig(
    val token: String,
) {
    override fun toString(): String = "AdminConfig(token=***)"
}

data class LocalFilesConfig(
    val directory: String,
    val syncIntervalSeconds: Long,
)

data class NotionConfig(
    val token: String,
    val apiVersion: String,
    val syncIntervalSeconds: Long,
) {
    override fun toString(): String =
        "NotionConfig(token=***, apiVersion=$apiVersion, syncIntervalSeconds=$syncIntervalSeconds)"
}

data class GitBookConfig(
    val token: String,
    val organizationId: String,
    val spaces: List<String>,
    val syncIntervalSeconds: Long,
) {
    override fun toString(): String =
        "GitBookConfig(token=***, organizationId=$organizationId, spaces=$spaces, " +
            "syncIntervalSeconds=$syncIntervalSeconds)"
}

data class GitHubConfig(
    val token: String,
    val repositories: List<String>,
    val apiBaseUrl: String,
    val syncIntervalSeconds: Long,
) {
    override fun toString(): String =
        "GitHubConfig(token=***, repositories=$repositories, apiBaseUrl=$apiBaseUrl, " +
            "syncIntervalSeconds=$syncIntervalSeconds)"
}

data class GitLabConfig(
    val token: String,
    val projects: List<String>,
    val apiBaseUrl: String,
    val syncIntervalSeconds: Long,
) {
    override fun toString(): String =
        "GitLabConfig(token=***, projects=$projects, apiBaseUrl=$apiBaseUrl, " +
            "syncIntervalSeconds=$syncIntervalSeconds)"
}

data class LinearConfig(
    val apiKey: String,
    val apiBaseUrl: String,
    val syncIntervalSeconds: Long,
) {
    override fun toString(): String =
        "LinearConfig(apiKey=***, apiBaseUrl=$apiBaseUrl, " +
            "syncIntervalSeconds=$syncIntervalSeconds)"
}

data class ZendeskConfig(
    val baseUrl: String,
    val email: String,
    val apiToken: String,
    val syncIntervalSeconds: Long,
) {
    override fun toString(): String =
        "ZendeskConfig(baseUrl=$baseUrl, email=$email, apiToken=***, " +
            "syncIntervalSeconds=$syncIntervalSeconds)"
}

data class IntercomConfig(
    val accessToken: String,
    val apiBaseUrl: String,
    val apiVersion: String,
    val syncIntervalSeconds: Long,
) {
    override fun toString(): String =
        "IntercomConfig(accessToken=***, apiBaseUrl=$apiBaseUrl, apiVersion=$apiVersion, " +
            "syncIntervalSeconds=$syncIntervalSeconds)"
}

data class FreshdeskConfig(
    val baseUrl: String,
    val apiKey: String,
    val syncIntervalSeconds: Long,
) {
    override fun toString(): String =
        "FreshdeskConfig(baseUrl=$baseUrl, apiKey=***, syncIntervalSeconds=$syncIntervalSeconds)"
}

data class BackstageConfig(
    val baseUrl: String,
    val token: String,
    val namespaces: List<String>,
    val techDocsEnabled: Boolean,
    val syncIntervalSeconds: Long,
) {
    override fun toString(): String =
        "BackstageConfig(baseUrl=$baseUrl, token=***, namespaces=$namespaces, techDocsEnabled=$techDocsEnabled, " +
            "syncIntervalSeconds=$syncIntervalSeconds)"
}

data class SecurityConfig(
    val secretEncryptionKey: String,
) {
    override fun toString(): String = "SecurityConfig(secretEncryptionKey=***)"
}

data class KontextConfig(
    val atlassian: AtlassianConfig,
    val llm: LlmConfig,
    val database: DatabaseConfig,
    val server: ServerConfig,
    val admin: AdminConfig,
    val localFiles: LocalFilesConfig,
    val notion: NotionConfig,
    val gitBook: GitBookConfig,
    val gitHub: GitHubConfig,
    val gitLab: GitLabConfig,
    val linear: LinearConfig,
    val zendesk: ZendeskConfig,
    val intercom: IntercomConfig,
    val freshdesk: FreshdeskConfig,
    val backstage: BackstageConfig,
    val security: SecurityConfig,
) {
    companion object {
        fun load(rawConfig: Config = ConfigFactory.load()): KontextConfig {
            return arrow.core.raise.fold(
                block = { validate(rawConfig) },
                recover = { error -> throw IllegalArgumentException(error.message) },
                transform = { it },
            )
        }

        fun Raise<ConfigError>.validate(rawConfig: Config = ConfigFactory.load()): KontextConfig {
            val root = if (rawConfig.hasPath("kontext")) rawConfig.getConfig("kontext") else rawConfig

            val server = validateServer(root)
            val database = validateDatabase(root)
            val llm = validateLlm(root)
            val atlassian = validateAtlassian(root)
            val admin = validateAdmin(root)
            val localFiles = validateLocalFiles(root)
            val notion = validateNotion(root)
            val gitBook = validateGitBook(root)
            val gitHub = validateGitHub(root)
            val gitLab = validateGitLab(root)
            val linear = validateLinear(root)
            val zendesk = validateZendesk(root)
            val intercom = validateIntercom(root)
            val freshdesk = validateFreshdesk(root)
            val backstage = validateBackstage(root)
            val secretEncryptionKey = root.stringOrEmpty("security.secret-encryption-key")
            val encryptionKeyBytes =
                secretEncryptionKey.takeIf(String::isNotBlank)?.let { key ->
                    runCatching { java.util.Base64.getDecoder().decode(key) }.getOrNull()
                }
            ensure(secretEncryptionKey.isBlank() || encryptionKeyBytes?.size == 32) {
                ConfigError.Invalid(
                    "security.secret-encryption-key",
                    "Secret encryption key must be a base64-encoded 32-byte key",
                )
            }
            val security = SecurityConfig(secretEncryptionKey = secretEncryptionKey)

            return KontextConfig(
                atlassian = atlassian,
                llm = llm,
                database = database,
                server = server,
                admin = admin,
                localFiles = localFiles,
                notion = notion,
                gitBook = gitBook,
                gitHub = gitHub,
                gitLab = gitLab,
                linear = linear,
                zendesk = zendesk,
                intercom = intercom,
                freshdesk = freshdesk,
                backstage = backstage,
                security = security,
            )
        }

        private fun validateAdmin(root: Config): AdminConfig {
            val token = if (root.hasPath("admin.token")) root.getString("admin.token") else ""
            return AdminConfig(token = token.trim())
        }

        private fun Raise<ConfigError>.validateLocalFiles(root: Config): LocalFilesConfig {
            val directory =
                if (root.hasPath("sources.local-files.directory")) {
                    root.getString("sources.local-files.directory").trim()
                } else {
                    ""
                }
            val syncIntervalSeconds =
                if (root.hasPath("sources.local-files.sync-interval-seconds")) {
                    root.getLong("sources.local-files.sync-interval-seconds")
                } else {
                    300L
                }
            ensure(syncIntervalSeconds >= 30) {
                ConfigError.Invalid(
                    "sources.local-files.sync-interval-seconds",
                    "Sync interval must be at least 30 seconds",
                )
            }
            return LocalFilesConfig(directory, syncIntervalSeconds)
        }

        private fun Raise<ConfigError>.validateNotion(root: Config): NotionConfig =
            NotionConfig(
                token = if (root.hasPath("notion.token")) root.getString("notion.token").trim() else "",
                apiVersion =
                    if (root.hasPath("notion.api-version")) {
                        root.getString("notion.api-version").trim()
                    } else {
                        "2026-03-11"
                    },
                syncIntervalSeconds = configuredSyncInterval(root, "notion.sync-interval-seconds"),
            )

        private fun Raise<ConfigError>.validateGitBook(root: Config): GitBookConfig =
            GitBookConfig(
                token = if (root.hasPath("gitbook.token")) root.getString("gitbook.token").trim() else "",
                organizationId =
                    if (root.hasPath(
                            "gitbook.organization-id",
                        )
                    ) {
                        root.getString("gitbook.organization-id").trim()
                    } else {
                        ""
                    },
                spaces =
                    if (root.hasPath("gitbook.spaces")) {
                        readStringList(root, "gitbook.spaces").map { it.trim() }.filter { it.isNotBlank() }
                    } else {
                        emptyList()
                    },
                syncIntervalSeconds = configuredSyncInterval(root, "gitbook.sync-interval-seconds"),
            )

        private fun Raise<ConfigError>.validateGitHub(root: Config): GitHubConfig {
            val token = if (root.hasPath("github.token")) root.getString("github.token").trim() else ""
            val repositories =
                if (root.hasPath("github.repositories")) {
                    readStringList(root, "github.repositories").map { it.trim() }.filter { it.isNotBlank() }.distinct()
                } else {
                    emptyList()
                }
            ensure(
                repositories.none { repository ->
                    val parts = repository.split('/')
                    parts.size != 2 || parts.any { !it.matches(Regex("[A-Za-z0-9_.-]+")) }
                },
            ) {
                ConfigError.Invalid("github.repositories", "Repositories must use owner/repository format")
            }
            val apiBaseUrl =
                if (root.hasPath("github.api-base-url")) {
                    root.getString("github.api-base-url").trim().trimEnd('/')
                } else {
                    "https://api.github.com"
                }
            val parsedApiBaseUrl = runCatching { URI(apiBaseUrl) }.getOrNull()
            ensure(
                parsedApiBaseUrl?.scheme in setOf("http", "https") &&
                    !parsedApiBaseUrl?.host.isNullOrBlank(),
            ) {
                ConfigError.Invalid("github.api-base-url", "GitHub API base URL must be an absolute HTTP or HTTPS URL")
            }
            return GitHubConfig(
                token = token,
                repositories = repositories,
                apiBaseUrl = apiBaseUrl,
                syncIntervalSeconds = configuredSyncInterval(root, "github.sync-interval-seconds"),
            )
        }

        private fun Raise<ConfigError>.validateGitLab(root: Config): GitLabConfig {
            val token = if (root.hasPath("gitlab.token")) root.getString("gitlab.token").trim() else ""
            val projects =
                if (root.hasPath("gitlab.projects")) {
                    readStringList(root, "gitlab.projects").map(String::trim).filter(String::isNotBlank).distinct()
                } else {
                    emptyList()
                }
            val projectPathPattern = Regex("(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+")
            ensure(projects.all(projectPathPattern::matches)) {
                ConfigError.Invalid("gitlab.projects", "Projects must be GitLab project paths such as group/project")
            }
            val apiBaseUrl =
                if (root.hasPath("gitlab.api-base-url")) {
                    root.getString("gitlab.api-base-url").trim().trimEnd('/')
                } else {
                    "https://gitlab.com/api/v4"
                }
            val parsedApiBaseUrl = runCatching { URI(apiBaseUrl) }.getOrNull()
            ensure(parsedApiBaseUrl?.scheme in setOf("http", "https") && !parsedApiBaseUrl?.host.isNullOrBlank()) {
                ConfigError.Invalid("gitlab.api-base-url", "GitLab API base URL must be an absolute HTTP or HTTPS URL")
            }
            return GitLabConfig(
                token = token,
                projects = projects,
                apiBaseUrl = apiBaseUrl,
                syncIntervalSeconds = configuredSyncInterval(root, "gitlab.sync-interval-seconds"),
            )
        }

        private fun Raise<ConfigError>.validateLinear(root: Config): LinearConfig {
            val apiKey = if (root.hasPath("linear.api-key")) root.getString("linear.api-key").trim() else ""
            val apiBaseUrl =
                if (root.hasPath("linear.api-base-url")) {
                    root.getString("linear.api-base-url").trim().trimEnd('/')
                } else {
                    "https://api.linear.app/graphql"
                }
            val parsedApiBaseUrl = runCatching { URI(apiBaseUrl) }.getOrNull()
            ensure(parsedApiBaseUrl?.scheme in setOf("http", "https") && !parsedApiBaseUrl?.host.isNullOrBlank()) {
                ConfigError.Invalid("linear.api-base-url", "Linear API URL must be an absolute HTTP or HTTPS URL")
            }
            return LinearConfig(
                apiKey = apiKey,
                apiBaseUrl = apiBaseUrl,
                syncIntervalSeconds = configuredSyncInterval(root, "linear.sync-interval-seconds"),
            )
        }

        private fun Raise<ConfigError>.validateZendesk(root: Config): ZendeskConfig {
            val configuredBaseUrl =
                root.takeIf {
                    it.hasPath(
                        "zendesk.base-url",
                    )
                }?.getString("zendesk.base-url").orEmpty()
            val baseUrl = configuredBaseUrl.trim().trimEnd('/')
            ensure(baseUrl.isBlank() || baseUrl.isZendeskOrigin()) {
                ConfigError.Invalid("zendesk.base-url", "Zendesk base URL must be an absolute HTTP or HTTPS origin")
            }
            return ZendeskConfig(
                baseUrl = baseUrl,
                email = if (root.hasPath("zendesk.email")) root.getString("zendesk.email").trim() else "",
                apiToken = if (root.hasPath("zendesk.api-token")) root.getString("zendesk.api-token").trim() else "",
                syncIntervalSeconds = configuredSyncInterval(root, "zendesk.sync-interval-seconds"),
            )
        }

        private fun Raise<ConfigError>.validateIntercom(root: Config): IntercomConfig {
            val accessToken = root.stringOrEmpty("intercom.access-token")
            val apiBaseUrl = root.stringOrDefault("intercom.api-base-url", "https://api.intercom.io").trimEnd('/')
            val apiVersion = root.stringOrDefault("intercom.api-version", "2.17").trim()
            val parsedBaseUrl = runCatching { URI(apiBaseUrl) }.getOrNull()
            ensure(parsedBaseUrl?.scheme in setOf("http", "https") && !parsedBaseUrl?.host.isNullOrBlank()) {
                ConfigError.Invalid("intercom.api-base-url", "Intercom API URL must be an absolute HTTP or HTTPS URL")
            }
            ensure(apiVersion.matches(Regex("\\d+\\.\\d+"))) {
                ConfigError.Invalid("intercom.api-version", "Intercom API version must use major.minor format")
            }
            return IntercomConfig(
                accessToken = accessToken,
                apiBaseUrl = apiBaseUrl,
                apiVersion = apiVersion,
                syncIntervalSeconds = configuredSyncInterval(root, "intercom.sync-interval-seconds"),
            )
        }

        private fun Raise<ConfigError>.validateFreshdesk(root: Config): FreshdeskConfig {
            val baseUrl = root.stringOrEmpty("freshdesk.base-url").trimEnd('/')
            val parsedBaseUrl = runCatching { URI(baseUrl) }.getOrNull()
            ensure(baseUrl.isBlank() || parsedBaseUrl?.isFreshdeskOrigin() == true) {
                ConfigError.Invalid(
                    "freshdesk.base-url",
                    "Freshdesk URL must be an absolute HTTPS origin without path, query, or fragment",
                )
            }
            return FreshdeskConfig(
                baseUrl = baseUrl,
                apiKey = root.stringOrEmpty("freshdesk.api-key"),
                syncIntervalSeconds = configuredSyncInterval(root, "freshdesk.sync-interval-seconds"),
            )
        }

        private fun Raise<ConfigError>.validateBackstage(root: Config): BackstageConfig {
            val baseUrl = root.stringOrEmpty("backstage.base-url").trimEnd('/')
            val parsedBaseUrl = runCatching { URI(baseUrl) }.getOrNull()
            ensure(baseUrl.isBlank() || parsedBaseUrl?.isBackstageBaseUrl() == true) {
                ConfigError.Invalid(
                    "backstage.base-url",
                    "Backstage URL must be an absolute HTTP or HTTPS URL without query or fragment",
                )
            }
            val namespaces =
                if (root.hasPath("backstage.namespaces")) {
                    readStringList(root, "backstage.namespaces")
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinctBy(String::lowercase)
                } else {
                    emptyList()
                }
            ensure(namespaces.all { it.matches(Regex("[A-Za-z0-9_.-]+")) }) {
                ConfigError.Invalid(
                    "backstage.namespaces",
                    "Namespaces must contain only letters, digits, dots, underscores, or hyphens",
                )
            }
            return BackstageConfig(
                baseUrl = baseUrl,
                token = root.stringOrEmpty("backstage.token"),
                namespaces = namespaces,
                techDocsEnabled =
                    root.hasPath("backstage.techdocs-enabled") && root.getBoolean("backstage.techdocs-enabled"),
                syncIntervalSeconds = configuredSyncInterval(root, "backstage.sync-interval-seconds"),
            )
        }

        private fun Config.stringOrEmpty(path: String): String = if (hasPath(path)) getString(path).trim() else ""

        private fun Config.stringOrDefault(
            path: String,
            default: String,
        ): String = if (hasPath(path)) getString(path).trim() else default

        private fun Raise<ConfigError>.configuredSyncInterval(
            root: Config,
            path: String,
        ): Long {
            val interval = if (root.hasPath(path)) root.getLong(path) else 86_400L
            ensure(interval >= 30) { ConfigError.Invalid(path, "Sync interval must be at least 30 seconds") }
            return interval
        }

        private fun Raise<ConfigError>.validateServer(root: Config): ServerConfig {
            val port = if (root.hasPath("server.port")) root.getInt("server.port") else 8080
            ensure(port in 1..65535) {
                ConfigError.Invalid("server.port", "Port must be between 1 and 65535, found $port")
            }
            return ServerConfig(port = port)
        }

        private fun Raise<ConfigError>.validateDatabase(root: Config): DatabaseConfig {
            ensure(root.hasPath("database.url")) { ConfigError.Missing("kontext.database.url") }
            val dbUrl = root.getString("database.url").trim()
            ensure(dbUrl.isNotBlank()) { ConfigError.Invalid("database.url", "Database URL cannot be blank") }

            ensure(root.hasPath("database.user")) { ConfigError.Missing("kontext.database.user") }
            val dbUser = root.getString("database.user").trim()
            ensure(dbUser.isNotBlank()) { ConfigError.Invalid("database.user", "Database user cannot be blank") }

            val dbPassword = if (root.hasPath("database.password")) root.getString("database.password") else ""
            return DatabaseConfig(url = dbUrl, user = dbUser, password = dbPassword)
        }

        private fun Raise<ConfigError>.validateLlm(root: Config): LlmConfig {
            ensure(root.hasPath("llm.base-url")) { ConfigError.Missing("kontext.llm.base-url") }
            val llmBaseUrl = root.getString("llm.base-url").trim().trimEnd('/')
            ensure(llmBaseUrl.isNotBlank()) { ConfigError.Invalid("llm.base-url", "LLM base URL cannot be blank") }

            val llmApiKey = if (root.hasPath("llm.api-key")) root.getString("llm.api-key") else ""

            ensure(root.hasPath("llm.chat-model")) { ConfigError.Missing("kontext.llm.chat-model") }
            val chatModel = root.getString("llm.chat-model").trim()
            ensure(chatModel.isNotBlank()) { ConfigError.Invalid("llm.chat-model", "Chat model cannot be blank") }

            ensure(root.hasPath("llm.embedding-model")) { ConfigError.Missing("kontext.llm.embedding-model") }
            val embeddingModel = root.getString("llm.embedding-model").trim()
            ensure(embeddingModel.isNotBlank()) {
                ConfigError.Invalid("llm.embedding-model", "Embedding model cannot be blank")
            }

            val embeddingDim =
                if (root.hasPath("llm.embedding-dimension")) {
                    root.getInt("llm.embedding-dimension")
                } else {
                    1024
                }
            ensure(embeddingDim > 0) {
                ConfigError.Invalid("llm.embedding-dimension", "Embedding dimension must be > 0, found $embeddingDim")
            }

            val temperature = if (root.hasPath("llm.temperature")) root.getDouble("llm.temperature") else 0.2
            ensure(temperature in 0.0..2.0) {
                ConfigError.Invalid("llm.temperature", "Temperature must be between 0.0 and 2.0, found $temperature")
            }

            val maxContextChunks =
                if (root.hasPath("llm.max-context-chunks")) {
                    root.getInt("llm.max-context-chunks")
                } else {
                    8
                }
            ensure(maxContextChunks > 0) {
                ConfigError.Invalid("llm.max-context-chunks", "Max context chunks must be > 0")
            }

            return LlmConfig(
                baseUrl = llmBaseUrl,
                apiKey = llmApiKey,
                chatModel = chatModel,
                embeddingModel = embeddingModel,
                embeddingDimension = embeddingDim,
                temperature = temperature,
                maxContextChunks = maxContextChunks,
            )
        }

        private fun Raise<ConfigError>.validateAtlassian(root: Config): AtlassianConfig {
            val flavorStr =
                if (root.hasPath("atlassian.flavor")) {
                    root.getString("atlassian.flavor")
                } else {
                    "cloud"
                }
            val flavor =
                AtlassianFlavor.fromString(flavorStr)
                    ?: raise(
                        ConfigError.Invalid(
                            "atlassian.flavor",
                            "Unknown flavor '$flavorStr'. Must be cloud or datacenter",
                        ),
                    )

            val maxConcurrency =
                if (root.hasPath("atlassian.max-concurrency")) {
                    root.getInt("atlassian.max-concurrency")
                } else {
                    4
                }
            ensure(maxConcurrency > 0) {
                ConfigError.Invalid(
                    "atlassian.max-concurrency",
                    "Max concurrency must be > 0, found $maxConcurrency",
                )
            }

            val syncIntervalSeconds =
                if (root.hasPath("atlassian.sync-interval-seconds")) {
                    root.getLong("atlassian.sync-interval-seconds")
                } else {
                    86_400L
                }
            ensure(syncIntervalSeconds >= 30) {
                ConfigError.Invalid("atlassian.sync-interval-seconds", "Sync interval must be at least 30 seconds")
            }

            return AtlassianConfig(
                flavor = flavor,
                jira = validateJira(root),
                confluence = validateConfluence(root),
                auth = validateAuth(root),
                maxConcurrency = maxConcurrency,
                syncIntervalSeconds = syncIntervalSeconds,
            )
        }

        private fun validateJira(root: Config): AtlassianJiraConfig {
            val jiraUrl =
                if (root.hasPath("atlassian.jira.base-url")) {
                    root.getString("atlassian.jira.base-url").trim().trimEnd('/')
                } else {
                    ""
                }
            val jiraProjects =
                if (root.hasPath("atlassian.jira.projects")) {
                    readStringList(root, "atlassian.jira.projects").map { it.trim() }.filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
            val jiraJqlExtra =
                if (root.hasPath("atlassian.jira.jql-extra")) {
                    root.getString("atlassian.jira.jql-extra").trim()
                } else {
                    ""
                }
            return AtlassianJiraConfig(baseUrl = jiraUrl, projects = jiraProjects, jqlExtra = jiraJqlExtra)
        }

        private fun validateConfluence(root: Config): AtlassianConfluenceConfig {
            val confluenceUrl =
                if (root.hasPath("atlassian.confluence.base-url")) {
                    root.getString("atlassian.confluence.base-url").trim().trimEnd('/')
                } else {
                    ""
                }
            val confluenceSpaces =
                if (root.hasPath("atlassian.confluence.spaces")) {
                    readStringList(root, "atlassian.confluence.spaces").map { it.trim() }.filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
            return AtlassianConfluenceConfig(baseUrl = confluenceUrl, spaces = confluenceSpaces)
        }

        private fun readStringList(
            root: Config,
            path: String,
        ): List<String> =
            when (root.getValue(path).valueType()) {
                ConfigValueType.LIST -> root.getStringList(path)
                ConfigValueType.STRING -> root.getString(path).split(',')
                else -> emptyList()
            }

        private fun validateAuth(root: Config): AtlassianAuthConfig {
            val authEmail =
                if (root.hasPath("atlassian.auth.email")) {
                    root.getString("atlassian.auth.email").trim()
                } else {
                    ""
                }
            val authToken =
                if (root.hasPath("atlassian.auth.token")) {
                    root.getString("atlassian.auth.token").trim()
                } else {
                    ""
                }
            return AtlassianAuthConfig(email = authEmail, token = authToken)
        }
    }
}

private fun String.isZendeskOrigin(): Boolean {
    val uri = runCatching { URI(this) }.getOrNull() ?: return false
    return uri.scheme in setOf("http", "https") &&
        !uri.host.isNullOrBlank() &&
        uri.rawPath.orEmpty() in setOf("", "/") &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null
}

private fun URI.isFreshdeskOrigin(): Boolean =
    scheme == "https" &&
        !host.isNullOrBlank() &&
        rawPath.orEmpty() in setOf("", "/") &&
        rawUserInfo == null &&
        rawQuery == null &&
        rawFragment == null

private fun URI.isBackstageBaseUrl(): Boolean =
    scheme in setOf("http", "https") &&
        !host.isNullOrBlank() &&
        rawUserInfo == null &&
        rawQuery == null &&
        rawFragment == null
