package io.github.cyrilleguipie.kontext.api

import arrow.core.Either
import io.github.cyrilleguipie.kontext.domain.DocumentSource
import io.github.cyrilleguipie.kontext.domain.KontextError
import io.github.cyrilleguipie.kontext.ingestion.DocumentIngestionService
import io.github.cyrilleguipie.kontext.ingestion.IngestDocument
import io.github.cyrilleguipie.kontext.store.DocumentRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URISyntaxException
import java.sql.SQLException
import java.time.Instant
import java.time.format.DateTimeParseException

private const val MAX_BODY_LENGTH = 250_000

internal fun Route.manualDocumentRoutes(
    adminToken: String,
    ingestionService: DocumentIngestionService,
    documentRepository: DocumentRepository,
) {
    post("/documents") {
        if (!call.requireAdminAuthorization(adminToken)) return@post
        val request =
            try {
                call.receive<IngestDocumentRequest>()
            } catch (_: ContentTransformationException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid document payload"))
                return@post
            }
        val validationError = request.validationError()
        if (validationError != null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
            return@post
        }
        val updatedAt =
            when (val parsedTimestamp = request.updatedAt.toUpdatedAt()) {
                is Either.Left -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to parsedTimestamp.value))
                    return@post
                }
                is Either.Right -> parsedTimestamp.value
            }
        call.ingestDocument(request, updatedAt, ingestionService)
    }

    delete("/documents/{externalId}") {
        if (!call.requireAdminAuthorization(adminToken)) return@delete
        val externalId = call.parameters["externalId"]
        if (externalId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "externalId is required"))
            return@delete
        }
        val deleted =
            withContext(Dispatchers.IO) {
                documentRepository.deleteDocument(DocumentSource.MANUAL, externalId)
            }
        call.respond(if (deleted) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.ingestDocument(
    request: IngestDocumentRequest,
    updatedAt: Instant,
    ingestionService: DocumentIngestionService,
) {
    try {
        val result =
            ingestionService.ingest(
                IngestDocument(
                    externalId = request.externalId.trim(),
                    title = request.title.trim(),
                    body = request.body,
                    url = request.url.trim(),
                    metadata = request.metadata,
                    updatedAt = updatedAt,
                ),
            )
        respond(
            if (result.unchanged) HttpStatusCode.OK else HttpStatusCode.Created,
            IngestDocumentResponse(result.externalId, result.chunkCount, result.unchanged),
        )
    } catch (cause: KontextError.Exception) {
        respond(HttpStatusCode.BadGateway, mapOf("error" to (cause.message ?: "Embedding request failed")))
    } catch (cause: SQLException) {
        respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "Document storage failed")))
    }
}

private fun String?.toUpdatedAt(): Either<String, Instant> =
    when (this) {
        null -> Either.Right(Instant.now())
        else ->
            try {
                Either.Right(Instant.parse(this))
            } catch (_: DateTimeParseException) {
                Either.Left("updatedAt must be an ISO-8601 timestamp")
            }
    }

private fun IngestDocumentRequest.validationError(): String? {
    val parsedUrl = parseUri(url.trim())
    return sequenceOf(
        externalIdValidationError(),
        titleValidationError(),
        bodyValidationError(),
        urlValidationError(parsedUrl),
        metadataValidationError(),
    ).filterNotNull().firstOrNull()
}

private fun IngestDocumentRequest.externalIdValidationError(): String? =
    if (externalId.isBlank() || externalId.length > 512) "externalId must contain 1 to 512 characters" else null

private fun IngestDocumentRequest.titleValidationError(): String? =
    if (title.isBlank() || title.length > 500) "title must contain 1 to 500 characters" else null

private fun IngestDocumentRequest.bodyValidationError(): String? =
    if (body.isBlank() || body.length > MAX_BODY_LENGTH) "body must contain 1 to $MAX_BODY_LENGTH characters" else null

private fun IngestDocumentRequest.urlValidationError(parsedUrl: URI?): String? =
    when {
        url.length > 2_048 -> "url must not exceed 2048 characters"
        parsedUrl == null || parsedUrl.scheme !in setOf("http", "https") || parsedUrl.host.isNullOrBlank() ->
            "url must be an absolute HTTP or HTTPS URL"
        else -> null
    }

private fun IngestDocumentRequest.metadataValidationError(): String? =
    if (metadata.size > 100 || metadata.any { (key, value) -> key.length > 128 || value.length > 2_048 }) {
        "metadata supports at most 100 entries; keys are limited to 128 and values to 2048 characters"
    } else {
        null
    }

private fun parseUri(value: String): URI? =
    try {
        URI(value)
    } catch (_: URISyntaxException) {
        null
    }

internal fun Route.adminStatsRoutes(
    adminToken: String,
    documentRepository: DocumentRepository,
) {
    get("/stats") {
        if (!call.requireAdminAuthorization(adminToken)) return@get
        val stats = withContext(Dispatchers.IO) { documentRepository.getStats() }
        call.respond(
            AdminStatsResponse(
                totalDocuments = stats.totalDocuments,
                totalChunks = stats.totalChunks,
                bySource =
                    stats.bySource.map {
                        AdminSourceStats(it.source.dbValue, it.documentCount, it.chunkCount)
                    },
            ),
        )
    }
}
