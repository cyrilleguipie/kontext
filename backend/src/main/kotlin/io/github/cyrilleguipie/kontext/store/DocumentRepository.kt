package io.github.cyrilleguipie.kontext.store

import com.pgvector.PGvector
import io.github.cyrilleguipie.kontext.domain.Chunk
import io.github.cyrilleguipie.kontext.domain.Document
import io.github.cyrilleguipie.kontext.domain.DocumentSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class SourceStats(
    val source: DocumentSource,
    val documentCount: Long,
    val chunkCount: Long,
)

data class DocumentStats(
    val totalDocuments: Long,
    val totalChunks: Long,
    val bySource: List<SourceStats>,
)

class DocumentRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(DocumentRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun upsertDocument(document: Document): Long {
        dataSource.connection.use { conn ->
            return upsertDocument(conn, document)
        }
    }

    fun upsertDocument(
        conn: Connection,
        document: Document,
    ): Long {
        val sql =
            """
            INSERT INTO document (source, external_id, title, url, metadata, content_hash, updated_at, ingested_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT (source, external_id) DO UPDATE SET
                title = EXCLUDED.title,
                url = EXCLUDED.url,
                metadata = EXCLUDED.metadata,
                content_hash = EXCLUDED.content_hash,
                updated_at = EXCLUDED.updated_at,
                ingested_at = EXCLUDED.ingested_at
            RETURNING id
            """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, document.source.dbValue)
            stmt.setString(2, document.externalId)
            stmt.setString(3, document.title)
            stmt.setString(4, document.url)
            stmt.setString(5, json.encodeToString(document.metadata))
            stmt.setString(6, document.contentHash)
            stmt.setTimestamp(7, Timestamp.from(document.updatedAt))
            stmt.setTimestamp(8, Timestamp.from(document.ingestedAt))

            val rs = stmt.executeQuery()
            rs.next()
            return rs.getLong(1)
        }
    }

    fun replaceChunks(
        documentId: Long,
        chunks: List<Chunk>,
    ) {
        dataSource.connection.use { conn ->
            val autoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM chunk WHERE document_id = ?").use { stmt ->
                    stmt.setLong(1, documentId)
                    stmt.executeUpdate()
                }

                if (chunks.isNotEmpty()) {
                    insertChunkBatch(conn, documentId, chunks)
                }
                conn.commit()
            } catch (sqle: SQLException) {
                conn.rollback()
                throw sqle
            } finally {
                conn.autoCommit = autoCommit
            }
        }
    }

    private fun insertChunkBatch(
        conn: Connection,
        documentId: Long,
        chunks: List<Chunk>,
    ) {
        val insertSql =
            """
            INSERT INTO chunk (document_id, position, content, embedding)
            VALUES (?, ?, ?, ?)
            """.trimIndent()

        conn.prepareStatement(insertSql).use { stmt ->
            for (chunk in chunks) {
                stmt.setLong(1, documentId)
                stmt.setInt(2, chunk.position)
                stmt.setString(3, chunk.content)
                stmt.setObject(4, PGvector(chunk.embedding.toFloatArray()))
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    fun findDocument(
        source: DocumentSource,
        externalId: String,
    ): Document? {
        val sql =
            """
            SELECT id, source, external_id, title, url, metadata, content_hash, updated_at, ingested_at
            FROM document
            WHERE source = ? AND external_id = ?
            """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, source.dbValue)
                stmt.setString(2, externalId)
                val rs = stmt.executeQuery()
                return if (rs.next()) mapDocument(rs) else null
            }
        }
    }

    fun deleteDocument(
        source: DocumentSource,
        externalId: String,
    ): Boolean {
        val sql = "DELETE FROM document WHERE source = ? AND external_id = ?"
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, source.dbValue)
                stmt.setString(2, externalId)
                return stmt.executeUpdate() > 0
            }
        }
    }

    fun deleteDocumentsNotIn(
        source: DocumentSource,
        validExternalIds: Set<String>,
    ): Int {
        if (validExternalIds.isEmpty()) {
            val sql = "DELETE FROM document WHERE source = ?"
            dataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, source.dbValue)
                    return stmt.executeUpdate()
                }
            }
        }

        val sql =
            """
            DELETE FROM document
            WHERE source = ?
              AND external_id NOT IN (SELECT unnest(?::text[]))
            """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, source.dbValue)
                val array = conn.createArrayOf("text", validExternalIds.toTypedArray())
                stmt.setArray(2, array)
                val deleted = stmt.executeUpdate()
                logger.info("Deleted {} stale documents for source {}", deleted, source)
                return deleted
            }
        }
    }

    fun getChunksForDocument(documentId: Long): List<Chunk> {
        val sql =
            """
            SELECT id, document_id, position, content, embedding
            FROM chunk
            WHERE document_id = ?
            ORDER BY position ASC
            """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, documentId)
                val rs = stmt.executeQuery()
                val list = mutableListOf<Chunk>()
                while (rs.next()) {
                    list.add(mapChunk(rs))
                }
                return list
            }
        }
    }

    private fun mapChunk(rs: ResultSet): Chunk {
        val rawObj = rs.getObject("embedding")
        val pgVector =
            when (rawObj) {
                is PGvector -> rawObj
                is org.postgresql.util.PGobject -> PGvector(rawObj.value ?: "")
                is String -> PGvector(rawObj)
                else -> error("Unknown vector type: ${rawObj?.javaClass}")
            }
        return Chunk(
            id = rs.getLong("id"),
            documentId = rs.getLong("document_id"),
            position = rs.getInt("position"),
            content = rs.getString("content"),
            embedding = pgVector.toArray().toList(),
        )
    }

    fun getStats(): DocumentStats {
        val docCounts: Map<DocumentSource, Long>
        val chunkCounts: Map<DocumentSource, Long>

        dataSource.connection.use { conn ->
            docCounts = fetchDocCounts(conn)
            chunkCounts = fetchChunkCounts(conn)
        }

        val bySource =
            DocumentSource.entries.map { source ->
                SourceStats(
                    source = source,
                    documentCount = docCounts[source] ?: 0L,
                    chunkCount = chunkCounts[source] ?: 0L,
                )
            }

        return DocumentStats(
            totalDocuments = docCounts.values.sum(),
            totalChunks = chunkCounts.values.sum(),
            bySource = bySource,
        )
    }

    private fun fetchDocCounts(conn: Connection): Map<DocumentSource, Long> {
        val counts = mutableMapOf<DocumentSource, Long>()
        val sql = "SELECT source, COUNT(*) as count FROM document GROUP BY source"
        conn.prepareStatement(sql).use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                DocumentSource.fromDbValue(rs.getString("source"))?.let {
                    counts[it] = rs.getLong("count")
                }
            }
        }
        return counts
    }

    private fun fetchChunkCounts(conn: Connection): Map<DocumentSource, Long> {
        val counts = mutableMapOf<DocumentSource, Long>()
        val sql =
            """
            SELECT d.source, COUNT(c.id) as count
            FROM chunk c
            JOIN document d ON c.document_id = d.id
            GROUP BY d.source
            """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                DocumentSource.fromDbValue(rs.getString("source"))?.let {
                    counts[it] = rs.getLong("count")
                }
            }
        }
        return counts
    }

    private fun mapDocument(rs: ResultSet): Document {
        val metadataJson = rs.getString("metadata") ?: "{}"
        val metadataMap: Map<String, String> =
            runCatching {
                json.decodeFromString<Map<String, String>>(metadataJson)
            }.getOrDefault(emptyMap())

        return Document(
            id = rs.getLong("id"),
            source = DocumentSource.fromDbValue(rs.getString("source")) ?: DocumentSource.JIRA,
            externalId = rs.getString("external_id"),
            title = rs.getString("title"),
            url = rs.getString("url"),
            metadata = metadataMap,
            contentHash = rs.getString("content_hash"),
            updatedAt = rs.getTimestamp("updated_at")?.toInstant() ?: Instant.now(),
            ingestedAt = rs.getTimestamp("ingested_at")?.toInstant() ?: Instant.now(),
        )
    }
}
