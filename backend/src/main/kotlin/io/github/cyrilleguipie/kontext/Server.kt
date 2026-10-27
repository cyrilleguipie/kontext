package io.github.cyrilleguipie.kontext

import io.github.cyrilleguipie.kontext.api.ComponentHealth
import io.github.cyrilleguipie.kontext.api.adminRoutes
import io.github.cyrilleguipie.kontext.api.chatRoutes
import io.github.cyrilleguipie.kontext.api.healthRoutes
import io.github.cyrilleguipie.kontext.api.localFileRoutes
import io.github.cyrilleguipie.kontext.config.KontextConfig
import io.github.cyrilleguipie.kontext.connector.ingestion.ConnectorSyncService
import io.github.cyrilleguipie.kontext.ingestion.DocumentIngestionService
import io.github.cyrilleguipie.kontext.llm.RagEngine
import io.github.cyrilleguipie.kontext.store.DocumentRepository
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val serverLogger = LoggerFactory.getLogger("io.github.cyrilleguipie.kontext.Server")

fun Application.module(
    config: KontextConfig,
    dataSource: DataSource,
    ragEngine: RagEngine? = null,
    ingestionService: DocumentIngestionService? = null,
    documentRepository: DocumentRepository? = null,
    connectorSyncService: ConnectorSyncService? = null,
    checkLlmHealth: suspend () -> ComponentHealth = {
        ComponentHealth(status = "UP", details = "LLM health check initialized")
    },
) {
    serverLogger.debug("Configuring Ktor server module for port {}", config.server.port)

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            },
        )
    }

    install(SSE)

    routing {
        healthRoutes(dataSource, checkLlmHealth)
        localFileRoutes(config.localFiles.directory)
        ragEngine?.let { chatRoutes(it) }
        if (ingestionService != null && documentRepository != null) {
            adminRoutes(config.admin.token, ingestionService, documentRepository, connectorSyncService)
        }
    }
}
