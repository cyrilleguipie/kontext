package io.github.cyrilleguipie.kontext.domain

import kotlinx.serialization.Serializable
import java.time.Instant

enum class DocumentSource(val dbValue: String) {
    JIRA("jira"),
    CONFLUENCE("confluence"),
    ;

    companion object {
        fun fromDbValue(value: String): DocumentSource? =
            entries.firstOrNull { it.dbValue.equals(value, ignoreCase = true) }
    }
}

@Serializable
data class Document(
    val id: Long? = null,
    val source: DocumentSource,
    val externalId: String,
    val title: String,
    val url: String,
    val metadata: Map<String, String> = emptyMap(),
    val contentHash: String,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val ingestedAt: Instant = Instant.now(),
)

data class Chunk(
    val id: Long? = null,
    val documentId: Long = 0,
    val position: Int,
    val content: String,
    val embedding: List<Float>,
)

@Serializable
data class SearchHit(
    val chunkId: Long,
    val documentId: Long,
    val source: DocumentSource,
    val externalId: String,
    val title: String,
    val url: String,
    val content: String,
    val score: Double,
    val metadata: Map<String, String> = emptyMap(),
)

data class SearchFilter(
    val source: DocumentSource? = null,
    val projectOrSpace: String? = null,
)
