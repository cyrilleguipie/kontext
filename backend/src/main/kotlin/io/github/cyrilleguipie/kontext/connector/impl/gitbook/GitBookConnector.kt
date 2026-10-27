package io.github.cyrilleguipie.kontext.connector.impl.gitbook

import io.github.cyrilleguipie.kontext.config.GitBookConfig
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
import java.time.Instant

class GitBookConnector(
    private val config: GitBookConfig,
    private val httpClient: HttpClient,
) : SourceConnector {
    override val connectorId: String = "gitbook"
    override val source: DocumentSource = DocumentSource.GITBOOK
    override val allowEmptySnapshotCleanup: Boolean = true

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> =
        flow {
            val spaces = if (config.spaces.isEmpty()) fetchOrganizationSpaces() else config.spaces
            for (spaceId in spaces.distinct()) {
                val response =
                    httpClient.get("$API_BASE/spaces/$spaceId/content/pages") {
                        gitBookHeaders()
                        parameter("metadata", false)
                        parameter("computed", false)
                    }.requireSuccess("GitBook pages for space $spaceId")
                val pageCollection: GitBookPageCollection = response.body()
                pageCollection.pages.forEach { page ->
                    emit(page.toRawDocument(spaceId))
                }
            }
        }

    private suspend fun fetchOrganizationSpaces(): List<String> {
        var cursor: String? = null
        val spaceIds = mutableListOf<String>()
        val seenCursors = mutableSetOf<String>()
        while (true) {
            val response =
                httpClient.get("$API_BASE/orgs/${config.organizationId}/spaces") {
                    gitBookHeaders()
                    parameter("limit", PAGE_SIZE)
                    cursor?.let { parameter("page", it) }
                }.requireSuccess("GitBook spaces")
            val page: GitBookSpaceCollection = response.body()
            spaceIds += page.items.map { it.id }
            val next = page.next?.page ?: break
            check(seenCursors.add(next)) { "GitBook returned a repeated pagination cursor" }
            cursor = next
        }
        return spaceIds
    }

    private fun GitBookPage.toRawDocument(spaceId: String): RawDocument =
        RawDocument(
            externalId = "$spaceId:$id",
            title = title,
            body = markdown.ifBlank { title },
            url = urls?.app ?: urls?.location ?: "https://app.gitbook.com/o/${config.organizationId}/s/$spaceId",
            metadata =
                buildMap {
                    put("space", spaceId)
                    path?.takeIf(String::isNotBlank)?.let { put("path", it) }
                    type?.takeIf(String::isNotBlank)?.let { put("type", it) }
                },
            updatedAt = updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH,
        )

    private fun io.ktor.client.request.HttpRequestBuilder.gitBookHeaders() {
        header(HttpHeaders.Accept, "application/json")
        header(HttpHeaders.Authorization, "Bearer ${config.token}")
    }

    private suspend fun io.ktor.client.statement.HttpResponse.requireSuccess(
        operation: String,
    ): io.ktor.client.statement.HttpResponse {
        if (status != HttpStatusCode.OK) {
            val body = bodyAsText().take(MAX_ERROR_BODY_LENGTH)
            error("$operation failed with HTTP ${status.value}: $body")
        }
        return this
    }

    private companion object {
        const val API_BASE = "https://api.gitbook.com/v1"
        const val PAGE_SIZE = 1_000
        const val MAX_ERROR_BODY_LENGTH = 2_000
    }
}

@Serializable
private data class GitBookSpaceCollection(
    val items: List<GitBookSpace> = emptyList(),
    val next: GitBookNext? = null,
)

@Serializable
private data class GitBookSpace(
    val id: String,
)

@Serializable
private data class GitBookNext(
    val page: String? = null,
)

@Serializable
private data class GitBookPageCollection(
    val pages: List<GitBookPage> = emptyList(),
)

@Serializable
private data class GitBookPage(
    val id: String,
    val title: String,
    val markdown: String = "",
    val path: String? = null,
    val type: String? = null,
    val updatedAt: String? = null,
    val urls: GitBookPageUrls? = null,
)

@Serializable
private data class GitBookPageUrls(
    val app: String? = null,
    val location: String? = null,
)
