package io.github.cyrilleguipie.kontext.store

import arrow.core.raise.fold
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.cyrilleguipie.kontext.domain.KontextError.StorageError
import io.github.cyrilleguipie.kontext.store.Database.validateEmbeddingDimension
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class DatabaseIntegrationTest {
    companion object {
        private val pgvectorImage =
            DockerImageName.parse("pgvector/pgvector:pg17")
                .asCompatibleSubstituteFor("postgres")

        private val postgres =
            PostgreSQLContainer(pgvectorImage).apply {
                withDatabaseName("kontext_test")
                withUsername("test")
                withPassword("test")
            }

        private lateinit var dataSource: HikariDataSource

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
    fun `should migrate successfully and validate matching embedding dimension`() {
        val configuredDim = 1024
        Database.migrate(dataSource, configuredDim)

        val checkMatching =
            fold(
                block = { validateEmbeddingDimension(dataSource, configuredDim) },
                recover = { error -> error },
                transform = { true },
            )

        assertEquals(true, checkMatching)
    }

    @Test
    fun `should fail validation when configured dimension differs from existing column dimension`() {
        // Table was migrated with 1024 in the previous test / setup
        val differentDim = 768

        val checkMismatch =
            fold(
                block = { validateEmbeddingDimension(dataSource, differentDim) },
                recover = { error -> error },
                transform = { true },
            )

        assertTrue(checkMismatch is StorageError.DimensionMismatch)
        val mismatch = checkMismatch as StorageError.DimensionMismatch
        assertEquals(768, mismatch.expected)
        assertEquals(1024, mismatch.actual)
        val expectedMsg = "Configured embedding dimension (768) does not match database column vector(1024)"
        assertTrue(mismatch.message.contains(expectedMsg))
    }
}
