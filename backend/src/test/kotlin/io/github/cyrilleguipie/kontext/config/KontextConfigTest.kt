package io.github.cyrilleguipie.kontext.config

import arrow.core.raise.fold
import com.typesafe.config.ConfigFactory
import io.github.cyrilleguipie.kontext.config.KontextConfig.Companion.validate
import io.github.cyrilleguipie.kontext.domain.KontextError.ConfigError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KontextConfigTest {
    @Test
    fun `should parse valid configuration successfully`() {
        val configHocon =
            """
            kontext {
              server { port = 9090 }
              database {
                url = "jdbc:postgresql://localhost:5432/kontext_test"
                user = "test_user"
                password = "super_secret_db_password"
              }
              llm {
                base-url = "http://localhost:1234/v1"
                api-key = "super_secret_llm_key"
                chat-model = "gemma-4"
                embedding-model = "bge-m3"
                embedding-dimension = 1024
                temperature = 0.5
                max-context-chunks = 10
              }
              atlassian {
                flavor = "cloud"
                jira {
                  base-url = "https://example.atlassian.net"
                  projects = ["PROJ1", "PROJ2"]
                  jql-extra = "status != Closed"
                }
                confluence {
                  base-url = "https://example.atlassian.net/wiki"
                  spaces = ["SPACE1"]
                }
                auth {
                  email = "user@example.com"
                  token = "super_secret_atlassian_token"
                }
                max-concurrency = 8
              }
            }
            """.trimIndent()

        val parsed =
            fold(
                block = { validate(ConfigFactory.parseString(configHocon)) },
                recover = { error -> error },
                transform = { it },
            )

        assertTrue(parsed is KontextConfig)
        val config = parsed as KontextConfig

        assertEquals(9090, config.server.port)
        assertEquals("jdbc:postgresql://localhost:5432/kontext_test", config.database.url)
        assertEquals("test_user", config.database.user)
        assertEquals("super_secret_db_password", config.database.password)
        assertEquals(1024, config.llm.embeddingDimension)
        assertEquals("gemma-4", config.llm.chatModel)
        assertEquals(8, config.atlassian.maxConcurrency)
        assertEquals(AtlassianFlavor.CLOUD, config.atlassian.flavor)
    }

    @Test
    fun `should never leak secrets in toString`() {
        val auth = AtlassianAuthConfig(email = "test@example.com", token = "secret_token_123")
        assertFalse(auth.toString().contains("secret_token_123"))
        assertTrue(auth.toString().contains("***"))

        val db =
            DatabaseConfig(
                url = "jdbc:postgresql://localhost:5432/db",
                user = "admin",
                password = "secret_password",
            )
        assertFalse(db.toString().contains("secret_password"))
        assertTrue(db.toString().contains("***"))

        val llm =
            LlmConfig(
                baseUrl = "http://localhost:1234/v1",
                apiKey = "secret_key",
                chatModel = "gemma",
                embeddingModel = "bge",
                embeddingDimension = 1024,
                temperature = 0.2,
                maxContextChunks = 8,
            )
        assertFalse(llm.toString().contains("secret_key"))
        assertTrue(llm.toString().contains("***"))
    }

    @Test
    fun `should fail when port is invalid`() {
        val configHocon =
            """
            kontext {
              server { port = 99999 }
              database { url = "jdbc:postgresql://localhost/db", user = "u" }
              llm { base-url = "http://llm", chat-model = "m", embedding-model = "e" }
            }
            """.trimIndent()

        val result =
            fold(
                block = { validate(ConfigFactory.parseString(configHocon)) },
                recover = { error -> error },
                transform = { it },
            )

        assertTrue(result is ConfigError.Invalid)
        val invalid = result as ConfigError.Invalid
        assertEquals("server.port", invalid.key)
    }

    @Test
    fun `should fail when embedding dimension is non-positive`() {
        val configHocon =
            """
            kontext {
              server { port = 8080 }
              database { url = "jdbc:postgresql://localhost/db", user = "u" }
              llm {
                base-url = "http://llm"
                chat-model = "m"
                embedding-model = "e"
                embedding-dimension = 0
              }
            }
            """.trimIndent()

        val result =
            fold(
                block = { validate(ConfigFactory.parseString(configHocon)) },
                recover = { error -> error },
                transform = { it },
            )

        assertTrue(result is ConfigError.Invalid)
        val invalid = result as ConfigError.Invalid
        assertEquals("llm.embedding-dimension", invalid.key)
    }
}
