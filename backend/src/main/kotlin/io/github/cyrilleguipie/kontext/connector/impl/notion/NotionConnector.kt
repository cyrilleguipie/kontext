package io.github.cyrilleguipie.kontext.connector.impl.notion

import io.github.cyrilleguipie.kontext.config.NotionConfig
import io.github.cyrilleguipie.kontext.connector.spi.RawDocument
import io.github.cyrilleguipie.kontext.connector.spi.SourceConnector
import io.github.cyrilleguipie.kontext.domain.DocumentSource
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

class NotionConnector(
    private val config: NotionConfig,
    private val httpClient: HttpClient,
) : SourceConnector {
    override val connectorId: String = "notion"
    override val source: DocumentSource = DocumentSource.NOTION
    override val allowEmptySnapshotCleanup: Boolean = true
    private val json = Json { ignoreUnknownKeys = true }

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> =
        flow {
            var cursor: String? = null
            val seenCursors = mutableSetOf<String>()
            while (true) {
                val page = searchPages(cursor)
                page["results"]?.jsonArray.orEmpty().forEach { pageJson ->
                    val pageObject = pageJson.jsonObject
                    if (pageObject["in_trash"].asBoolean() || pageObject["is_archived"].asBoolean()) return@forEach
                    val id = pageObject["id"].asString() ?: return@forEach
                    val title = pageTitle(pageObject).ifBlank { "Untitled Notion page" }
                    val body = loadChildren(id, depth = 0, budget = BlockBudget()).trim()
                    val timestamp = pageObject["last_edited_time"].asString()?.let(::parseInstant) ?: Instant.EPOCH
                    emit(
                        RawDocument(
                            externalId = id,
                            title = title,
                            body = body.ifBlank { title },
                            url = pageObject["url"].asString() ?: "https://www.notion.so/$id",
                            metadata = pageMetadata(pageObject),
                            updatedAt = timestamp,
                        ),
                    )
                }
                val hasMore = page["has_more"].asBoolean()
                val next = page["next_cursor"].asString()
                if (!hasMore || next.isNullOrBlank()) break
                check(seenCursors.add(next)) { "Notion returned a repeated search cursor" }
                cursor = next
            }
        }

    private suspend fun searchPages(cursor: String?): JsonObject {
        val payload =
            buildJsonObject {
                put("page_size", PAGE_SIZE)
                putJsonObject("sort") {
                    put("direction", "descending")
                    put("timestamp", "last_edited_time")
                }
                putJsonObject("filter") {
                    put("property", "object")
                    put("value", "page")
                }
                cursor?.let { put("start_cursor", it) }
            }
        return requestJson("Notion page search") {
            httpClient.post("$API_BASE/search") {
                notionHeaders()
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        }
    }

    private suspend fun loadChildren(
        blockId: String,
        depth: Int,
        budget: BlockBudget,
    ): String {
        if (depth > MAX_BLOCK_DEPTH || budget.count >= MAX_BLOCKS_PER_PAGE) return ""
        val output = StringBuilder()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        while (budget.count < MAX_BLOCKS_PER_PAGE) {
            val page =
                requestJson("Notion block children") {
                    httpClient.get("$API_BASE/blocks/$blockId/children") {
                        notionHeaders()
                        parameter("page_size", PAGE_SIZE)
                        cursor?.let { parameter("start_cursor", it) }
                    }
                }
            val blocks = page["results"]?.jsonArray.orEmpty()
            for (blockElement in blocks) {
                appendBlock(blockElement, depth, budget, output)
            }
            val next = page["next_cursor"].asString()
            if (!page["has_more"].asBoolean() || next.isNullOrBlank()) break
            check(seenCursors.add(next)) { "Notion returned a repeated block cursor" }
            cursor = next
        }
        return output.toString()
    }

    private suspend fun appendBlock(
        blockElement: JsonElement,
        depth: Int,
        budget: BlockBudget,
        output: StringBuilder,
    ) {
        if (budget.count >= MAX_BLOCKS_PER_PAGE) return
        budget.count++
        val block = blockElement.jsonObject
        output.append(renderBlock(block))
        val childId = block["id"].asString().takeIf { block["has_children"].asBoolean() }
        childId?.let { output.append(loadChildren(it, depth + 1, budget)) }
    }

    private fun renderBlock(block: JsonObject): String =
        block["type"].asString()?.let { type ->
            (block[type] as? JsonObject)?.let { content -> renderTypedBlock(type, content) }
        }.orEmpty()

    private fun renderTypedBlock(
        type: String,
        content: JsonObject,
    ): String {
        val text =
            (content["rich_text"] as? JsonArray).richText()
                .ifBlank { (content["title"] as? JsonArray).richText() }
        return renderHeading(type, text)
            ?: renderListItem(type, content, text)
            ?: renderSpecialBlock(type, content, text)
            ?: text.takeIf(String::isNotBlank)?.let { "$it\n\n" }.orEmpty()
    }

    private fun renderHeading(
        type: String,
        text: String,
    ): String? =
        when (type) {
            "heading_1" -> "# $text\n\n"
            "heading_2" -> "## $text\n\n"
            "heading_3" -> "### $text\n\n"
            else -> null
        }

    private fun renderListItem(
        type: String,
        content: JsonObject,
        text: String,
    ): String? =
        when (type) {
            "bulleted_list_item" -> "- $text\n"
            "numbered_list_item" -> "1. $text\n"
            "to_do" -> "- [${if (content["checked"].asBoolean()) "x" else " "}] $text\n"
            else -> null
        }

    private fun renderSpecialBlock(
        type: String,
        content: JsonObject,
        text: String,
    ): String? =
        when (type) {
            "quote" -> "> $text\n\n"
            "code" -> "```${content["language"].asString()?.let { " $it" }.orEmpty()}\n$text\n```\n\n"
            "child_page" -> "## ${content["title"].asString().orEmpty()}\n\n"
            "table_row" -> "| ${text.replace("\n", " | ")} |\n"
            else -> null
        }

    private fun pageTitle(page: JsonObject): String {
        val properties = page["properties"] as? JsonObject ?: return ""
        return properties.values.firstNotNullOfOrNull { property ->
            val value = property as? JsonObject ?: return@firstNotNullOfOrNull null
            if (value["type"].asString() != "title") return@firstNotNullOfOrNull null
            (value["title"] as? JsonArray).richText()
        }.orEmpty()
    }

    private fun pageMetadata(page: JsonObject): Map<String, String> =
        buildMap {
            val parent = page["parent"] as? JsonObject
            val parentType = parent?.get("type").asString()
            if (parentType != null) put("parent_type", parentType)
            parentType?.let { type -> parent?.get("${type}_id").asString()?.let { put("parent_id", it) } }
        }

    private fun List<JsonElement>?.richText(): String =
        this.orEmpty().mapNotNull { item ->
            val objectValue = item as? JsonObject
            objectValue?.get("plain_text").asString()
                ?: (objectValue?.get("text") as? JsonObject)?.get("content").asString()
        }.joinToString("")

    private fun JsonElement?.asString(): String? = runCatching { this?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonElement?.asBoolean(): Boolean =
        runCatching { this?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false }.getOrDefault(false)

    private suspend fun requestJson(
        operation: String,
        request: suspend () -> HttpResponse,
    ): JsonObject {
        var attempt = 0
        while (true) {
            delay(REQUEST_SPACING_MILLIS)
            val response = request()
            if (response.status == HttpStatusCode.TooManyRequests && attempt < MAX_RETRIES) {
                val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: DEFAULT_RETRY_SECONDS
                delay(retryAfter.coerceIn(1, MAX_RETRY_DELAY_SECONDS).times(1_000))
                attempt++
                continue
            }
            if (response.status != HttpStatusCode.OK) {
                val body = response.bodyAsText().take(MAX_ERROR_BODY_LENGTH)
                error("$operation failed with HTTP ${response.status.value}: $body")
            }
            return json.parseToJsonElement(response.bodyAsText()).jsonObject
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.notionHeaders() {
        accept(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer ${config.token}")
        header("Notion-Version", config.apiVersion)
    }

    private fun parseInstant(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse { Instant.EPOCH }

    private class BlockBudget(var count: Int = 0)

    private companion object {
        const val API_BASE = "https://api.notion.com/v1"
        const val PAGE_SIZE = 100
        const val MAX_BLOCK_DEPTH = 8
        const val MAX_BLOCKS_PER_PAGE = 5_000
        const val MAX_ERROR_BODY_LENGTH = 2_000
        const val REQUEST_SPACING_MILLIS = 350L
        const val MAX_RETRIES = 3
        const val DEFAULT_RETRY_SECONDS = 2L
        const val MAX_RETRY_DELAY_SECONDS = 60L
    }
}
