package io.github.cyrilleguipie.kontext.store

import com.pgvector.PGvector
import io.github.cyrilleguipie.kontext.domain.DocumentSource
import io.github.cyrilleguipie.kontext.domain.SearchFilter
import io.github.cyrilleguipie.kontext.domain.SearchHit
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

class HybridSearch(
    private val dataSource: DataSource,
    private val defaultCandidates: Int = 40,
) {
    private val logger = LoggerFactory.getLogger(HybridSearch::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val jiraKeyRegex = Regex("\\b([A-Z][A-Z0-9]+-\\d+)\\b")

    fun search(
        queryText: String,
        queryEmbedding: List<Float>,
        k: Int,
        filter: SearchFilter? = null,
    ): List<SearchHit> {
        val detectedJiraKey = jiraKeyRegex.find(queryText)?.value
        if (detectedJiraKey != null) {
            logger.debug("Detected Jira key in query: {}", detectedJiraKey)
        }

        val sql = buildSearchSql()
        val pgVector = PGvector(queryEmbedding.toFloatArray())

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                bindParameters(
                    stmt = stmt,
                    queryText = queryText,
                    pgVector = pgVector,
                    filter = filter,
                    detectedJiraKey = detectedJiraKey,
                    k = k,
                )

                val rs = stmt.executeQuery()
                val hits = mutableListOf<SearchHit>()
                while (rs.next()) {
                    hits.add(mapSearchHit(rs))
                }
                return hits
            }
        }
    }

    private fun bindParameters(
        stmt: PreparedStatement,
        queryText: String,
        pgVector: PGvector,
        filter: SearchFilter?,
        detectedJiraKey: String?,
        k: Int,
    ) {
        val sourceStr = filter?.source?.dbValue
        val projectOrSpace = filter?.projectOrSpace
        var idx = 1

        // vec CTE
        stmt.setObject(idx++, pgVector)
        stmt.setString(idx++, sourceStr)
        stmt.setString(idx++, sourceStr)
        stmt.setString(idx++, projectOrSpace)
        stmt.setString(idx++, projectOrSpace)
        stmt.setString(idx++, projectOrSpace)
        stmt.setObject(idx++, pgVector)
        stmt.setInt(idx++, defaultCandidates)

        // fts CTE
        stmt.setString(idx++, queryText)
        stmt.setString(idx++, sourceStr)
        stmt.setString(idx++, sourceStr)
        stmt.setString(idx++, projectOrSpace)
        stmt.setString(idx++, projectOrSpace)
        stmt.setString(idx++, projectOrSpace)
        stmt.setInt(idx++, defaultCandidates)

        // fused bonus
        stmt.setString(idx++, detectedJiraKey)
        stmt.setString(idx++, detectedJiraKey)

        // final LIMIT
        stmt.setInt(idx, k)
    }

    private fun buildSearchSql(): String =
        """
        WITH vec AS (
          SELECT c.id, ROW_NUMBER() OVER (ORDER BY c.embedding <=> ?::vector) AS rnk
          FROM chunk c
          JOIN document d ON c.document_id = d.id
          WHERE (?::text IS NULL OR d.source = ?::text)
            AND (?::text IS NULL OR d.metadata->>'project' = ?::text OR d.metadata->>'space' = ?::text)
          ORDER BY c.embedding <=> ?::vector
          LIMIT ?
        ),
        fts AS (
          SELECT c.id, ROW_NUMBER() OVER (ORDER BY ts_rank_cd(c.tsv, q) DESC) AS rnk
          FROM chunk c
          JOIN document d ON c.document_id = d.id,
          websearch_to_tsquery('simple', ?) q
          WHERE c.tsv @@ q
            AND (?::text IS NULL OR d.source = ?::text)
            AND (?::text IS NULL OR d.metadata->>'project' = ?::text OR d.metadata->>'space' = ?::text)
          ORDER BY ts_rank_cd(c.tsv, q) DESC
          LIMIT ?
        ),
        fused AS (
          SELECT id, rnk FROM vec
          UNION ALL
          SELECT id, rnk FROM fts
        )
        SELECT c.id AS chunk_id,
               c.document_id,
               d.source,
               d.external_id,
               d.title,
               d.url,
               c.content,
               d.metadata,
               (CASE WHEN ?::text IS NOT NULL AND d.external_id = ?::text THEN 2.0 ELSE 0.0 END
                + SUM(1.0 / (60.0 + fused.rnk))) AS score
        FROM fused
        JOIN chunk c ON fused.id = c.id
        JOIN document d ON c.document_id = d.id
        GROUP BY c.id, c.document_id, d.source, d.external_id, d.title, d.url, c.content, d.metadata
        ORDER BY score DESC
        LIMIT ?;
        """.trimIndent()

    private fun mapSearchHit(rs: ResultSet): SearchHit {
        val metadataJson = rs.getString("metadata") ?: "{}"
        val metadataMap: Map<String, String> =
            runCatching {
                json.decodeFromString<Map<String, String>>(metadataJson)
            }.getOrDefault(emptyMap())

        return SearchHit(
            chunkId = rs.getLong("chunk_id"),
            documentId = rs.getLong("document_id"),
            source = DocumentSource.fromDbValue(rs.getString("source")) ?: DocumentSource.JIRA,
            externalId = rs.getString("external_id"),
            title = rs.getString("title"),
            url = rs.getString("url"),
            content = rs.getString("content"),
            score = rs.getDouble("score"),
            metadata = metadataMap,
        )
    }
}
