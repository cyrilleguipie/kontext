package io.github.cyrilleguipie.kontext.llm

import arrow.core.raise.Raise
import arrow.resilience.CircuitBreaker
import io.github.cyrilleguipie.kontext.config.LlmConfig
import io.github.cyrilleguipie.kontext.domain.KontextError
import io.github.cyrilleguipie.kontext.domain.KontextError.LlmError
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    val stream: Boolean = true,
)

@Serializable
private data class ChatDelta(
    val content: String? = null,
)

@Serializable
private data class ChatChoice(
    val delta: ChatDelta? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
private data class ChatChunkResponse(
    val choices: List<ChatChoice> = emptyList(),
)

class ChatClient(
    private val config: LlmConfig,
    private val httpClient: HttpClient,
    private val circuitBreaker: CircuitBreaker =
        CircuitBreaker(
            resetTimeout = 15.seconds,
            openingStrategy = CircuitBreaker.OpeningStrategy.Count(maxFailures = 3),
        ),
) {
    private val logger = LoggerFactory.getLogger(ChatClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun streamChat(messages: List<ChatMessage>): Flow<String> =
        flow {
            val endpoint = "${config.baseUrl}/chat/completions"
            val requestPayload =
                ChatCompletionRequest(
                    model = config.chatModel,
                    messages = messages,
                    temperature = config.temperature,
                    stream = true,
                )

            try {
                circuitBreaker.protectOrThrow {
                    executeChatStream(endpoint, requestPayload) { token ->
                        emit(token)
                    }
                }
            } catch (rle: LlmRateLimitException) {
                throw KontextError.Exception(LlmError.RateLimited(rle.retryAfterSeconds), rle)
            } catch (lse: LlmServerException) {
                throw KontextError.Exception(LlmError.Server(lse.statusCode, lse.body), lse)
            } catch (ioe: IOException) {
                throw KontextError.Exception(
                    LlmError.Connection("Failed to connect to LLM chat endpoint at $endpoint", ioe),
                    ioe,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (
                @Suppress("TooGenericExceptionCaught") ex: Exception,
            ) {
                throw KontextError.Exception(LlmError.Connection("Chat request failed: ${ex.message}", ex), ex)
            }
        }

    suspend fun Raise<LlmError>.consumeChat(
        messages: List<ChatMessage>,
        onToken: suspend (String) -> Unit,
    ) {
        try {
            streamChat(messages).collect(onToken)
        } catch (ke: KontextError.Exception) {
            if (ke.error is LlmError) raise(ke.error) else throw ke
        }
    }

    private suspend fun executeChatStream(
        endpoint: String,
        payload: ChatCompletionRequest,
        emitToken: suspend (String) -> Unit,
    ) {
        httpClient.preparePost(endpoint) {
            contentType(ContentType.Application.Json)
            if (config.apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            }
            setBody(payload)
        }.execute { response ->
            when (response.status) {
                HttpStatusCode.OK -> {
                    val channel: ByteReadChannel = response.body()
                    readSseTokens(channel, emitToken)
                }
                HttpStatusCode.TooManyRequests -> {
                    val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
                    throw LlmRateLimitException(retryAfter)
                }
                else -> {
                    val body = response.bodyAsText()
                    logger.error("LLM chat error (status {}): {}", response.status.value, body)
                    throw LlmServerException(response.status.value, body)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun readSseTokens(
        channel: ByteReadChannel,
        emitToken: suspend (String) -> Unit,
    ) {
        var keepReading = true
        while (keepReading && !channel.isClosedForRead) {
            val line = channel.readUTF8Line()
            if (line == null) {
                keepReading = false
            } else {
                keepReading = processSseLine(line, emitToken)
            }
        }
    }

    private suspend fun processSseLine(
        line: String,
        emitToken: suspend (String) -> Unit,
    ): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("data:")) {
            val data = trimmed.removePrefix("data:").trim()
            if (data == "[DONE]") {
                return false
            }
            extractToken(data)?.let { emitToken(it) }
        }
        return true
    }

    private fun extractToken(jsonStr: String): String? {
        return try {
            val chunk = json.decodeFromString<ChatChunkResponse>(jsonStr)
            chunk.choices.firstOrNull()?.delta?.content
        } catch (_: SerializationException) {
            null
        }
    }

    private class LlmRateLimitException(val retryAfterSeconds: Long?) : RuntimeException()

    private class LlmServerException(val statusCode: Int, val body: String) : RuntimeException()
}
