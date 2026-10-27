package io.github.cyrilleguipie.kontext.connector.impl.github

import io.github.cyrilleguipie.kontext.config.GitHubConfig
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Base64

class GitHubConnector(
    private val config: GitHubConfig,
    private val httpClient: HttpClient,
) : SourceConnector {
    override val connectorId: String = "github"
    override val source: DocumentSource = DocumentSource.GITHUB
    override val allowEmptySnapshotCleanup: Boolean = false

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> =
        flow {
            for (repository in config.repositories) {
                val (owner, name) = repository.split('/')
                val repositoryUrl = "$API_PATH/repos/$owner/$name"
                fetchReadme(repository, repositoryUrl)?.let { emit(it) }
                var page = 1
                while (true) {
                    val response =
                        httpClient.get("${config.apiBaseUrl}$repositoryUrl/issues") {
                            githubHeaders()
                            parameter("state", "all")
                            parameter("sort", "updated")
                            parameter("direction", "desc")
                            parameter("per_page", PAGE_SIZE)
                            parameter("page", page)
                        }.requireSuccess("GitHub issues for $repository")
                    val issues: List<GitHubIssue> = response.body()
                    issues.forEach { emit(it.toRawDocument(repository)) }
                    if (issues.size < PAGE_SIZE) break
                    page++
                }
            }
        }

    private suspend fun fetchReadme(
        repository: String,
        repositoryUrl: String,
    ): RawDocument? {
        val response =
            httpClient.get("${config.apiBaseUrl}$repositoryUrl/readme") {
                githubHeaders()
            }
        return if (response.status == HttpStatusCode.NotFound) {
            null
        } else {
            val readme: GitHubContent = response.requireSuccess("GitHub README for $repository").body()
            decodeReadme(readme)?.let { markdown ->
                RawDocument(
                    externalId = "$repository:README",
                    title = "$repository README",
                    body = markdown,
                    url = readme.htmlUrl ?: "https://github.com/$repository/blob/HEAD/${readme.path}",
                    metadata = mapOf("repository" to repository, "kind" to "readme", "path" to readme.path),
                    updatedAt = readme.updatedAt?.let(::parseInstant) ?: Instant.EPOCH,
                )
            }
        }
    }

    private fun decodeReadme(readme: GitHubContent): String? =
        if (readme.encoding != "base64" || readme.content.isBlank()) {
            null
        } else {
            runCatching { Base64.getMimeDecoder().decode(readme.content).toString(Charsets.UTF_8).trim() }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
        }

    private fun GitHubIssue.toRawDocument(repository: String): RawDocument {
        val isPullRequest = pullRequest != null
        val issueKind = if (isPullRequest) "pull_request" else "issue"
        val labelsText = labels.map { it.name }.filter(String::isNotBlank)
        val text =
            buildString {
                append("# $title\n\n")
                append("Repository: $repository\n")
                append("Type: ${if (isPullRequest) "Pull request" else "Issue"}\n")
                append("State: $state\n")
                user?.login?.let { append("Author: $it\n") }
                if (labelsText.isNotEmpty()) append("Labels: ${labelsText.joinToString(", ")}\n")
                append("\n")
                body?.takeIf(String::isNotBlank)?.let(::append)
            }.trim()
        return RawDocument(
            externalId = "$repository#$number",
            title = title,
            body = text,
            url = htmlUrl,
            metadata =
                buildMap {
                    put("repository", repository)
                    put("kind", issueKind)
                    put("state", state)
                    user?.login?.let { put("author", it) }
                    if (labelsText.isNotEmpty()) put("labels", labelsText.joinToString(", "))
                },
            updatedAt = parseInstant(updatedAt),
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.githubHeaders() {
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header(HttpHeaders.Authorization, "Bearer ${config.token}")
        header("X-GitHub-Api-Version", GITHUB_API_VERSION)
    }

    private suspend fun io.ktor.client.statement.HttpResponse.requireSuccess(operation: String) =
        apply {
            if (status != HttpStatusCode.OK) {
                val body = bodyAsText().take(MAX_ERROR_BODY_LENGTH)
                error("$operation failed with HTTP ${status.value}: $body")
            }
        }

    private fun parseInstant(value: String): Instant = runCatching { Instant.parse(value) }.getOrElse { Instant.EPOCH }

    private companion object {
        const val API_PATH = "/repos"
        const val PAGE_SIZE = 100
        const val GITHUB_API_VERSION = "2022-11-28"
        const val MAX_ERROR_BODY_LENGTH = 2_000
    }
}

@Serializable
private data class GitHubIssue(
    val number: Long,
    val title: String,
    val body: String? = null,
    val state: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("updated_at") val updatedAt: String,
    val user: GitHubUser? = null,
    val labels: List<GitHubLabel> = emptyList(),
    @SerialName("pull_request") val pullRequest: GitHubPullRequest? = null,
)

@Serializable
private data class GitHubContent(
    val content: String = "",
    val encoding: String = "",
    val path: String = "README.md",
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
private data class GitHubUser(val login: String? = null)

@Serializable
private data class GitHubLabel(val name: String = "")

@Serializable
private data class GitHubPullRequest(val url: String? = null)
