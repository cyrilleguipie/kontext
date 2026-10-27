package io.github.cyrilleguipie.kontext.connector.impl.intercom

import io.github.cyrilleguipie.kontext.config.IntercomConfig
import io.github.cyrilleguipie.kontext.connector.spi.RawDocument
import io.github.cyrilleguipie.kontext.connector.spi.SourceConnector
import io.github.cyrilleguipie.kontext.domain.DocumentSource
import io.github.cyrilleguipie.kontext.domain.KontextError
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
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
import org.jsoup.Jsoup
import java.time.Instant

class IntercomConnector(
    private val config: IntercomConfig,
    private val httpClient: HttpClient,
) : SourceConnector {
    override val connectorId: String = "intercom"
    override val source: DocumentSource = DocumentSource.INTERCOM
    override val allowEmptySnapshotCleanup: Boolean = true

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> =
        flow {
            var pageNumber = 1
            var totalPages = 1
            while (pageNumber <= totalPages) {
                if (pageNumber > MAX_PAGES) failIntercom(KontextError.IntercomError.Pagination)
                val page = fetchArticles(pageNumber)
                totalPages = maxOf(totalPages, page.pages.totalPages)
                if (page.pages.page != pageNumber) failIntercom(KontextError.IntercomError.Pagination)
                page.data.filter { it.isPublishedAndPublic() }.forEach { emit(it.toRawDocument()) }
                pageNumber++
            }
        }

    private suspend fun fetchArticles(page: Int): IntercomArticlePage {
        val response =
            httpClient.get("${config.apiBaseUrl}/articles") {
                bearerAuth(config.accessToken)
                header(INTERCOM_VERSION_HEADER, config.apiVersion)
                parameter("page", page)
                parameter("per_page", PAGE_SIZE)
            }
        when (response.status) {
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                failIntercom(KontextError.IntercomError.Authentication)
            HttpStatusCode.TooManyRequests ->
                failIntercom(
                    KontextError.IntercomError.RateLimited(
                        response.headers[HttpHeaders.RetryAfter],
                    ),
                )
            else -> {
                if (response.status.value !in 200..299) {
                    val detail = response.bodyAsText().take(MAX_ERROR_BODY_LENGTH)
                    failIntercom(KontextError.IntercomError.HttpFailure(response.status.value, detail))
                }
            }
        }
        return response.body()
    }

    private fun IntercomArticle.isPublishedAndPublic(): Boolean =
        state == PUBLISHED_STATE && helpCenterAudience == PUBLIC_AUDIENCE

    private fun IntercomArticle.toRawDocument(): RawDocument {
        val plainText =
            bodyMarkdown?.takeIf(String::isNotBlank)
                ?: Jsoup.parse(body.orEmpty()).text()
        val articleContent =
            buildString {
                append("# $title\n\n")
                description?.takeIf(String::isNotBlank)?.let { append("$it\n\n") }
                append(plainText)
            }.take(MAX_DOCUMENT_LENGTH)
        return RawDocument(
            externalId = "article:$id",
            title = title.take(MAX_TITLE_LENGTH),
            body = articleContent,
            url = url?.takeIf(String::isNotBlank) ?: "${config.apiBaseUrl}/articles/$id",
            metadata =
                buildMap {
                    put("kind", "help_center_article")
                    put("audience", helpCenterAudience.orEmpty())
                    authorId?.let { put("authorId", it.toString()) }
                    if (parentIds.isNotEmpty()) put("parentIds", parentIds.joinToString(","))
                },
            updatedAt = parseInstant(updatedAt),
        )
    }

    private fun parseInstant(epochSeconds: Long): Instant =
        runCatching { Instant.ofEpochSecond(epochSeconds) }.getOrElse { Instant.EPOCH }

    private fun failIntercom(error: KontextError.IntercomError): Nothing = throw KontextError.Exception(error)

    private companion object {
        const val INTERCOM_VERSION_HEADER = "Intercom-Version"
        const val PAGE_SIZE = 250
        const val MAX_PAGES = 10_000
        const val MAX_DOCUMENT_LENGTH = 250_000
        const val MAX_TITLE_LENGTH = 500
        const val MAX_ERROR_BODY_LENGTH = 2_000
        const val PUBLISHED_STATE = "published"
        const val PUBLIC_AUDIENCE = "everyone"
    }
}

@Serializable
private data class IntercomArticlePage(
    val data: List<IntercomArticle> = emptyList(),
    val pages: IntercomPages,
)

@Serializable
private data class IntercomPages(
    val page: Int,
    @SerialName("total_pages") val totalPages: Int,
)

@Serializable
private data class IntercomArticle(
    val id: String,
    val title: String,
    val description: String? = null,
    val body: String? = null,
    @SerialName("body_markdown") val bodyMarkdown: String? = null,
    val state: String,
    val url: String? = null,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("author_id") val authorId: Long? = null,
    @SerialName("parent_ids") val parentIds: List<Long> = emptyList(),
    @SerialName("help_center_audience") val helpCenterAudience: String? = null,
)
