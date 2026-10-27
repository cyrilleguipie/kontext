package io.github.cyrilleguipie.kontext.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.cyrilleguipie.kontext.domain.Chunk
import io.github.cyrilleguipie.kontext.domain.Document
import io.github.cyrilleguipie.kontext.domain.DocumentSource
import io.github.cyrilleguipie.kontext.domain.SearchFilter
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant

class HybridSearchTest {
    companion object {
        private val pgvectorImage =
            DockerImageName.parse("pgvector/pgvector:pg17")
                .asCompatibleSubstituteFor("postgres")

        private val postgres =
            PostgreSQLContainer(pgvectorImage).apply {
                withDatabaseName("kontext_search_test")
                withUsername("test")
                withPassword("test")
            }

        private lateinit var dataSource: HikariDataSource
        private lateinit var repository: DocumentRepository
        private lateinit var searcher: HybridSearch

        @JvmStatic
        @BeforeAll
        fun setup() {
            postgres.start()
            val hikariConfig =
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    driverClassName = "org.postgresql.Driver"
                    maximumPoolSize = 5
                }
            dataSource = HikariDataSource(hikariConfig)
            Database.migrate(dataSource, 4)
            repository = DocumentRepository(dataSource)
            searcher = HybridSearch(dataSource)
            seedTestData(repository)
        }

        private fun seedTestData(repo: DocumentRepository) {
            val doc1Id =
                repo.upsertDocument(
                    Document(
                        source = DocumentSource.JIRA,
                        externalId = "DEMO-100",
                        title = "Payment gateway timeout on retry",
                        url = "https://acme.atlassian.net/browse/DEMO-100",
                        metadata = mapOf("project" to "DEMO"),
                        contentHash = "h1",
                        updatedAt = Instant.now(),
                    ),
                )
            repo.replaceChunks(
                doc1Id,
                listOf(
                    Chunk(
                        documentId = doc1Id,
                        position = 0,
                        content = "When Stripe webhooks fail, payment checkout encounters timeout",
                        embedding = listOf(1.0f, 0.0f, 0.0f, 0.0f),
                    ),
                ),
            )

            val doc2Id =
                repo.upsertDocument(
                    Document(
                        source = DocumentSource.CONFLUENCE,
                        externalId = "conf-500",
                        title = "Kafka event streaming architecture",
                        url = "https://acme.atlassian.net/wiki/conf-500",
                        metadata = mapOf("space" to "DEMO"),
                        contentHash = "h2",
                        updatedAt = Instant.now(),
                    ),
                )
            repo.replaceChunks(
                doc2Id,
                listOf(
                    Chunk(
                        documentId = doc2Id,
                        position = 0,
                        content = "All order payment events are published to Kafka cluster topic",
                        embedding = listOf(0.0f, 1.0f, 0.0f, 0.0f),
                    ),
                ),
            )
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            if (::dataSource.isInitialized) {
                dataSource.close()
            }
            postgres.stop()
        }
    }

    @Test
    fun `should find relevant chunks using hybrid search`() {
        val queryVector = listOf(1.0f, 0.0f, 0.0f, 0.0f)
        val hits =
            searcher.search(
                queryText = "Stripe webhooks payment timeout",
                queryEmbedding = queryVector,
                k = 5,
            )

        assertTrue(hits.isNotEmpty())
        assertEquals("DEMO-100", hits.first().externalId)
        assertTrue(hits.first().score > 0)
    }

    @Test
    fun `should boost document when query mentions its Jira key`() {
        // Query has Kafka embedding but explicitly mentions DEMO-100
        val queryVector = listOf(0.0f, 1.0f, 0.0f, 0.0f)
        val hits =
            searcher.search(
                queryText = "What is the status of DEMO-100?",
                queryEmbedding = queryVector,
                k = 5,
            )

        assertTrue(hits.isNotEmpty())
        assertEquals("DEMO-100", hits.first().externalId)
    }

    @Test
    fun `should filter by source`() {
        val queryVector = listOf(0.5f, 0.5f, 0.0f, 0.0f)
        val confluenceHits =
            searcher.search(
                queryText = "payment architecture",
                queryEmbedding = queryVector,
                k = 5,
                filter = SearchFilter(source = DocumentSource.CONFLUENCE),
            )

        assertTrue(confluenceHits.isNotEmpty())
        assertTrue(confluenceHits.all { it.source == DocumentSource.CONFLUENCE })
    }
}
