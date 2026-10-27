package io.github.cyrilleguipie.kontext.connector.impl.zendesk

import io.github.cyrilleguipie.kontext.config.ZendeskConfig
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
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

class ZendeskConnector(
    private val config: ZendeskConfig,
    private val httpClient: HttpClient,
) : SourceConnector {
    override val connectorId: String = "zendesk"
    override val source: DocumentSource = DocumentSource.ZENDESK
    override val allowEmptySnapshotCleanup: Boolean = true

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> =
        flow {
            var requestUrl = articlesEndpoint()
            val visitedUrls = mutableSetOf<String>()
            var pageNumber = 0
            while (true) {
                pageNumber++
                if (pageNumber > MAX_PAGES) failZendesk(KontextError.ZendeskError.Pagination)
                if (!visitedUrls.add(requestUrl)) failZendesk(KontextError.ZendeskError.Pagination)

                val response =
                    httpClient.get(requestUrl) {
                        header(HttpHeaders.Authorization, basicAuthorization())
                        if (pageNumber == 1) parameter("page[size]", PAGE_SIZE)
                    }.requireSuccess()
                val page: ZendeskArticlePage = response.body()
                page.articles.filter { it.isPublicPublished() }.forEach { emit(it.toRawDocument()) }

                if (!page.meta.hasMore) break
                requestUrl = nextPageUrl(page) ?: failZendesk(KontextError.ZendeskError.Pagination)
            }
        }

    private fun ZendeskArticle.isPublicPublished(): Boolean =
        !draft && userSegmentId == null && userSegmentIds.orEmpty().isEmpty()

    private fun ZendeskArticle.toRawDocument(): RawDocument {
        val labels = labelNames.orEmpty().filter(String::isNotBlank)
        val plainText = Jsoup.parse(body.orEmpty()).text().take(MAX_DOCUMENT_LENGTH)
        return RawDocument(
            externalId = "article:$id:$locale",
            title = title.take(MAX_TITLE_LENGTH),
            body =
                buildString {
                    append("# $title\n\n")
                    append("Locale: $locale\n")
                    sectionId?.let { append("Section: $it\n") }
                    if (labels.isNotEmpty()) append("Labels: ${labels.joinToString(", ")}\n")
                    append("\n")
                    append(plainText)
                }.take(MAX_DOCUMENT_LENGTH),
            url = htmlUrl?.takeIf(String::isNotBlank) ?: url,
            metadata =
                buildMap {
                    put("kind", "help_center_article")
                    put("locale", locale)
                    sectionId?.let { put("sectionId", it.toString()) }
                    authorId?.let { put("authorId", it.toString()) }
                    if (labels.isNotEmpty()) put("labels", labels.joinToString(", "))
                },
            updatedAt = parseInstant(updatedAt),
        )
    }

    private fun nextPageUrl(page: ZendeskArticlePage): String? =
        page.links.next
            ?.takeIf(String::isNotBlank)
            ?.also(::requireTrustedPaginationUrl)
            ?: page.meta.afterCursor
                ?.takeIf(String::isNotBlank)
                ?.let(::cursorUrl)

    private fun requireTrustedPaginationUrl(nextUrl: String) {
        val baseUri = URI(config.baseUrl)
        val nextUri =
            runCatching { URI(nextUrl) }.getOrNull()
                ?: failZendesk(KontextError.ZendeskError.Pagination)
        val sameOrigin =
            nextUri.scheme.equals(baseUri.scheme, ignoreCase = true) &&
                nextUri.host.equals(baseUri.host, ignoreCase = true) &&
                effectivePort(nextUri) == effectivePort(baseUri)
        val validPath = nextUri.rawPath.startsWith(ARTICLES_PATH)
        if (!sameOrigin || !validPath || !nextUri.rawUserInfo.isNullOrBlank()) {
            failZendesk(KontextError.ZendeskError.Pagination)
        }
    }

    private fun cursorUrl(cursor: String): String =
        "${articlesEndpoint()}?page%5Bsize%5D=$PAGE_SIZE&page%5Bafter%5D=" +
            URLEncoder.encode(cursor, StandardCharsets.UTF_8)

    private fun articlesEndpoint(): String = "${config.baseUrl}$ARTICLES_PATH"

    private fun basicAuthorization(): String {
        val credentials = "${config.email}/token:${config.apiToken}"
        val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
        return "Basic $encoded"
    }

    private suspend fun io.ktor.client.statement.HttpResponse.requireSuccess(): io.ktor.client.statement.HttpResponse {
        when {
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
                failZendesk(KontextError.ZendeskError.Authentication)
            status == HttpStatusCode.TooManyRequests ->
                failZendesk(
                    KontextError.ZendeskError.RateLimited(
                        headers[HttpHeaders.RetryAfter],
                    ),
                )
            status.value !in 200..299 -> {
                val detail = bodyAsText().take(MAX_ERROR_BODY_LENGTH)
                failZendesk(KontextError.ZendeskError.HttpFailure(status.value, detail))
            }
        }
        return this
    }

    private fun parseInstant(value: String?): Instant =
        value?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH

    private fun effectivePort(uri: URI): Int =
        uri.port.takeIf { it >= 0 } ?: if (uri.scheme.equals("https", true)) 443 else 80

    private fun failZendesk(error: KontextError.ZendeskError): Nothing = throw KontextError.Exception(error)

    private companion object {
        const val ARTICLES_PATH = "/api/v2/help_center/articles.json"
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 10_000
        const val MAX_DOCUMENT_LENGTH = 250_000
        const val MAX_TITLE_LENGTH = 500
        const val MAX_ERROR_BODY_LENGTH = 2_000
    }
}

@Serializable
private data class ZendeskArticlePage(
    val articles: List<ZendeskArticle> = emptyList(),
    val meta: ZendeskPageMeta,
    val links: ZendeskPageLinks = ZendeskPageLinks(),
)

@Serializable
private data class ZendeskPageMeta(
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("after_cursor") val afterCursor: String? = null,
)

@Serializable
private data class ZendeskPageLinks(val next: String? = null)

@Serializable
private data class ZendeskArticle(
    val id: Long,
    val title: String,
    val body: String? = null,
    val locale: String,
    val url: String,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("author_id") val authorId: Long? = null,
    @SerialName("section_id") val sectionId: Long? = null,
    @SerialName("label_names") val labelNames: List<String>? = null,
    val draft: Boolean = false,
    @SerialName("user_segment_id") val userSegmentId: Long? = null,
    @SerialName("user_segment_ids") val userSegmentIds: List<Long>? = null,
)
