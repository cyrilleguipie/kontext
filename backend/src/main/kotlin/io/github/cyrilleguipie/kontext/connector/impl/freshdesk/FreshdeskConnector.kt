package io.github.cyrilleguipie.kontext.connector.impl.freshdesk

import io.github.cyrilleguipie.kontext.config.FreshdeskConfig
import io.github.cyrilleguipie.kontext.connector.spi.RawDocument
import io.github.cyrilleguipie.kontext.connector.spi.SourceConnector
import io.github.cyrilleguipie.kontext.domain.DocumentSource
import io.github.cyrilleguipie.kontext.domain.KontextError
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.time.Instant

class FreshdeskConnector(
    private val config: FreshdeskConfig,
    private val httpClient: HttpClient,
) : SourceConnector {
    override val connectorId: String = "freshdesk"
    override val source: DocumentSource = DocumentSource.FRESHDESK
    override val allowEmptySnapshotCleanup: Boolean = true

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> =
        flow {
            val visitedFolderIds = mutableSetOf<Long>()
            fetchPages<FreshdeskCategory>("/solutions/categories").collect { category ->
                fetchPages<FreshdeskFolder>("/solutions/categories/${category.id}/folders")
                    .collect { folder ->
                        if (folder.isPublic() && folder.parentFolderId == null) {
                            emitPublicFolderDocuments(folder, visitedFolderIds)
                        }
                    }
            }
        }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<RawDocument>.emitPublicFolderDocuments(
        folder: FreshdeskFolder,
        visitedFolderIds: MutableSet<Long>,
        depth: Int = 0,
    ) {
        if (depth > MAX_FOLDER_DEPTH) failFreshdesk(KontextError.FreshdeskError.Pagination)
        if (!visitedFolderIds.add(folder.id)) return
        fetchPages<FreshdeskArticle>("/solutions/folders/${folder.id}/articles").collect { article ->
            if (article.isPublished()) emit(article.toRawDocument(config))
        }
        fetchPages<FreshdeskFolder>("/solutions/folders/${folder.id}/subfolders")
            .collect { subfolder ->
                if (subfolder.isPublic()) emitPublicFolderDocuments(subfolder, visitedFolderIds, depth + 1)
            }
    }

    private inline fun <reified T> fetchPages(path: String): Flow<T> =
        flow {
            var page = 1
            while (page <= MAX_PAGES) {
                val response =
                    httpClient.get("${config.baseUrl}/api/v2$path") {
                        basicAuth(config.apiKey, "X")
                        parameter("page", page)
                        parameter("per_page", PAGE_SIZE)
                    }
                when (response.status) {
                    HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                        failFreshdesk(KontextError.FreshdeskError.Authentication)
                    HttpStatusCode.TooManyRequests ->
                        failFreshdesk(
                            KontextError.FreshdeskError.RateLimited(response.headers[HttpHeaders.RetryAfter]),
                        )
                    else -> {
                        if (response.status.value !in 200..299) {
                            val detail = response.bodyAsText().take(MAX_ERROR_BODY_LENGTH)
                            failFreshdesk(
                                KontextError.FreshdeskError.HttpFailure(response.status.value, detail),
                            )
                        }
                    }
                }
                val items: List<T> = response.body()
                items.forEach { emit(it) }
                if (items.size < PAGE_SIZE) return@flow
                page++
            }
            failFreshdesk(KontextError.FreshdeskError.Pagination)
        }

    private fun failFreshdesk(error: KontextError.FreshdeskError): Nothing = throw KontextError.Exception(error)

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 10_000
        const val MAX_FOLDER_DEPTH = 30
        const val MAX_ERROR_BODY_LENGTH = 2_000
    }
}

private const val PUBLIC_FOLDER_VISIBILITY = 1
private const val PUBLISHED_STATUS = 2
private const val MAX_DOCUMENT_LENGTH = 250_000
private const val MAX_TITLE_LENGTH = 500

private fun FreshdeskFolder.isPublic(): Boolean = visibility == PUBLIC_FOLDER_VISIBILITY

private fun FreshdeskArticle.isPublished(): Boolean = status == PUBLISHED_STATUS

private fun FreshdeskArticle.toRawDocument(config: FreshdeskConfig): RawDocument {
    val content = descriptionText?.takeIf(String::isNotBlank) ?: Jsoup.parse(description.orEmpty()).text()
    return RawDocument(
        externalId = "article:$id",
        title = title.take(MAX_TITLE_LENGTH),
        body = "# $title\n\n${content.take(MAX_DOCUMENT_LENGTH)}",
        url = "${config.baseUrl}/support/solutions/articles/$id",
        metadata =
            buildMap {
                put("kind", "help_center_article")
                categoryId?.let { put("categoryId", it.toString()) }
                folderId?.let { put("folderId", it.toString()) }
                tags.takeIf(List<String>::isNotEmpty)?.let { put("tags", it.joinToString(",")) }
            },
        updatedAt = runCatching { Instant.parse(updatedAt) }.getOrElse { Instant.EPOCH },
    )
}

@Serializable
private data class FreshdeskCategory(val id: Long)

@Serializable
private data class FreshdeskFolder(
    val id: Long,
    val visibility: Int,
    @SerialName("parent_folder_id") val parentFolderId: Long? = null,
)

@Serializable
private data class FreshdeskArticle(
    val id: Long,
    val title: String,
    val description: String? = null,
    @SerialName("description_text") val descriptionText: String? = null,
    val status: Int,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("folder_id") val folderId: Long? = null,
    val tags: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: String,
)
