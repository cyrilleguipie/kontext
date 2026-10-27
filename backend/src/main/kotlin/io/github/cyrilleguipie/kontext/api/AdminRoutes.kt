package io.github.cyrilleguipie.kontext.api

import io.github.cyrilleguipie.kontext.connector.ingestion.ConnectorSyncService
import io.github.cyrilleguipie.kontext.ingestion.DocumentIngestionService
import io.github.cyrilleguipie.kontext.store.DocumentRepository
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class IngestDocumentRequest(
    val externalId: String,
    val title: String,
    val body: String,
    val url: String,
    val metadata: Map<String, String> = emptyMap(),
    val updatedAt: String? = null,
)

@Serializable
data class IngestDocumentResponse(
    val externalId: String,
    val chunkCount: Int,
    val unchanged: Boolean,
)

@Serializable
data class AdminSourceStats(
    val source: String,
    val documents: Long,
    val chunks: Long,
)

@Serializable
data class AdminStatsResponse(
    val totalDocuments: Long,
    val totalChunks: Long,
    val bySource: List<AdminSourceStats>,
)

@Serializable
data class AdminConnectorStatus(
    val connectorId: String,
    val source: String,
    val running: Boolean,
    val lastAttemptAt: String? = null,
    val lastSuccessAt: String? = null,
    val indexed: Int? = null,
    val deleted: Int? = null,
    val error: String? = null,
)

fun Route.adminRoutes(
    adminToken: String,
    ingestionService: DocumentIngestionService,
    documentRepository: DocumentRepository,
    connectorSyncService: ConnectorSyncService? = null,
) {
    route("/api/admin") {
        manualDocumentRoutes(adminToken, ingestionService, documentRepository)
        adminStatsRoutes(adminToken, documentRepository)
        connectorStatusRoutes(adminToken, connectorSyncService)
    }
}
