package io.github.cyrilleguipie.kontext.connector.spi

import io.github.cyrilleguipie.kontext.domain.DocumentSource
import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class RawDocument(
    val externalId: String,
    val title: String,
    val body: String,
    val url: String,
    val metadata: Map<String, String>,
    val updatedAt: Instant,
)

interface SourceConnector {
    val connectorId: String
    val source: DocumentSource
    val allowEmptySnapshotCleanup: Boolean get() = false

    fun fetchDocuments(since: Instant? = null): Flow<RawDocument>
}
