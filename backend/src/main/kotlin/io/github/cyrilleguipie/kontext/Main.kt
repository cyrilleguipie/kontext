package io.github.cyrilleguipie.kontext

import arrow.continuations.SuspendApp
import arrow.core.raise.fold
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.resourceScope
import io.github.cyrilleguipie.kontext.api.ComponentHealth
import io.github.cyrilleguipie.kontext.config.AtlassianFlavor
import io.github.cyrilleguipie.kontext.config.KontextConfig
import io.github.cyrilleguipie.kontext.config.KontextConfig.Companion.validate
import io.github.cyrilleguipie.kontext.connector.impl.backstage.BackstageConnector
import io.github.cyrilleguipie.kontext.connector.impl.confluence.ConfluenceConnector
import io.github.cyrilleguipie.kontext.connector.impl.files.LocalFilesConnector
import io.github.cyrilleguipie.kontext.connector.impl.freshdesk.FreshdeskConnector
import io.github.cyrilleguipie.kontext.connector.impl.gitbook.GitBookConnector
import io.github.cyrilleguipie.kontext.connector.impl.github.GitHubConnector
import io.github.cyrilleguipie.kontext.connector.impl.gitlab.GitLabConnector
import io.github.cyrilleguipie.kontext.connector.impl.intercom.IntercomConnector
import io.github.cyrilleguipie.kontext.connector.impl.jira.JiraConnector
import io.github.cyrilleguipie.kontext.connector.impl.linear.LinearConnector
import io.github.cyrilleguipie.kontext.connector.impl.notion.NotionConnector
import io.github.cyrilleguipie.kontext.connector.impl.zendesk.ZendeskConnector
import io.github.cyrilleguipie.kontext.connector.ingestion.ConnectorSyncService
import io.github.cyrilleguipie.kontext.connector.ingestion.SyncResult
import io.github.cyrilleguipie.kontext.connector.spi.SourceConnector
import io.github.cyrilleguipie.kontext.ingestion.DocumentIngestionService
import io.github.cyrilleguipie.kontext.llm.ChatClient
import io.github.cyrilleguipie.kontext.llm.EmbeddingClient
import io.github.cyrilleguipie.kontext.llm.HttpClientFactory
import io.github.cyrilleguipie.kontext.llm.RagEngine
import io.github.cyrilleguipie.kontext.store.Database
import io.github.cyrilleguipie.kontext.store.Database.validateEmbeddingDimension
import io.github.cyrilleguipie.kontext.store.DocumentRepository
import io.github.cyrilleguipie.kontext.store.HybridSearch
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("io.github.cyrilleguipie.kontext.Main")

fun main() =
    SuspendApp {
        logger.info("Starting Kontext...")
        val config = loadConfig()
        logger.info(
            "Configuration loaded successfully: LLM model={}, embeddingDim={}, port={}",
            config.llm.chatModel,
            config.llm.embeddingDimension,
            config.server.port,
        )
        runWithResources(config)
    }

private fun loadConfig(): KontextConfig =
    fold(
        block = { validate() },
        recover = { error ->
            logger.error("Configuration validation failed: {}", error.message)
            exitProcess(1)
        },
        transform = { it },
    )

private suspend fun runWithResources(config: KontextConfig): Nothing =
    resourceScope {
        val dataSource = Database.dataSourceResource(config.database).bind()
        val httpClient = HttpClientFactory.createClientResource().bind()
        val connectorScope =
            resource({ CoroutineScope(SupervisorJob() + Dispatchers.IO) }) { scope, _ -> scope.cancel() }.bind()

        Database.migrate(dataSource, config.llm.embeddingDimension)
        validateDatabaseDimension(dataSource, config)

        val embeddingClient = EmbeddingClient(config.llm, httpClient)
        val documentRepository = DocumentRepository(dataSource)
        val ingestionService = DocumentIngestionService(embeddingClient, documentRepository)
        val syncJobs = buildSyncJobs(config, httpClient)
        val connectorSyncService =
            ConnectorSyncService(ingestionService, documentRepository, syncJobs.map { it.first })
        startSyncJobs(connectorScope, syncJobs, connectorSyncService)

        val ragEngine =
            RagEngine(
                config = config.llm,
                embeddingClient = embeddingClient,
                hybridSearch = HybridSearch(dataSource),
                chatClient = ChatClient(config.llm, httpClient),
            )
        createServerResource(
            config,
            dataSource,
            httpClient,
            ragEngine,
            ingestionService,
            documentRepository,
            connectorSyncService,
        ).bind()
        logger.info("Kontext is ready! Listening on http://localhost:{}", config.server.port)
        awaitCancellation()
    }

private fun validateDatabaseDimension(
    dataSource: DataSource,
    config: KontextConfig,
) {
    fold(
        block = { validateEmbeddingDimension(dataSource, config.llm.embeddingDimension) },
        recover = { error ->
            logger.error("Database dimension check failed: {}", error.message)
            exitProcess(1)
        },
        transform = {},
    )
}

private fun buildSyncJobs(
    config: KontextConfig,
    httpClient: HttpClient,
): List<Pair<SourceConnector, Long>> =
    buildList {
        addLocalFilesJob(config)
        addAtlassianJobs(config, httpClient)
        addKnowledgeWorkspaceJobs(config, httpClient)
        addDeveloperPlatformJobs(config, httpClient)
        addBackstageJob(config, httpClient)
        addCustomerSupportJobs(config, httpClient)
    }

private fun MutableList<Pair<SourceConnector, Long>>.addLocalFilesJob(config: KontextConfig) {
    if (config.localFiles.directory.isNotBlank()) {
        add(LocalFilesConnector(config.localFiles.directory) to config.localFiles.syncIntervalSeconds)
    }
}

private fun MutableList<Pair<SourceConnector, Long>>.addBackstageJob(
    config: KontextConfig,
    httpClient: HttpClient,
) {
    val backstage = config.backstage
    if (backstage.baseUrl.isNotBlank()) {
        add(BackstageConnector(backstage, httpClient) to backstage.syncIntervalSeconds)
    }
}

private fun MutableList<Pair<SourceConnector, Long>>.addKnowledgeWorkspaceJobs(
    config: KontextConfig,
    httpClient: HttpClient,
) {
    if (config.notion.token.isNotBlank()) {
        add(NotionConnector(config.notion, httpClient) to config.notion.syncIntervalSeconds)
    }
    if (config.gitBook.token.isNotBlank() && config.gitBook.organizationId.isNotBlank()) {
        add(GitBookConnector(config.gitBook, httpClient) to config.gitBook.syncIntervalSeconds)
    }
}

private fun MutableList<Pair<SourceConnector, Long>>.addDeveloperPlatformJobs(
    config: KontextConfig,
    httpClient: HttpClient,
) {
    addGitHubJob(config, httpClient)
    addGitLabJob(config, httpClient)
    if (config.linear.apiKey.isNotBlank()) {
        add(LinearConnector(config.linear, httpClient) to config.linear.syncIntervalSeconds)
    }
}

private fun MutableList<Pair<SourceConnector, Long>>.addGitHubJob(
    config: KontextConfig,
    httpClient: HttpClient,
) {
    when {
        config.gitHub.token.isBlank() -> Unit
        config.gitHub.repositories.isEmpty() ->
            logger.warn("GitHub sync disabled because KONTEXT_GITHUB_REPOSITORIES is empty")
        else -> add(GitHubConnector(config.gitHub, httpClient) to config.gitHub.syncIntervalSeconds)
    }
}

private fun MutableList<Pair<SourceConnector, Long>>.addGitLabJob(
    config: KontextConfig,
    httpClient: HttpClient,
) {
    val tokenConfigured = config.gitLab.token.isNotBlank()
    val projectsConfigured = config.gitLab.projects.isNotEmpty()
    when {
        tokenConfigured && projectsConfigured ->
            add(GitLabConnector(config.gitLab, httpClient) to config.gitLab.syncIntervalSeconds)
        tokenConfigured -> logger.warn("GitLab sync disabled because KONTEXT_GITLAB_PROJECTS is empty")
        projectsConfigured -> logger.warn("GitLab sync disabled because KONTEXT_GITLAB_TOKEN is empty")
    }
}

private fun MutableList<Pair<SourceConnector, Long>>.addCustomerSupportJobs(
    config: KontextConfig,
    httpClient: HttpClient,
) {
    val zendesk = config.zendesk
    val fullyConfigured = zendesk.baseUrl.isNotBlank() && zendesk.email.isNotBlank() && zendesk.apiToken.isNotBlank()
    if (fullyConfigured) {
        add(ZendeskConnector(zendesk, httpClient) to zendesk.syncIntervalSeconds)
    } else if (listOf(zendesk.baseUrl, zendesk.email, zendesk.apiToken).any(String::isNotBlank)) {
        logger.warn("Zendesk sync disabled because base URL, account email, and API token are all required")
    }
    if (config.intercom.accessToken.isNotBlank()) {
        add(IntercomConnector(config.intercom, httpClient) to config.intercom.syncIntervalSeconds)
    }
    val freshdesk = config.freshdesk
    when {
        freshdesk.baseUrl.isNotBlank() && freshdesk.apiKey.isNotBlank() ->
            add(FreshdeskConnector(freshdesk, httpClient) to freshdesk.syncIntervalSeconds)
        freshdesk.baseUrl.isNotBlank() || freshdesk.apiKey.isNotBlank() ->
            logger.warn("Freshdesk sync disabled because both base URL and API key are required")
    }
}

private fun MutableList<Pair<SourceConnector, Long>>.addAtlassianJobs(
    config: KontextConfig,
    httpClient: HttpClient,
) {
    val jiraConfigured = config.atlassian.jira.baseUrl.isNotBlank()
    val authConfigured =
        config.atlassian.auth.token.isNotBlank() &&
            (config.atlassian.flavor == AtlassianFlavor.DATACENTER || config.atlassian.auth.email.isNotBlank())
    val interval = config.atlassian.syncIntervalSeconds
    when {
        jiraConfigured && authConfigured -> add(JiraConnector(config.atlassian, httpClient) to interval)
        jiraConfigured -> logger.warn("Jira sync disabled because Atlassian credentials are incomplete")
    }
    val confluenceConfigured = config.atlassian.confluence.baseUrl.isNotBlank()
    when {
        confluenceConfigured && authConfigured -> add(ConfluenceConnector(config.atlassian, httpClient) to interval)
        confluenceConfigured -> logger.warn("Confluence sync disabled because Atlassian credentials are incomplete")
    }
}

private fun startSyncJobs(
    scope: CoroutineScope,
    jobs: List<Pair<SourceConnector, Long>>,
    syncService: ConnectorSyncService,
) {
    jobs.forEach { (connector, intervalSeconds) ->
        scope.launch {
            while (isActive) {
                runCatching { syncService.sync(connector) }.fold(
                    onSuccess = ::logSyncSuccess,
                    onFailure = { cause ->
                        when (cause) {
                            is CancellationException -> throw cause
                            else -> logger.warn("Connector {} sync failed: {}", connector.connectorId, cause.message)
                        }
                    },
                )
                delay(intervalSeconds.seconds)
            }
        }
    }
}

private fun logSyncSuccess(result: SyncResult) {
    logger.info(
        "Connector {} sync complete: indexed={}, deleted={}",
        result.connectorId,
        result.indexed,
        result.deleted,
    )
}

private fun createServerResource(
    config: KontextConfig,
    dataSource: DataSource,
    httpClient: HttpClient,
    ragEngine: RagEngine,
    ingestionService: DocumentIngestionService,
    documentRepository: DocumentRepository,
    connectorSyncService: ConnectorSyncService,
) = resource({
    val server =
        embeddedServer(Netty, port = config.server.port) {
            module(
                config,
                dataSource,
                ragEngine,
                ingestionService,
                documentRepository,
                connectorSyncService,
                checkLlmHealth = { checkLlmHealth(config, httpClient) },
            )
        }
    server.start(wait = false)
    server
}) { server, exitCase ->
    logger.info("Shutting down Ktor server (exitCase: {})...", exitCase)
    server.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
}

private suspend fun checkLlmHealth(
    config: KontextConfig,
    httpClient: HttpClient,
): ComponentHealth =
    runCatching {
        httpClient.get("${config.llm.baseUrl}/models") {
            if (config.llm.apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${config.llm.apiKey}")
        }
    }.fold(
        onSuccess = { response ->
            if (response.status.value in 200..299) {
                ComponentHealth("UP", "LLM endpoint reachable")
            } else {
                ComponentHealth("DOWN", "LLM endpoint returned HTTP ${response.status.value}")
            }
        },
        onFailure = { cause ->
            when (cause) {
                is CancellationException -> throw cause
                is IOException, is IllegalStateException, is SerializationException, is SQLException ->
                    ComponentHealth("DOWN", cause.message ?: "LLM endpoint unavailable")
                else -> throw cause
            }
        },
    )
