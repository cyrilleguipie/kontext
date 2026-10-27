package io.github.cyrilleguipie.kontext.connector.impl.gitlab

import io.github.cyrilleguipie.kontext.config.GitLabConfig
import io.github.cyrilleguipie.kontext.connector.spi.RawDocument
import io.github.cyrilleguipie.kontext.connector.spi.SourceConnector
import io.github.cyrilleguipie.kontext.domain.DocumentSource
import io.github.cyrilleguipie.kontext.domain.KontextError
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class GitLabConnector(
    private val config: GitLabConfig,
    private val httpClient: HttpClient,
) : SourceConnector {
    override val connectorId: String = "gitlab"
    override val source: DocumentSource = DocumentSource.GITLAB
    override val allowEmptySnapshotCleanup: Boolean = false

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> =
        flow {
            config.projects.forEach { project ->
                val projectId = encodeProjectPath(project)
                emitAllPages<GitLabRecord>("projects/$projectId/issues", project, "issue") {
                    it.toIssueDocument(project)
                }
                emitAllPages<GitLabRecord>(
                    "projects/$projectId/merge_requests",
                    project,
                    "merge_request",
                ) {
                    it.toMergeRequestDocument(project)
                }
                emitAllPages<GitLabWikiPage>("projects/$projectId/wikis", project, "wiki") {
                    it.toWikiDocument(project)
                }
            }
        }

    private suspend fun <Item : Any> kotlinx.coroutines.flow.FlowCollector<RawDocument>.emitAllPages(
        path: String,
        project: String,
        kind: String,
        convert: (Item) -> RawDocument,
    ) {
        var page = 1
        while (true) {
            val response =
                httpClient.get("${config.apiBaseUrl}/$path") {
                    header("PRIVATE-TOKEN", config.token)
                    parameter("per_page", PAGE_SIZE)
                    parameter("page", page)
                    if (kind != "wiki") parameter("state", "all")
                }.requireSuccess("GitLab $kind records for $project")
            val items: List<Item> = response.body()
            items.forEach { emit(convert(it)) }
            if (items.size < PAGE_SIZE) break
            page++
        }
    }

    private fun GitLabRecord.toIssueDocument(project: String): RawDocument = toRawDocument(project, "issue", "Issue")

    private fun GitLabRecord.toMergeRequestDocument(project: String): RawDocument =
        toRawDocument(project, "merge_request", "Merge request")

    private fun GitLabRecord.toRawDocument(
        project: String,
        kind: String,
        label: String,
    ): RawDocument {
        val text =
            buildString {
                append("# $title\n\n")
                append("Project: $project\n")
                append("Type: $label\n")
                state?.let { append("State: $it\n") }
                author?.username?.let { append("Author: $it\n") }
                labels.orEmpty().takeIf(List<String>::isNotEmpty)?.let { labels ->
                    append("Labels: ${labels.joinToString(", ")}\n")
                }
                append("\n")
                description?.takeIf(String::isNotBlank)?.let { append(it) }
            }.trim()
        return RawDocument(
            externalId = "$project:$kind:$id",
            title = title,
            body = text,
            url = webUrl,
            metadata =
                mapOf("project" to project, "kind" to kind) +
                    (state?.let { mapOf("state" to it) } ?: emptyMap()),
            updatedAt = parseInstant(updatedAt),
        )
    }

    private fun GitLabWikiPage.toWikiDocument(project: String): RawDocument =
        RawDocument(
            externalId = "$project:wiki:$slug",
            title = title,
            body = content,
            url = webUrl ?: wikiUrl(project, slug),
            metadata = mapOf("project" to project, "kind" to "wiki", "slug" to slug),
            updatedAt = parseInstant(updatedAt),
        )

    private suspend fun io.ktor.client.statement.HttpResponse.requireSuccess(operation: String) =
        apply {
            if (status != HttpStatusCode.OK) {
                val error =
                    when (status) {
                        HttpStatusCode.Unauthorized,
                        HttpStatusCode.Forbidden,
                        ->
                            KontextError.GitLabError.Authentication
                        HttpStatusCode.TooManyRequests ->
                            KontextError.GitLabError.RateLimited(
                                headers[HttpHeaders.RetryAfter],
                            )
                        else ->
                            KontextError.GitLabError.HttpFailure(
                                status.value,
                                "$operation: ${bodyAsText().take(MAX_ERROR_BODY_LENGTH)}",
                            )
                    }
                throw KontextError.Exception(error)
            }
        }

    private fun parseInstant(value: String?): Instant =
        value?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH

    private fun encodeProjectPath(project: String): String =
        URLEncoder.encode(project, StandardCharsets.UTF_8).replace("+", "%20")

    private fun wikiUrl(
        project: String,
        slug: String,
    ): String =
        "${config.apiBaseUrl.substringBefore("/api/")}/$project/-/wikis/" +
            URLEncoder.encode(slug, StandardCharsets.UTF_8).replace("+", "%20")

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_ERROR_BODY_LENGTH = 2_000
    }
}

@Serializable
private data class GitLabRecord(
    val id: Long,
    val title: String,
    val description: String? = null,
    val state: String? = null,
    @SerialName("web_url") val webUrl: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    val author: GitLabAuthor? = null,
    val labels: List<String>? = null,
)

@Serializable
private data class GitLabWikiPage(
    val slug: String,
    val title: String,
    val content: String,
    @SerialName("web_url") val webUrl: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
private data class GitLabAuthor(val username: String? = null)
