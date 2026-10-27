package io.github.cyrilleguipie.kontext.llm

import arrow.core.raise.fold
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import io.github.cyrilleguipie.kontext.config.LlmConfig
import io.github.cyrilleguipie.kontext.domain.KontextError.LlmError
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

@WireMockTest
class EmbeddingClientTest {
    companion object {
        private lateinit var httpClient: HttpClient

        @JvmStatic
        @BeforeAll
        fun setupClient() {
            httpClient =
                HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }
        }

        @JvmStatic
        @AfterAll
        fun closeClient() {
            httpClient.close()
        }
    }

    @Test
    fun `should fetch embeddings successfully and match dimension`(wm: WireMockRuntimeInfo) =
        runBlocking {
            val config =
                LlmConfig(
                    baseUrl = wm.httpBaseUrl,
                    apiKey = "test-api-key",
                    chatModel = "test-chat",
                    embeddingModel = "bge-m3",
                    embeddingDimension = 4,
                    temperature = 0.2,
                    maxContextChunks = 5,
                )

            val wireMock = wm.wireMock
            wireMock.register(
                post(urlEqualTo("/embeddings"))
                    .withHeader("Authorization", equalTo("Bearer test-api-key"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                """
                                {
                                  "data": [
                                    {
                                      "index": 0,
                                      "embedding": [0.1, 0.2, 0.3, 0.4]
                                    }
                                  ],
                                  "model": "bge-m3"
                                }
                                """.trimIndent(),
                            ),
                    ),
            )

            val client = EmbeddingClient(config, httpClient)

            val result =
                fold(
                    block = { client.run { embed("Hello world") } },
                    recover = { error -> error },
                    transform = { it },
                )

            assertTrue(result is List<*>)
            @Suppress("UNCHECKED_CAST")
            val embedding = result as List<Float>
            assertEquals(4, embedding.size)
            assertEquals(0.1f, embedding[0])
        }

    @Test
    fun `should fail with DimensionMismatch when returned vector size does not match config`(wm: WireMockRuntimeInfo) =
        runBlocking {
            val config =
                LlmConfig(
                    baseUrl = wm.httpBaseUrl,
                    apiKey = "",
                    chatModel = "test-chat",
                    embeddingModel = "bge-m3",
                    // expects 4, but wiremock returns 2
                    embeddingDimension = 4,
                    temperature = 0.2,
                    maxContextChunks = 5,
                )

            wm.wireMock.register(
                post(urlEqualTo("/embeddings"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                """
                                {
                                  "data": [
                                    {
                                      "index": 0,
                                      "embedding": [0.1, 0.2]
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            ),
                    ),
            )

            val client = EmbeddingClient(config, httpClient)

            val result =
                fold(
                    block = { client.run { embed("test") } },
                    recover = { error -> error },
                    transform = { it },
                )

            assertTrue(result is LlmError.DimensionMismatch)
            val mismatch = result as LlmError.DimensionMismatch
            assertEquals(4, mismatch.expected)
            assertEquals(2, mismatch.actual)
        }

    @Test
    fun `should fail with RateLimited on HTTP 429`(wm: WireMockRuntimeInfo) =
        runBlocking {
            val config =
                LlmConfig(
                    baseUrl = wm.httpBaseUrl,
                    apiKey = "",
                    chatModel = "chat",
                    embeddingModel = "embed",
                    embeddingDimension = 4,
                    temperature = 0.2,
                    maxContextChunks = 5,
                )

            wm.wireMock.register(
                post(urlEqualTo("/embeddings"))
                    .willReturn(
                        aResponse()
                            .withStatus(429)
                            .withHeader("Retry-After", "10")
                            .withBody("""{"error": "rate limit"}"""),
                    ),
            )

            val client = EmbeddingClient(config, httpClient)

            val result =
                fold(
                    block = { client.run { embed("test") } },
                    recover = { error -> error },
                    transform = { it },
                )

            assertTrue(result is LlmError.RateLimited)
            val rateLimited = result as LlmError.RateLimited
            assertEquals(10L, rateLimited.retryAfterSeconds)
        }
}
