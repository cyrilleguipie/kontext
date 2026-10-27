package io.github.cyrilleguipie.kontext.store

import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class EncryptedConnectorConfiguration(
    val connectorId: String,
    val enabled: Boolean,
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val updatedAt: Instant,
)

class ConnectorConfigurationRepository(private val dataSource: DataSource) {
    fun find(connectorId: String): EncryptedConnectorConfiguration? {
        val sql =
            """
            SELECT connector_id, enabled, encrypted_payload, nonce, updated_at
            FROM connector_configuration
            WHERE connector_id = ?
            """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, connectorId)
                statement.executeQuery().use { result ->
                    if (!result.next()) return null
                    return EncryptedConnectorConfiguration(
                        connectorId = result.getString("connector_id"),
                        enabled = result.getBoolean("enabled"),
                        ciphertext = result.getBytes("encrypted_payload"),
                        nonce = result.getBytes("nonce"),
                        updatedAt = result.getTimestamp("updated_at").toInstant(),
                    )
                }
            }
        }
    }

    fun save(configuration: EncryptedConnectorConfiguration) {
        val sql =
            """
            INSERT INTO connector_configuration (
                connector_id, enabled, encrypted_payload, nonce, updated_at
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (connector_id) DO UPDATE SET
                enabled = EXCLUDED.enabled,
                encrypted_payload = EXCLUDED.encrypted_payload,
                nonce = EXCLUDED.nonce,
                updated_at = EXCLUDED.updated_at
            """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, configuration.connectorId)
                statement.setBoolean(2, configuration.enabled)
                statement.setBytes(3, configuration.ciphertext)
                statement.setBytes(4, configuration.nonce)
                statement.setTimestamp(5, Timestamp.from(configuration.updatedAt))
                statement.executeUpdate()
            }
        }
    }
}
