package io.github.cyrilleguipie.kontext.connector.impl.linear

import io.github.cyrilleguipie.kontext.config.LinearConfig
import io.github.cyrilleguipie.kontext.connector.spi.RawDocument
import io.github.cyrilleguipie.kontext.connector.spi.SourceConnector
import io.github.cyrilleguipie.kontext.domain.DocumentSource
import io.github.cyrilleguipie.kontext.domain.KontextError
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import java.time.Instant

class LinearConnector(
    private val config: LinearConfig,
    private val httpClient: HttpClient,
) : SourceConnector {
    override val connectorId: String = "linear"
    override val source: DocumentSource = DocumentSource.LINEAR
    override val allowEmptySnapshotCleanup: Boolean = false

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> =
        flow {
            emitAllPages(ISSUES_QUERY, { it.issues?.pageInfo }) { page ->
                page.issues?.nodes.orEmpty().map { it.toRawDocument() }
            }
            emitAllPages(PROJECTS_QUERY, { it.projects?.pageInfo }) { page ->
                page.projects?.nodes.orEmpty().map { it.toRawDocument() }
            }
            emitAllPages(DOCUMENTS_QUERY, { it.documents?.pageInfo }) { page ->
                page.documents?.nodes.orEmpty().map { it.toRawDocument() }
            }
        }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<RawDocument>.emitAllPages(
        query: String,
        pageInfo: (LinearData) -> LinearPageInfo?,
        documents: (LinearData) -> List<RawDocument>,
    ) {
        var cursor: String? = null
        val visitedCursors = mutableSetOf<String>()
        var pageNumber = 0
        do {
            pageNumber++
            if (pageNumber > MAX_PAGES) failLinear("Linear pagination exceeded the safety limit")
            val page = fetchPage(query, cursor)
            val data = page.data ?: failLinear("Linear response did not include data")
            documents(data).forEach { emit(it) }
            val currentPageInfo = pageInfo(data) ?: failLinear("Linear response did not include a result connection")
            if (!currentPageInfo.hasNextPage) break
            cursor = currentPageInfo.endCursor
            if (cursor.isNullOrBlank() || !visitedCursors.add(cursor)) {
                failLinear(
                    "Linear returned an empty or repeated pagination cursor",
                )
            }
        } while (true)
    }

    private suspend fun fetchPage(
        query: String,
        cursor: String?,
    ): LinearGraphQlResponse {
        val response =
            httpClient.post(config.apiBaseUrl) {
                header(HttpHeaders.Authorization, config.apiKey)
                contentType(ContentType.Application.Json)
                setBody(LinearGraphQlRequest(query, mapOf("after" to cursor)))
            }
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            failLinear(KontextError.LinearError.Authentication)
        }
        if (response.status.value !in 200..299) {
            val detail = response.bodyAsText().take(MAX_ERROR_BODY_LENGTH)
            failLinear("Linear returned HTTP ${response.status.value}: $detail")
        }
        val result: LinearGraphQlResponse = response.body()
        val errors = result.errors.orEmpty().map(LinearGraphQlError::message).filter(String::isNotBlank)
        if (errors.isNotEmpty()) failLinear("Linear GraphQL request failed: ${errors.joinToString("; ")}")
        return result
    }

    private fun LinearIssue.toRawDocument(): RawDocument {
        val labelsText = labels.nodes.map(LinearLabel::name).filter(String::isNotBlank)
        val text =
            buildString {
                append("# $identifier — $title\n\n")
                append("Team: ${team.name}\n")
                state?.name?.let { append("State: $it\n") }
                assignee?.name?.let { append("Assignee: $it\n") }
                project?.name?.let { append("Project: $it\n") }
                if (labelsText.isNotEmpty()) append("Labels: ${labelsText.joinToString(", ")}\n")
                append("\n")
                description?.takeIf(String::isNotBlank)?.let { append(it) }
            }.take(MAX_DOCUMENT_LENGTH)
        return RawDocument(
            externalId = "issue:$id",
            title = "$identifier: $title".take(MAX_TITLE_LENGTH),
            body = text,
            url = url,
            metadata =
                buildMap {
                    put("kind", "issue")
                    put("identifier", identifier)
                    put("team", team.name)
                    state?.name?.let { put("state", it) }
                    assignee?.name?.let { put("assignee", it) }
                    project?.name?.let { put("project", it) }
                    if (labelsText.isNotEmpty()) put("labels", labelsText.joinToString(", "))
                },
            updatedAt = parseInstant(updatedAt),
        )
    }

    private fun LinearProject.toRawDocument(): RawDocument =
        RawDocument(
            externalId = "project:$id",
            title = "Project: $name".take(MAX_TITLE_LENGTH),
            body = (description?.takeIf(String::isNotBlank) ?: name).take(MAX_DOCUMENT_LENGTH),
            url = url,
            metadata = mapOf("kind" to "project", "projectId" to id),
            updatedAt = parseInstant(updatedAt),
        )

    private fun LinearDocument.toRawDocument(): RawDocument =
        RawDocument(
            externalId = "document:$id",
            title = "Document: $title".take(MAX_TITLE_LENGTH),
            body = (content?.takeIf(String::isNotBlank) ?: title).take(MAX_DOCUMENT_LENGTH),
            url = url,
            metadata =
                mapOf("kind" to "document") +
                    (project?.name?.let { mapOf("project" to it) } ?: emptyMap()),
            updatedAt = parseInstant(updatedAt),
        )

    private fun parseInstant(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse { Instant.EPOCH }

    private fun failLinear(message: String): Nothing = failLinear(KontextError.LinearError.ApiFailure(message))

    private fun failLinear(error: KontextError.LinearError): Nothing = throw KontextError.Exception(error)

    private companion object {
        const val MAX_PAGES = 10_000
        const val MAX_DOCUMENT_LENGTH = 250_000
        const val MAX_TITLE_LENGTH = 500
        const val MAX_ERROR_BODY_LENGTH = 2_000
        const val ISSUES_QUERY =
            """
            query KontextIssues(${'$'}after: String) {
              issues(first: 100, after: ${'$'}after, includeArchived: true, orderBy: updatedAt) {
                nodes {
                  id identifier title description url updatedAt
                  state { name } assignee { name } team { name } project { name }
                  labels { nodes { name } }
                }
                pageInfo { hasNextPage endCursor }
              }
            }
            """
        const val PROJECTS_QUERY =
            """
            query KontextProjects(${'$'}after: String) {
              projects(first: 100, after: ${'$'}after, includeArchived: true, orderBy: updatedAt) {
                nodes { id name description url updatedAt }
                pageInfo { hasNextPage endCursor }
              }
            }
            """
        const val DOCUMENTS_QUERY =
            """
            query KontextDocuments(${'$'}after: String) {
              documents(first: 100, after: ${'$'}after, includeArchived: true, orderBy: updatedAt) {
                nodes { id title content url updatedAt project { name } }
                pageInfo { hasNextPage endCursor }
              }
            }
            """
    }
}

@Serializable
private data class LinearGraphQlRequest(
    val query: String,
    val variables: Map<String, String?>,
)

@Serializable
private data class LinearGraphQlResponse(
    val data: LinearData? = null,
    val errors: List<LinearGraphQlError>? = null,
)

@Serializable
private data class LinearGraphQlError(val message: String)

@Serializable
private data class LinearData(
    val issues: LinearIssueConnection? = null,
    val projects: LinearProjectConnection? = null,
    val documents: LinearDocumentConnection? = null,
)

@Serializable
private data class LinearIssueConnection(
    val nodes: List<LinearIssue> = emptyList(),
    val pageInfo: LinearPageInfo,
)

@Serializable
private data class LinearProjectConnection(
    val nodes: List<LinearProject> = emptyList(),
    val pageInfo: LinearPageInfo,
)

@Serializable
private data class LinearDocumentConnection(
    val nodes: List<LinearDocument> = emptyList(),
    val pageInfo: LinearPageInfo,
)

@Serializable
private data class LinearPageInfo(val hasNextPage: Boolean, val endCursor: String? = null)

@Serializable
private data class LinearIssue(
    val id: String,
    val identifier: String,
    val title: String,
    val description: String? = null,
    val url: String,
    val updatedAt: String,
    val state: LinearNamedEntity? = null,
    val assignee: LinearNamedEntity? = null,
    val team: LinearNamedEntity,
    val project: LinearNamedEntity? = null,
    val labels: LinearLabelConnection = LinearLabelConnection(),
)

@Serializable
private data class LinearProject(
    val id: String,
    val name: String,
    val description: String? = null,
    val url: String,
    val updatedAt: String,
)

@Serializable
private data class LinearDocument(
    val id: String,
    val title: String,
    val content: String? = null,
    val url: String,
    val updatedAt: String,
    val project: LinearNamedEntity? = null,
)

@Serializable
private data class LinearNamedEntity(val name: String)

@Serializable
private data class LinearLabelConnection(val nodes: List<LinearLabel> = emptyList())

@Serializable
private data class LinearLabel(val name: String)
