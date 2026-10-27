package io.github.cyrilleguipie.kontext.llm

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import io.github.cyrilleguipie.kontext.config.LlmConfig
import io.github.cyrilleguipie.kontext.domain.KontextError.LlmError
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

@Serializable
private data class EmbeddingRequest(
    val model: String,
    val input: List<String>,
)

@Serializable
private data class EmbeddingData(
    val index: Int,
    val embedding: List<Float>,
)

@Serializable
private data class EmbeddingResponse(
    val data: List<EmbeddingData>,
    val model: String? = null,
)

class EmbeddingClient(
    private val config: LlmConfig,
    private val httpClient: HttpClient,
    private val batchSize: Int = 32,
) {
    private val logger = LoggerFactory.getLogger(EmbeddingClient::class.java)

    suspend fun Raise<LlmError>.embed(text: String): List<Float> {
        val results = embedBatch(listOf(text))
        return results.first()
    }

    suspend fun Raise<LlmError>.embedBatch(texts: List<String>): List<List<Float>> {
        if (texts.isEmpty()) return emptyList()

        val allEmbeddings = mutableListOf<List<Float>>()

        for (batch in texts.chunked(batchSize)) {
            val batchResults = callEmbeddingApi(batch)
            allEmbeddings.addAll(batchResults)
        }

        return allEmbeddings
    }

    private suspend fun Raise<LlmError>.callEmbeddingApi(batch: List<String>): List<List<Float>> {
        val endpoint = "${config.baseUrl}/embeddings"
        val requestPayload =
            EmbeddingRequest(
                model = config.embeddingModel,
                input = batch,
            )

        val response =
            try {
                httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    if (config.apiKey.isNotBlank()) {
                        header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                    }
                    setBody(requestPayload)
                }
            } catch (ioe: IOException) {
                raise(LlmError.Connection("Failed to connect to LLM embedding endpoint at $endpoint", ioe))
            } catch (ce: CancellationException) {
                throw ce
            } catch (
                @Suppress("TooGenericExceptionCaught") ex: Exception,
            ) {
                raise(LlmError.Connection("Unexpected error while calling LLM embeddings at $endpoint", ex))
            }

        return handleEmbeddingResponse(response)
    }

    private suspend fun Raise<LlmError>.handleEmbeddingResponse(
        response: io.ktor.client.statement.HttpResponse,
    ): List<List<Float>> {
        return when (response.status) {
            HttpStatusCode.OK -> parseOkResponse(response)
            HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
                raise(LlmError.RateLimited(retryAfter))
            }
            else -> {
                val errorBody = response.bodyAsText()
                logger.error("LLM embeddings error (status {}): {}", response.status.value, errorBody)
                raise(LlmError.Server(response.status.value, errorBody))
            }
        }
    }

    private suspend fun Raise<LlmError>.parseOkResponse(
        response: io.ktor.client.statement.HttpResponse,
    ): List<List<Float>> {
        val body =
            try {
                response.body<EmbeddingResponse>()
            } catch (se: SerializationException) {
                val raw = response.bodyAsText()
                raise(LlmError.Connection("Failed to parse embedding response: $raw", se))
            }

        val sorted = body.data.sortedBy { it.index }
        for (item in sorted) {
            val actualDim = item.embedding.size
            ensure(actualDim == config.embeddingDimension) {
                LlmError.DimensionMismatch(expected = config.embeddingDimension, actual = actualDim)
            }
        }
        return sorted.map { it.embedding }
    }
}
