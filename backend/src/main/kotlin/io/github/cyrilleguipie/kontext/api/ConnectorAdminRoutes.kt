package io.github.cyrilleguipie.kontext.api

import io.github.cyrilleguipie.kontext.connector.ingestion.ConnectorSyncService
import io.github.cyrilleguipie.kontext.connector.ingestion.ConnectorSyncStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.sql.SQLException

internal fun Route.connectorStatusRoutes(
    adminToken: String,
    connectorSyncService: ConnectorSyncService?,
) {
    get("/connectors") {
        if (!call.requireAdminAuthorization(adminToken)) return@get
        call.respond(
            connectorSyncService?.configuredConnectorStatuses().orEmpty().map(ConnectorSyncStatus::toAdminStatus),
        )
    }

    get("/connectors/{connectorId}/sync/status") {
        if (!call.requireAdminAuthorization(adminToken)) return@get
        val connectorId = call.parameters["connectorId"]
        val status = connectorId?.let { connectorSyncService?.status(it) }
        if (status == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Connector not found"))
        } else {
            call.respond(status.toAdminStatus())
        }
    }

    post("/connectors/{connectorId}/sync") {
        if (!call.requireAdminAuthorization(adminToken)) return@post
        val connectorId = call.parameters["connectorId"]
        if (connectorId.isNullOrBlank() || connectorSyncService == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Connector not found"))
            return@post
        }
        when (val result = runConnectorSync(connectorSyncService, connectorId)) {
            is ConnectorSyncOutcome.Success -> call.respond(result.status.toAdminStatus())
            is ConnectorSyncOutcome.NotFound ->
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Connector not found"),
                )
            is ConnectorSyncOutcome.Failure -> call.respond(HttpStatusCode.BadGateway, mapOf("error" to result.message))
        }
    }
}

private suspend fun runConnectorSync(
    syncService: ConnectorSyncService,
    connectorId: String,
): ConnectorSyncOutcome =
    try {
        val result = syncService.sync(connectorId)
        val status = syncService.status(connectorId)
        if (result == null || status == null) ConnectorSyncOutcome.NotFound else ConnectorSyncOutcome.Success(status)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: IOException) {
        ConnectorSyncOutcome.Failure(cause.message ?: "Connector request failed")
    } catch (cause: SerializationException) {
        ConnectorSyncOutcome.Failure(cause.message ?: "Connector returned an invalid response")
    } catch (cause: SQLException) {
        ConnectorSyncOutcome.Failure(cause.message ?: "Connector state could not be stored")
    } catch (cause: IllegalStateException) {
        ConnectorSyncOutcome.Failure(cause.message ?: "Connector sync failed")
    }

private sealed interface ConnectorSyncOutcome {
    data class Success(val status: ConnectorSyncStatus) : ConnectorSyncOutcome

    data object NotFound : ConnectorSyncOutcome

    data class Failure(val message: String) : ConnectorSyncOutcome
}

private fun ConnectorSyncStatus.toAdminStatus() =
    AdminConnectorStatus(
        connectorId = connectorId,
        source = source,
        running = running,
        lastAttemptAt = lastAttemptAt?.toString(),
        lastSuccessAt = lastSuccessAt?.toString(),
        indexed = indexed,
        deleted = deleted,
        error = error,
    )
