package io.github.cyrilleguipie.kontext.llm

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import io.github.cyrilleguipie.kontext.config.LlmConfig
import io.github.cyrilleguipie.kontext.domain.KontextError
import io.github.cyrilleguipie.kontext.domain.KontextError.LlmError
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

@WireMockTest
class ChatClientTest {
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
    fun `should stream tokens from SSE response`(wm: WireMockRuntimeInfo) =
        runBlocking {
            val config =
                LlmConfig(
                    baseUrl = wm.httpBaseUrl,
                    apiKey = "",
                    chatModel = "gemma-4",
                    embeddingModel = "bge-m3",
                    embeddingDimension = 1024,
                    temperature = 0.2,
                    maxContextChunks = 8,
                )

            val sseBody =
                """
                data: {"choices":[{"delta":{"content":"Hello"}}]}

                data: {"choices":[{"delta":{"content":" world"}}]}

                data: {"choices":[{"delta":{"content":"!"}}]}

                data: [DONE]

                """.trimIndent()

            wm.wireMock.register(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/event-stream")
                            .withBody(sseBody),
                    ),
            )

            val client = ChatClient(config, httpClient)

            val tokens = client.streamChat(listOf(ChatMessage("user", "Hi"))).toList()
            assertEquals(listOf("Hello", " world", "!"), tokens)
            assertEquals("Hello world!", tokens.joinToString(""))
        }

    @Test
    fun `should fail with RateLimited on HTTP 429 in chat stream`(wm: WireMockRuntimeInfo) =
        runBlocking {
            val config =
                LlmConfig(
                    baseUrl = wm.httpBaseUrl,
                    apiKey = "",
                    chatModel = "gemma-4",
                    embeddingModel = "bge-m3",
                    embeddingDimension = 1024,
                    temperature = 0.2,
                    maxContextChunks = 8,
                )

            wm.wireMock.register(
                post(urlEqualTo("/chat/completions"))
                    .willReturn(
                        aResponse()
                            .withStatus(429)
                            .withHeader("Retry-After", "5")
                            .withBody("Rate limited"),
                    ),
            )

            val client = ChatClient(config, httpClient)

            val error =
                try {
                    client.streamChat(listOf(ChatMessage("user", "Hi"))).collect {}
                    null
                } catch (ke: KontextError.Exception) {
                    ke.error as? LlmError
                }

            assertTrue(error is LlmError.RateLimited)
            assertEquals(5L, (error as LlmError.RateLimited).retryAfterSeconds)
        }
}
