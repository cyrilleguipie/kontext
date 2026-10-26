package io.github.cyrilleguipie.kontext.store

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.cyrilleguipie.kontext.config.DatabaseConfig
import io.github.cyrilleguipie.kontext.domain.KontextError.StorageError
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.sql.DataSource

object Database {
    private val logger = LoggerFactory.getLogger(Database::class.java)

    fun dataSourceResource(config: DatabaseConfig): Resource<HikariDataSource> =
        resource({
            logger.info("Initializing HikariCP connection pool for {}", config.url)
            val hikariConfig =
                HikariConfig().apply {
                    jdbcUrl = config.url
                    username = config.user
                    password = config.password
                    driverClassName = "org.postgresql.Driver"
                    maximumPoolSize = 10
                    minimumIdle = 2
                    connectionTimeout = 10_000
                    idleTimeout = 600_000
                    maxLifetime = 1_800_000
                    poolName = "KontextHikariPool"
                }
            HikariDataSource(hikariConfig)
        }) { ds, exitCase ->
            logger.info("Closing HikariCP connection pool (exitCase: {})", exitCase)
            ds.close()
        }

    fun migrate(
        dataSource: DataSource,
        embeddingDimension: Int,
    ) {
        logger.info("Running Flyway migrations with embeddingDim={}", embeddingDimension)
        val flyway =
            Flyway.configure()
                .dataSource(dataSource)
                .placeholders(mapOf("embeddingDim" to embeddingDimension.toString()))
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()

        flyway.migrate()
        logger.info("Flyway migrations applied successfully")
    }

    fun Raise<StorageError>.validateEmbeddingDimension(
        dataSource: DataSource,
        expectedDimension: Int,
    ) {
        dataSource.connection.use { conn ->
            val actualDimension = getChunkEmbeddingDimension(conn)
            if (actualDimension != null) {
                ensure(actualDimension == expectedDimension) {
                    StorageError.DimensionMismatch(expected = expectedDimension, actual = actualDimension)
                }
            }
        }
    }

    private fun getChunkEmbeddingDimension(conn: Connection): Int? {
        val sql =
            """
            SELECT atttypmod
            FROM pg_attribute
            WHERE attrelid = 'chunk'::regclass
              AND attname = 'embedding'
              AND attnum > 0
              AND NOT attisdropped;
            """.trimIndent()

        return runCatching {
            conn.prepareStatement(sql).use { stmt ->
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    val typmod = rs.getInt("atttypmod")
                    if (typmod > 0) typmod else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }
}
