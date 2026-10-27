package io.github.cyrilleguipie.kontext.connector.impl.jira

import io.github.cyrilleguipie.kontext.config.AtlassianConfig
import io.github.cyrilleguipie.kontext.config.AtlassianFlavor
import io.github.cyrilleguipie.kontext.connector.spi.RawDocument
import io.github.cyrilleguipie.kontext.connector.spi.SourceConnector
import io.github.cyrilleguipie.kontext.domain.DocumentSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime

class JiraConnector(
    private val config: AtlassianConfig,
    private val httpClient: HttpClient,
) : SourceConnector {
    override val connectorId: String = "jira"
    override val source: DocumentSource = DocumentSource.JIRA
    override val allowEmptySnapshotCleanup: Boolean = true

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> =
        if (config.flavor == AtlassianFlavor.CLOUD) {
            fetchCloudIssues(
                buildJql(since),
            )
        } else {
            fetchDataCenterIssues(buildJql(since))
        }

    private fun fetchCloudIssues(jql: String): Flow<RawDocument> =
        flow {
            var nextPageToken: String? = null
            val seenTokens = mutableSetOf<String>()
            var hasNext = true
            while (hasNext) {
                val page = fetchPage(jql, startAt = 0, nextPageToken = nextPageToken)
                page.issues.forEach { issue -> emit(issue.toRawDocument(config.jira.baseUrl)) }
                val next = page.nextPageToken
                hasNext = next != null
                next?.let { token ->
                    check(seenTokens.add(token)) { "Jira returned a repeated nextPageToken" }
                    nextPageToken = token
                }
            }
        }

    private fun fetchDataCenterIssues(jql: String): Flow<RawDocument> =
        flow {
            var startAt = 0
            var hasNext = true
            while (hasNext) {
                val page = fetchPage(jql, startAt, nextPageToken = null)
                page.issues.forEach { issue -> emit(issue.toRawDocument(config.jira.baseUrl)) }
                startAt = (page.startAt ?: startAt) + page.issues.size
                hasNext = page.issues.isNotEmpty() && (page.total == null || startAt < page.total)
            }
        }

    private fun buildJql(since: Instant?): String {
        val clauses = mutableListOf<String>()
        if (config.jira.projects.isNotEmpty()) {
            val projectKeys = config.jira.projects.joinToString(", ") { key -> "\"${key.replace("\"", "\\\"")}\"" }
            clauses += "project in ($projectKeys)"
        }
        if (config.jira.jqlExtra.isNotBlank()) clauses += "(${config.jira.jqlExtra})"
        if (since != null) clauses += "updated >= \"${since}\""
        return clauses.joinToString(" AND ").ifBlank { "ORDER BY updated DESC" }
            .let { if (it == "ORDER BY updated DESC") it else "$it ORDER BY updated DESC" }
    }

    private suspend fun fetchPage(
        jql: String,
        startAt: Int,
        nextPageToken: String?,
    ): JiraSearchPage {
        val cloud = config.flavor == AtlassianFlavor.CLOUD
        val endpoint =
            if (cloud) {
                "${config.jira.baseUrl}/rest/api/3/search/jql"
            } else {
                "${config.jira.baseUrl}/rest/api/2/search"
            }
        val response =
            httpClient.get(endpoint) {
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.Authorization, authorizationHeader())
                parameter("jql", jql)
                parameter("maxResults", PAGE_SIZE)
                parameter("fields", "summary,description,issuetype,project,status,assignee,labels,updated,created")
                if (cloud) {
                    nextPageToken?.let { parameter("nextPageToken", it) }
                } else {
                    parameter("startAt", startAt)
                }
            }
        if (response.status != HttpStatusCode.OK) {
            val body = response.bodyAsText().take(MAX_ERROR_BODY_LENGTH)
            error("Jira search failed with HTTP ${response.status.value}: $body")
        }
        return response.body()
    }

    private fun authorizationHeader(): String {
        return if (config.flavor == AtlassianFlavor.CLOUD) {
            val credential = "${config.auth.email}:${config.auth.token}"
            "Basic ${java.util.Base64.getEncoder().encodeToString(credential.toByteArray(Charsets.UTF_8))}"
        } else {
            "Bearer ${config.auth.token}"
        }
    }

    private fun JiraIssue.toRawDocument(baseUrl: String): RawDocument {
        val fields = fields
        val body =
            buildString {
                appendLine(fields.description?.toPlainText().orEmpty())
                fields.status?.name?.let { appendLine("Status: $it") }
                fields.assignee?.displayName?.let { appendLine("Assignee: $it") }
                if (fields.labels.isNotEmpty()) appendLine("Labels: ${fields.labels.joinToString(", ")}")
            }.trim()
        val title = "$key: ${fields.summary.orEmpty()}".trim()
        return RawDocument(
            externalId = key,
            title = title,
            body = body.ifBlank { title },
            url = "$baseUrl/browse/$key",
            metadata =
                buildMap {
                    fields.project?.key?.let { put("project", it) }
                    fields.issuetype?.name?.let { put("type", it) }
                    fields.status?.name?.let { put("status", it) }
                    if (fields.labels.isNotEmpty()) put("labels", fields.labels.joinToString(","))
                },
            updatedAt = fields.updated.toInstantOrEpoch(),
        )
    }

    private fun String?.toInstantOrEpoch(): Instant {
        if (this == null) return Instant.EPOCH
        val normalized = replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        return runCatching { OffsetDateTime.parse(normalized).toInstant() }.getOrElse { Instant.EPOCH }
    }

    private fun JsonElement.toPlainText(): String =
        buildString {
            fun appendText(element: JsonElement) {
                when (element) {
                    is JsonObject -> {
                        element["text"]?.let { text ->
                            if (text is JsonPrimitive) append(text.contentOrNull.orEmpty())
                        }
                        if (element["type"]?.jsonPrimitive?.contentOrNull == "hardBreak") append('\n')
                        element["content"]?.let { children ->
                            if (children is JsonArray) children.forEach(::appendText)
                        }
                        if (element["type"]?.jsonPrimitive?.contentOrNull in BLOCK_TYPES) append('\n')
                    }
                    is JsonArray -> element.forEach(::appendText)
                    is JsonPrimitive -> if (element.isString) append(element.content)
                }
            }
            appendText(this@toPlainText)
        }.trim()

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_ERROR_BODY_LENGTH = 2_000
        val BLOCK_TYPES = setOf("paragraph", "heading", "listItem", "blockquote", "codeBlock")
    }
}

@Serializable
private data class JiraSearchPage(
    val issues: List<JiraIssue> = emptyList(),
    val total: Int? = null,
    val startAt: Int? = null,
    val nextPageToken: String? = null,
)

@Serializable
private data class JiraIssue(
    val key: String,
    val fields: JiraIssueFields,
)

@Serializable
private data class JiraIssueFields(
    val summary: String? = null,
    val description: JsonElement? = null,
    val issuetype: JiraNamedField? = null,
    val project: JiraProjectField? = null,
    val status: JiraNamedField? = null,
    val assignee: JiraUserField? = null,
    val labels: List<String> = emptyList(),
    val updated: String? = null,
)

@Serializable
private data class JiraNamedField(
    val name: String? = null,
)

@Serializable
private data class JiraProjectField(
    val key: String? = null,
)

@Serializable
private data class JiraUserField(
    val displayName: String? = null,
)
