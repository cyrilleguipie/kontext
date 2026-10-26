package io.github.cyrilleguipie.kontext.config

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.cyrilleguipie.kontext.domain.KontextError.ConfigError

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

data class KontextConfig(
    val atlassian: AtlassianConfig,
    val llm: LlmConfig,
    val database: DatabaseConfig,
    val server: ServerConfig,
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

            return KontextConfig(
                atlassian = atlassian,
                llm = llm,
                database = database,
                server = server,
            )
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

            return AtlassianConfig(
                flavor = flavor,
                jira = validateJira(root),
                confluence = validateConfluence(root),
                auth = validateAuth(root),
                maxConcurrency = maxConcurrency,
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
                    root.getStringList("atlassian.jira.projects").map { it.trim() }.filter { it.isNotBlank() }
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
                    root.getStringList("atlassian.confluence.spaces").map { it.trim() }.filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
            return AtlassianConfluenceConfig(baseUrl = confluenceUrl, spaces = confluenceSpaces)
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
