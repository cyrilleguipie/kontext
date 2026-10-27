package io.github.cyrilleguipie.kontext.ingestion

import arrow.core.raise.either
import io.github.cyrilleguipie.kontext.domain.Chunk
import io.github.cyrilleguipie.kontext.domain.Document
import io.github.cyrilleguipie.kontext.domain.DocumentSource
import io.github.cyrilleguipie.kontext.domain.KontextError
import io.github.cyrilleguipie.kontext.llm.EmbeddingClient
import io.github.cyrilleguipie.kontext.store.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

data class IngestDocument(
    val externalId: String,
    val title: String,
    val body: String,
    val url: String,
    val source: DocumentSource = DocumentSource.MANUAL,
    val metadata: Map<String, String> = emptyMap(),
    val updatedAt: Instant = Instant.now(),
)

data class IngestResult(
    val externalId: String,
    val chunkCount: Int,
    val unchanged: Boolean,
)

class DocumentIngestionService(
    private val embeddingClient: EmbeddingClient,
    private val repository: DocumentRepository,
) {
    suspend fun ingest(
        input: IngestDocument,
        source: DocumentSource = input.source,
    ): IngestResult {
        val contentHash = hash(input)
        val existing =
            withContext(Dispatchers.IO) {
                repository.findDocument(source, input.externalId)
            }
        if (existing?.contentHash == contentHash) {
            val chunkCount =
                existing.id?.let { documentId ->
                    withContext(Dispatchers.IO) { repository.getChunksForDocument(documentId).size }
                } ?: 0
            return IngestResult(input.externalId, chunkCount, unchanged = true)
        }

        val contents = splitIntoChunks(input.body)
        val embeddings =
            either {
                with(embeddingClient) { embedBatch(contents) }
            }.fold(
                ifLeft = { throw KontextError.Exception(it) },
                ifRight = { it },
            )
        val document =
            Document(
                source = source,
                externalId = input.externalId,
                title = input.title,
                url = input.url,
                metadata = input.metadata,
                contentHash = contentHash,
                updatedAt = input.updatedAt,
            )
        val chunks =
            contents.mapIndexed { index, content ->
                Chunk(position = index, content = content, embedding = embeddings[index])
            }
        withContext(Dispatchers.IO) { repository.saveDocumentWithChunks(document, chunks) }
        return IngestResult(input.externalId, chunks.size, unchanged = false)
    }

    private fun hash(input: IngestDocument): String {
        val stableContent =
            listOf(
                input.title,
                input.url,
                input.body,
                Json.encodeToString(input.metadata.toSortedMap()),
            ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(stableContent.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun splitIntoChunks(body: String): List<String> {
        val text = body.replace("\r\n", "\n").trim()
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val limit = (start + CHUNK_SIZE).coerceAtMost(text.length)
            val end =
                if (limit < text.length) {
                    text.lastIndexOf(' ', limit).takeIf { it > start + CHUNK_SIZE / 2 } ?: limit
                } else {
                    limit
                }
            text.substring(start, end).trim().takeIf(String::isNotBlank)?.let(chunks::add)
            if (end == text.length) break
            start = (end - CHUNK_OVERLAP).coerceAtLeast(start + 1)
            while (start < text.length && text[start].isWhitespace()) start++
        }
        return chunks
    }

    private companion object {
        const val CHUNK_SIZE = 1_200
        const val CHUNK_OVERLAP = 150
    }
}
