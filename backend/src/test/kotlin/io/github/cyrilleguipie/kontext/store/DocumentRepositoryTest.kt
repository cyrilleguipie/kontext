package io.github.cyrilleguipie.kontext.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.cyrilleguipie.kontext.domain.Chunk
import io.github.cyrilleguipie.kontext.domain.Document
import io.github.cyrilleguipie.kontext.domain.DocumentSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant

class DocumentRepositoryTest {
    companion object {
        private val pgvectorImage =
            DockerImageName.parse("pgvector/pgvector:pg17")
                .asCompatibleSubstituteFor("postgres")

        private val postgres =
            PostgreSQLContainer(pgvectorImage).apply {
                withDatabaseName("kontext_doc_test")
                withUsername("test")
                withPassword("test")
            }

        private lateinit var dataSource: HikariDataSource
        private lateinit var repository: DocumentRepository

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
    fun `should upsert document and retrieve it successfully`() {
        val now = Instant.now()
        val doc =
            Document(
                source = DocumentSource.JIRA,
                externalId = "DEMO-101",
                title = "Test ticket title",
                url = "https://example.com/browse/DEMO-101",
                metadata = mapOf("project" to "DEMO", "status" to "Open"),
                contentHash = "hash123",
                updatedAt = now,
            )

        val docId = repository.upsertDocument(doc)
        assertTrue(docId > 0)

        val retrieved = repository.findDocument(DocumentSource.JIRA, "DEMO-101")
        assertNotNull(retrieved)
        assertEquals("Test ticket title", retrieved?.title)
        assertEquals("DEMO", retrieved?.metadata?.get("project"))
        assertEquals("Open", retrieved?.metadata?.get("status"))

        // Update document
        val updatedDoc = doc.copy(title = "Updated ticket title", contentHash = "hash456")
        val updatedId = repository.upsertDocument(updatedDoc)
        assertEquals(docId, updatedId)

        val reRetrieved = repository.findDocument(DocumentSource.JIRA, "DEMO-101")
        assertEquals("Updated ticket title", reRetrieved?.title)
        assertEquals("hash456", reRetrieved?.contentHash)
    }

    @Test
    fun `should insert and replace chunks for a document`() {
        val doc =
            Document(
                source = DocumentSource.CONFLUENCE,
                externalId = "page-202",
                title = "Architecture Overview",
                url = "https://example.com/wiki/page-202",
                metadata = mapOf("space" to "DEMO"),
                contentHash = "hash-page",
                updatedAt = Instant.now(),
            )
        val docId = repository.upsertDocument(doc)

        val chunks =
            listOf(
                Chunk(
                    documentId = docId,
                    position = 0,
                    content = "First section about backend architecture",
                    embedding = listOf(0.1f, 0.2f, 0.3f, 0.4f),
                ),
                Chunk(
                    documentId = docId,
                    position = 1,
                    content = "Second section about database storage",
                    embedding = listOf(0.5f, 0.6f, 0.7f, 0.8f),
                ),
            )

        repository.replaceChunks(docId, chunks)

        val retrievedChunks = repository.getChunksForDocument(docId)
        assertEquals(2, retrievedChunks.size)
        assertEquals(0, retrievedChunks[0].position)
        assertEquals("First section about backend architecture", retrievedChunks[0].content)
        assertEquals(4, retrievedChunks[0].embedding.size)

        // Replace with single chunk
        val replacementChunks =
            listOf(
                Chunk(
                    documentId = docId,
                    position = 0,
                    content = "Consolidated architecture document",
                    embedding = listOf(0.9f, 0.8f, 0.7f, 0.6f),
                ),
            )
        repository.replaceChunks(docId, replacementChunks)

        val reRetrievedChunks = repository.getChunksForDocument(docId)
        assertEquals(1, reRetrievedChunks.size)
        assertEquals("Consolidated architecture document", reRetrievedChunks[0].content)
    }

    @Test
    fun `should delete document and cascade delete its chunks`() {
        val doc =
            Document(
                source = DocumentSource.JIRA,
                externalId = "DEMO-999",
                title = "Ticket to delete",
                url = "https://example.com/browse/DEMO-999",
                contentHash = "del-hash",
                updatedAt = Instant.now(),
            )
        val docId = repository.upsertDocument(doc)
        repository.replaceChunks(
            docId,
            listOf(
                Chunk(
                    documentId = docId,
                    position = 0,
                    content = "Chunk to be deleted",
                    embedding = listOf(0.1f, 0.1f, 0.1f, 0.1f),
                ),
            ),
        )

        val deleted = repository.deleteDocument(DocumentSource.JIRA, "DEMO-999")
        assertTrue(deleted)
        assertNull(repository.findDocument(DocumentSource.JIRA, "DEMO-999"))
        assertTrue(repository.getChunksForDocument(docId).isEmpty())
    }

    @Test
    fun `should delete documents not in valid external id set`() {
        repository.upsertDocument(
            Document(
                source = DocumentSource.JIRA,
                externalId = "SYNC-1",
                title = "Keep 1",
                url = "url1",
                contentHash = "h1",
                updatedAt = Instant.now(),
            ),
        )
        repository.upsertDocument(
            Document(
                source = DocumentSource.JIRA,
                externalId = "SYNC-2",
                title = "Delete me",
                url = "url2",
                contentHash = "h2",
                updatedAt = Instant.now(),
            ),
        )

        val deletedCount = repository.deleteDocumentsNotIn(DocumentSource.JIRA, setOf("SYNC-1"))
        assertTrue(deletedCount >= 1)
        assertNotNull(repository.findDocument(DocumentSource.JIRA, "SYNC-1"))
        assertNull(repository.findDocument(DocumentSource.JIRA, "SYNC-2"))
    }
}
