package io.github.cyrilleguipie.kontext.api

import io.github.cyrilleguipie.kontext.domain.KontextError
import io.github.cyrilleguipie.kontext.domain.SearchHit
import io.github.cyrilleguipie.kontext.llm.ChatMessage
import io.github.cyrilleguipie.kontext.llm.RagEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.sql.SQLException

@Serializable
data class ChatRequest(
    val message: String,
    val history: List<ChatMessage> = emptyList(),
)

@Serializable
data class Citation(
    val index: Int,
    val title: String,
    val url: String,
    val source: String,
    val excerpt: String,
)

private fun SearchHit.toCitation(index: Int) = Citation(index, title, url, source.dbValue, content)

fun Route.chatRoutes(ragEngine: RagEngine) {
    post("/api/chat") {
        val request =
            try {
                call.receive<ChatRequest>()
            } catch (_: ContentTransformationException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid chat request"))
                return@post
            }
        val validationError = request.validationError()
        if (validationError != null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
            return@post
        }
        try {
            val answer = ragEngine.answer(request.message.trim(), request.history)
            call.respond(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Text.EventStream
                    override val headers =
                        headersOf(
                            HttpHeaders.CacheControl to listOf("no-cache"),
                            "X-Accel-Buffering" to listOf("no"),
                        )

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        suspend fun sendEvent(
                            name: String,
                            data: String,
                        ) {
                            channel.writeStringUtf8("event: $name\ndata: $data\n\n")
                            channel.flush()
                        }
                        sendEvent(
                            "sources",
                            Json.encodeToString(answer.sources.mapIndexed { index, hit -> hit.toCitation(index + 1) }),
                        )
                        try {
                            answer.tokens.collect { token -> sendEvent("token", Json.encodeToString(token)) }
                            sendEvent("done", "{}")
                        } catch (cause: CancellationException) {
                            throw cause
                        } catch (cause: IOException) {
                            sendEvent("error", Json.encodeToString(cause.message ?: "Chat generation failed"))
                        } catch (cause: SerializationException) {
                            sendEvent("error", Json.encodeToString(cause.message ?: "Chat generation failed"))
                        } catch (cause: IllegalStateException) {
                            sendEvent("error", Json.encodeToString(cause.message ?: "Chat generation failed"))
                        }
                    }
                },
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: KontextError.Exception) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to (cause.message ?: "Chat request failed")))
        } catch (cause: IOException) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to (cause.message ?: "Chat request failed")))
        } catch (cause: SerializationException) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to (cause.message ?: "Chat request failed")))
        } catch (cause: SQLException) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "Search failed")))
        } catch (cause: IllegalStateException) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to (cause.message ?: "Chat request failed")))
        }
    }
}

private fun ChatRequest.validationError(): String? =
    if (message.isBlank() || message.length > MAX_MESSAGE_LENGTH) {
        "Message must contain 1 to $MAX_MESSAGE_LENGTH characters"
    } else {
        null
    }

private const val MAX_MESSAGE_LENGTH = 12_000
