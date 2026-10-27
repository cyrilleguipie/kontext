package io.github.cyrilleguipie.kontext.connector.ingestion

import io.github.cyrilleguipie.kontext.connector.spi.SourceConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class ConnectorJobScheduler(
    private val scope: CoroutineScope,
    private val syncService: ConnectorSyncService,
) {
    private val jobs = ConcurrentHashMap<String, Job>()

    fun schedule(
        connector: SourceConnector,
        intervalSeconds: Long,
    ) {
        require(intervalSeconds >= MIN_INTERVAL_SECONDS) { "Connector interval must be at least 30 seconds" }
        syncService.register(connector)
        jobs.remove(connector.connectorId)?.cancel()
        jobs[connector.connectorId] =
            scope.launch {
                while (isActive) {
                    runCatching { syncService.sync(connector) }.fold(
                        onSuccess = { result ->
                            logger.info(
                                "Connector {} sync complete: indexed={}, deleted={}",
                                result.connectorId,
                                result.indexed,
                                result.deleted,
                            )
                        },
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

    fun cancel(connectorId: String) {
        jobs.remove(connectorId)?.cancel()
        syncService.unregister(connectorId)
    }

    private companion object {
        const val MIN_INTERVAL_SECONDS = 30L
        val logger = LoggerFactory.getLogger(ConnectorJobScheduler::class.java)
    }
}
