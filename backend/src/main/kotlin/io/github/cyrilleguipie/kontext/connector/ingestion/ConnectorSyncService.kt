package io.github.cyrilleguipie.kontext.connector.ingestion

import io.github.cyrilleguipie.kontext.connector.spi.SourceConnector
import io.github.cyrilleguipie.kontext.ingestion.DocumentIngestionService
import io.github.cyrilleguipie.kontext.ingestion.IngestDocument
import io.github.cyrilleguipie.kontext.store.ConnectorSyncRecord
import io.github.cyrilleguipie.kontext.store.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class SyncResult(
    val connectorId: String,
    val indexed: Int,
    val deleted: Int,
)

data class ConnectorSyncStatus(
    val connectorId: String,
    val source: String,
    val running: Boolean,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val indexed: Int? = null,
    val deleted: Int? = null,
    val error: String? = null,
)

class ConnectorSyncService(
    private val ingestionService: DocumentIngestionService,
    private val repository: DocumentRepository,
    initialConnectors: List<SourceConnector> = emptyList(),
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val connectors = ConcurrentHashMap(initialConnectors.associateBy { it.connectorId })
    private val statuses = ConcurrentHashMap<String, ConnectorSyncStatus>()

    fun register(connector: SourceConnector) {
        connectors[connector.connectorId] = connector
    }

    fun unregister(connectorId: String) {
        connectors.remove(connectorId)
    }

    suspend fun configuredConnectorStatuses(): List<ConnectorSyncStatus> =
        buildList {
            connectors.keys.sorted().forEach { connectorId ->
                status(connectorId)?.let(::add)
            }
        }

    suspend fun status(connectorId: String): ConnectorSyncStatus? {
        val connector = connectors[connectorId]
        return connector?.let { statuses[connectorId] ?: loadPersistedStatus(it) }
    }

    suspend fun sync(connectorId: String): SyncResult? = connectors[connectorId]?.let { sync(it) }

    suspend fun sync(connector: SourceConnector): SyncResult {
        val lock = locks.computeIfAbsent(connector.connectorId) { Mutex() }
        return lock.withLock {
            val attemptAt = Instant.now()
            val previous = status(connector.connectorId)
            updateStatus(
                ConnectorSyncStatus(
                    connectorId = connector.connectorId,
                    source = connector.source.dbValue,
                    running = true,
                    lastAttemptAt = attemptAt,
                    lastSuccessAt = previous?.lastSuccessAt,
                    indexed = previous?.indexed,
                    deleted = previous?.deleted,
                ),
            )
            runCatching {
                syncSnapshot(connector).also { result ->
                    updateStatus(
                        ConnectorSyncStatus(
                            connectorId = connector.connectorId,
                            source = connector.source.dbValue,
                            running = false,
                            lastAttemptAt = attemptAt,
                            lastSuccessAt = Instant.now(),
                            indexed = result.indexed,
                            deleted = result.deleted,
                        ),
                    )
                }
            }.fold(
                onSuccess = { result ->
                    result
                },
                onFailure = { cause ->
                    updateStatus(
                        ConnectorSyncStatus(
                            connectorId = connector.connectorId,
                            source = connector.source.dbValue,
                            running = false,
                            lastAttemptAt = attemptAt,
                            lastSuccessAt = previous?.lastSuccessAt,
                            indexed = previous?.indexed,
                            deleted = previous?.deleted,
                            error = cause.message ?: cause::class.simpleName,
                        ),
                        bestEffort = true,
                    )
                    throw cause
                },
            )
        }
    }

    private suspend fun loadPersistedStatus(connector: SourceConnector): ConnectorSyncStatus {
        val stored =
            withContext(Dispatchers.IO) { repository.getConnectorSyncRecord(connector.connectorId)?.toStatus() }
        val status = stored ?: ConnectorSyncStatus(connector.connectorId, connector.source.dbValue, running = false)
        return if (status.running) {
            status.copy(running = false, error = "Previous sync was interrupted by a server restart")
                .also { recovered ->
                    statuses[connector.connectorId] = recovered
                    persistStatus(recovered)
                }
        } else {
            status
        }
    }

    private suspend fun syncSnapshot(connector: SourceConnector): SyncResult {
        val seen = mutableSetOf<String>()
        var indexed = 0
        connector.fetchDocuments().collect { raw ->
            seen += raw.externalId
            val result =
                ingestionService.ingest(
                    IngestDocument(
                        externalId = raw.externalId,
                        title = raw.title,
                        body = raw.body,
                        url = raw.url,
                        source = connector.source,
                        metadata = raw.metadata,
                        updatedAt = raw.updatedAt,
                    ),
                )
            if (!result.unchanged) indexed++
        }
        val deleted =
            if (seen.isEmpty() && !connector.allowEmptySnapshotCleanup) {
                0
            } else {
                withContext(Dispatchers.IO) { repository.deleteDocumentsNotIn(connector.source, seen) }
            }
        return SyncResult(connector.connectorId, indexed, deleted)
    }

    private suspend fun updateStatus(
        status: ConnectorSyncStatus,
        bestEffort: Boolean = false,
    ) {
        statuses[status.connectorId] = status
        if (bestEffort) runCatching { persistStatus(status) } else persistStatus(status)
    }

    private suspend fun persistStatus(status: ConnectorSyncStatus) {
        withContext(Dispatchers.IO) {
            repository.saveConnectorSyncRecord(
                ConnectorSyncRecord(
                    connectorId = status.connectorId,
                    source = status.source,
                    running = status.running,
                    lastAttemptAt = status.lastAttemptAt,
                    lastSuccessAt = status.lastSuccessAt,
                    indexed = status.indexed,
                    deleted = status.deleted,
                    error = status.error,
                ),
            )
        }
    }

    private fun ConnectorSyncRecord.toStatus() =
        ConnectorSyncStatus(
            connectorId = connectorId,
            source = source,
            running = running,
            lastAttemptAt = lastAttemptAt,
            lastSuccessAt = lastSuccessAt,
            indexed = indexed,
            deleted = deleted,
            error = error,
        )
}
