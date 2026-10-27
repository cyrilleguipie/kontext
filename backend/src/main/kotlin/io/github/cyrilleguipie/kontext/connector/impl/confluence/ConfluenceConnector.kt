package io.github.cyrilleguipie.kontext.connector.impl.confluence

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
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.OffsetDateTime

class ConfluenceConnector(
    private val config: AtlassianConfig,
    private val httpClient: HttpClient,
) : SourceConnector {
    override val connectorId: String = "confluence"
    override val source: DocumentSource = DocumentSource.CONFLUENCE
    override val allowEmptySnapshotCleanup: Boolean = true

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> =
        if (config.flavor == AtlassianFlavor.CLOUD) fetchCloudDocuments() else fetchDataCenterDocuments()

    private fun fetchCloudDocuments(): Flow<RawDocument> =
        flow {
            for (space in fetchCloudSpaces()) {
                var cursor: String? = null
                val seenCursors = mutableSetOf<String>()
                var hasNext = true
                while (hasNext) {
                    val page = fetchCloudPages(space.id, cursor)
                    page.results.forEach { emit(it.toRawDocument(space.key, config.confluence.baseUrl)) }
                    val next = page.links?.next?.let(::cursorFromNextLink)
                    hasNext = next != null
                    next?.let {
                        check(seenCursors.add(it)) { "Confluence returned a repeated pagination cursor" }
                        cursor = it
                    }
                }
            }
        }

    private fun fetchDataCenterDocuments(): Flow<RawDocument> =
        flow {
            var start = 0
            var hasNext = true
            while (hasNext) {
                val page = fetchDataCenterPages(start)
                page.results.forEach { emit(it.toRawDocument(config.confluence.baseUrl)) }
                start += page.size ?: page.results.size
                hasNext = page.results.isNotEmpty() && page.results.size >= (page.limit ?: PAGE_SIZE)
            }
        }

    private suspend fun fetchCloudSpaces(): List<CloudSpace> {
        val spaces = mutableListOf<CloudSpace>()
        val keys = config.confluence.spaces
        val requestedKeys: List<String?> = if (keys.isEmpty()) listOf(null) else keys.map { it }
        for (key in requestedKeys) {
            var cursor: String? = null
            val seenCursors = mutableSetOf<String>()
            while (true) {
                val response =
                    httpClient.get("${config.confluence.baseUrl}/api/v2/spaces") {
                        applyAuth()
                        parameter("limit", PAGE_SIZE)
                        key?.let { parameter("keys", it) }
                        cursor?.let { parameter("cursor", it) }
                    }.requireSuccess("Confluence spaces")
                val page: CloudSpacePage = response.body()
                spaces += page.results
                val next = page.links?.next?.let(::cursorFromNextLink) ?: break
                check(seenCursors.add(next)) { "Confluence returned a repeated space cursor" }
                cursor = next
            }
        }
        return spaces.distinctBy { it.id }
    }

    private suspend fun fetchCloudPages(
        spaceId: String,
        cursor: String?,
    ): CloudPage {
        val response =
            httpClient.get("${config.confluence.baseUrl}/api/v2/spaces/$spaceId/pages") {
                applyAuth()
                parameter("limit", PAGE_SIZE)
                parameter("body-format", "storage")
                cursor?.let { parameter("cursor", it) }
            }.requireSuccess("Confluence pages")
        return response.body()
    }

    private suspend fun fetchDataCenterPages(start: Int): DataCenterPage {
        val response =
            httpClient.get("${config.confluence.baseUrl}/rest/api/content") {
                applyAuth()
                parameter("type", "page")
                parameter("limit", PAGE_SIZE)
                parameter("start", start)
                parameter("expand", "body.storage,space,version")
                config.confluence.spaces.forEach { parameter("spaceKey", it) }
            }.requireSuccess("Confluence Data Center pages")
        return response.body()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth() {
        header(HttpHeaders.Accept, "application/json")
        val authorization =
            if (config.flavor == AtlassianFlavor.CLOUD) {
                val credential = "${config.auth.email}:${config.auth.token}"
                "Basic ${java.util.Base64.getEncoder().encodeToString(credential.toByteArray(Charsets.UTF_8))}"
            } else {
                "Bearer ${config.auth.token}"
            }
        header(HttpHeaders.Authorization, authorization)
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

    private fun cursorFromNextLink(link: String): String? {
        val query = runCatching { URI(link).rawQuery }.getOrNull() ?: return null
        return query.split('&')
            .firstOrNull { it.substringBefore('=') == "cursor" }
            ?.substringAfter('=', "")
            ?.takeIf(String::isNotBlank)
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
    }

    private fun CloudPageDocument.toRawDocument(
        spaceKey: String,
        baseUrl: String,
    ): RawDocument {
        val storage = body?.storage?.value.orEmpty()
        val content = Jsoup.parse(storage).text()
        val webUi =
            links?.webui?.let { URI(baseUrl).resolve(it).toString() }
                ?: "$baseUrl/spaces/$spaceKey/pages/$id"
        return RawDocument(
            externalId = id,
            title = title,
            body = content.ifBlank { title },
            url = webUi,
            metadata = mapOf("space" to spaceKey),
            updatedAt = version?.createdAt.toInstantOrEpoch(),
        )
    }

    private fun DataCenterPageDocument.toRawDocument(baseUrl: String): RawDocument {
        val content = Jsoup.parse(body?.storage?.value.orEmpty()).text()
        val spaceKey = space?.key.orEmpty()
        val webUi =
            links?.webui?.let { URI(baseUrl).resolve(it).toString() }
                ?: "$baseUrl/display/$spaceKey/$id"
        return RawDocument(
            externalId = id,
            title = title,
            body = content.ifBlank { title },
            url = webUi,
            metadata =
                buildMap {
                    if (spaceKey.isNotBlank()) put("space", spaceKey)
                },
            updatedAt = version?.whenUpdated.toInstantOrEpoch(),
        )
    }

    private fun String?.toInstantOrEpoch(): Instant {
        if (this == null) return Instant.EPOCH
        return runCatching { OffsetDateTime.parse(this).toInstant() }.getOrElse { Instant.EPOCH }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_ERROR_BODY_LENGTH = 2_000
    }
}

@Serializable
private data class CloudSpacePage(
    val results: List<CloudSpace> = emptyList(),
    @kotlinx.serialization.SerialName("_links")
    val links: PaginationLinks? = null,
)

@Serializable
private data class CloudSpace(
    val id: String,
    val key: String,
)

@Serializable
private data class CloudPage(
    val results: List<CloudPageDocument> = emptyList(),
    @kotlinx.serialization.SerialName("_links")
    val links: PaginationLinks? = null,
)

@Serializable
private data class CloudPageDocument(
    val id: String,
    val title: String,
    val body: CloudPageBody? = null,
    val version: CloudPageVersion? = null,
    @kotlinx.serialization.SerialName("_links")
    val links: WebLinks? = null,
)

@Serializable
private data class CloudPageBody(
    val storage: StorageValue? = null,
)

@Serializable
private data class StorageValue(
    val value: String? = null,
)

@Serializable
private data class CloudPageVersion(
    val createdAt: String? = null,
)

@Serializable
private data class PaginationLinks(
    val next: String? = null,
)

@Serializable
private data class WebLinks(
    val webui: String? = null,
)

@Serializable
private data class DataCenterPage(
    val results: List<DataCenterPageDocument> = emptyList(),
    val start: Int? = null,
    val limit: Int? = null,
    val size: Int? = null,
)

@Serializable
private data class DataCenterPageDocument(
    val id: String,
    val title: String,
    val body: DataCenterPageBody? = null,
    val space: DataCenterSpace? = null,
    val version: DataCenterVersion? = null,
    @kotlinx.serialization.SerialName("_links")
    val links: WebLinks? = null,
)

@Serializable
private data class DataCenterPageBody(
    val storage: StorageValue? = null,
)

@Serializable
private data class DataCenterSpace(
    val key: String? = null,
)

@Serializable
private data class DataCenterVersion(
    @kotlinx.serialization.SerialName("when")
    val whenUpdated: String? = null,
)
