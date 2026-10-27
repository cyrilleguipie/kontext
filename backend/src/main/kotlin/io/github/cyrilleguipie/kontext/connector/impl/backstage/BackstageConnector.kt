package io.github.cyrilleguipie.kontext.connector.impl.backstage

import io.github.cyrilleguipie.kontext.config.BackstageConfig
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
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class BackstageConnector(
    private val config: BackstageConfig,
    private val httpClient: HttpClient,
) : SourceConnector {
    override val connectorId: String = "backstage"
    override val source: DocumentSource = DocumentSource.BACKSTAGE

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> =
        flow {
            var cursor: String? = null
            val seenCursors = mutableSetOf<String>()
            var indexedTechDocsPages = 0
            repeat(MAX_PAGES) {
                val response =
                    get("${config.baseUrl}/api/catalog/entities/by-query") {
                        parameter("limit", PAGE_SIZE)
                        parameter("fields", ENTITY_FIELDS)
                        cursor?.let { parameter("cursor", it) }
                    }
                requireSuccess(response)
                val page: BackstageEntityPage = response.body()
                page.items.filter(::isAllowedNamespace).forEach { entity ->
                    emit(entity.toRawDocument())
                    if (config.techDocsEnabled) {
                        fetchTechDocsPages(entity) { document ->
                            indexedTechDocsPages++
                            if (indexedTechDocsPages > MAX_TECHDOCS_PAGES_PER_SYNC) {
                                failBackstage(KontextError.BackstageError.Pagination)
                            }
                            emit(document)
                        }
                    }
                }
                val nextCursor = page.pageInfo.nextCursor?.takeIf(String::isNotBlank) ?: return@flow
                if (!seenCursors.add(nextCursor)) failBackstage(KontextError.BackstageError.Pagination)
                cursor = nextCursor
            }
            failBackstage(KontextError.BackstageError.Pagination)
        }

    private suspend fun fetchTechDocsPages(
        entity: BackstageEntity,
        emitDocument: suspend (RawDocument) -> Unit,
    ) {
        val namespace = entity.metadata.namespace ?: DEFAULT_NAMESPACE
        val kind = entity.kind.lowercase()
        val name = entity.metadata.name
        val entityPath =
            listOf(namespace, kind, name).joinToString("/") { encodePathSegment(it) }
        val metadataResponse = get("${config.baseUrl}/api/techdocs/metadata/techdocs/$entityPath")
        if (metadataResponse.status == HttpStatusCode.NotFound) return
        requireSuccess(metadataResponse)
        val techDocs: BackstageTechDocsMetadata = metadataResponse.body()
        val files = (techDocs.files ?: listOf(INDEX_FILE)).filter(String::isHtmlFile).distinct()
        if (files.isEmpty() || files.size > MAX_TECHDOCS_PAGES_PER_ENTITY) {
            failBackstage(KontextError.BackstageError.TechDocsMetadata)
        }
        files.forEach { file ->
            val safePath = file.toSafeTechDocsPath() ?: failBackstage(KontextError.BackstageError.TechDocsMetadata)
            val encodedPath = safePath.split('/').joinToString("/") { encodePathSegment(it) }
            val pageResponse = get("${config.baseUrl}/api/techdocs/static/docs/$entityPath/$encodedPath")
            requireSuccess(pageResponse)
            val contentLength = pageResponse.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (contentLength == null || contentLength > MAX_TECHDOCS_PAGE_BYTES) {
                failBackstage(KontextError.BackstageError.TechDocsPageTooLarge(MAX_TECHDOCS_PAGE_BYTES))
            }
            if (pageResponse.headers[HttpHeaders.ContentType]?.startsWith(ContentType.Text.Html.toString()) != true) {
                failBackstage(KontextError.BackstageError.TechDocsMetadata)
            }
            emitDocument(entity.toTechDocsDocument(safePath, pageResponse.bodyAsText(), techDocs))
        }
    }

    private suspend fun get(
        url: String,
        configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ) = httpClient.get(url) {
        if (config.token.isNotBlank()) bearerAuth(config.token)
        header(HttpHeaders.Accept, "application/json, text/html")
        configure()
    }

    private suspend fun requireSuccess(response: io.ktor.client.statement.HttpResponse) {
        when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                failBackstage(KontextError.BackstageError.Authentication)
            response.status == HttpStatusCode.TooManyRequests ->
                failBackstage(KontextError.BackstageError.RateLimited(response.headers[HttpHeaders.RetryAfter]))
            response.status.value !in 200..299 -> {
                val detail = response.bodyAsText().take(MAX_ERROR_BODY_LENGTH)
                failBackstage(KontextError.BackstageError.HttpFailure(response.status.value, detail))
            }
        }
    }

    private fun isAllowedNamespace(entity: BackstageEntity): Boolean =
        config.namespaces.isEmpty() ||
            config.namespaces.any { it.equals(entity.metadata.namespace ?: DEFAULT_NAMESPACE, ignoreCase = true) }

    private fun BackstageEntity.toRawDocument(): RawDocument {
        val namespace = metadata.namespace ?: DEFAULT_NAMESPACE
        val normalizedKind = kind.lowercase()
        val content = toSearchableText(namespace)
        return RawDocument(
            externalId = "$normalizedKind:$namespace/${metadata.name}".lowercase(),
            title = (metadata.title?.takeIf(String::isNotBlank) ?: metadata.name).take(MAX_TITLE_LENGTH),
            body = content.take(MAX_DOCUMENT_LENGTH),
            url =
                "${config.baseUrl}/catalog/${encodePathSegment(namespace)}/" +
                    "${encodePathSegment(normalizedKind)}/${encodePathSegment(metadata.name)}",
            metadata =
                buildMap {
                    put("kind", kind)
                    put("namespace", namespace)
                    put("name", metadata.name)
                    metadata.tags.take(MAX_TAGS).takeIf(List<String>::isNotEmpty)?.let {
                        put("tags", it.joinToString(",") { tag -> tag.take(MAX_METADATA_VALUE_LENGTH) })
                    }
                    spec.stringValue("owner")?.let { put("owner", it.take(MAX_METADATA_VALUE_LENGTH)) }
                    spec.stringValue("system")?.let { put("system", it.take(MAX_METADATA_VALUE_LENGTH)) }
                    spec.stringValue("lifecycle")?.let { put("lifecycle", it.take(MAX_METADATA_VALUE_LENGTH)) }
                },
            updatedAt = Instant.EPOCH,
        )
    }

    private fun BackstageEntity.toSearchableText(namespace: String): String =
        buildString {
            appendLine("# ${metadata.title?.takeIf(String::isNotBlank) ?: metadata.name}")
            appendLine("Kind: $kind")
            appendLine("Namespace: $namespace")
            metadata.description?.takeIf(String::isNotBlank)?.let {
                appendLine("Description: ${it.take(MAX_FIELD_LENGTH)}")
            }
            metadata.tags.take(MAX_TAGS).takeIf(List<String>::isNotEmpty)?.let { tags ->
                appendLine("Tags: ${tags.joinToString(", ") { it.take(MAX_METADATA_VALUE_LENGTH) }}")
            }
            listOf("type", "lifecycle", "owner", "system", "domain", "description")
                .forEach { key ->
                    val value = spec.stringValue(key)
                    value?.let { appendLine("${key.replaceFirstChar(Char::uppercase)}: ${it.take(MAX_FIELD_LENGTH)}") }
                }
            listOf("dependsOn", "providesApis", "consumesApis")
                .forEach { key ->
                    spec.stringListValue(key)?.take(MAX_RELATIONS)?.let { values ->
                        appendLine("$key: ${values.joinToString(", ") { it.take(MAX_METADATA_VALUE_LENGTH) }}")
                    }
                }
            relations.take(MAX_RELATIONS).forEach { relation ->
                appendLine("${relation.type.take(128)}: ${relation.targetRef.take(MAX_METADATA_VALUE_LENGTH)}")
            }
            metadata.links.take(MAX_LINKS).forEach { link ->
                appendLine(
                    "${link.title?.takeIf(String::isNotBlank)?.take(MAX_METADATA_VALUE_LENGTH) ?: "Link"}: " +
                        link.url.take(MAX_METADATA_VALUE_LENGTH),
                )
            }
        }.let(Jsoup::parse).text()

    private fun BackstageEntity.toTechDocsDocument(
        path: String,
        html: String,
        techDocs: BackstageTechDocsMetadata,
    ): RawDocument {
        val namespace = metadata.namespace ?: DEFAULT_NAMESPACE
        val normalizedKind = kind.lowercase()
        val parsed = Jsoup.parse(html)
        val pageTitle = parsed.title().ifBlank { parsed.selectFirst("h1")?.text().orEmpty() }.ifBlank { path }
        val body = parsed.toTechDocsText().take(MAX_DOCUMENT_LENGTH)
        val entityTitle = metadata.title?.takeIf(String::isNotBlank) ?: metadata.name
        return RawDocument(
            externalId = "techdocs:$normalizedKind:$namespace/${metadata.name}/$path",
            title = "$entityTitle — $pageTitle".take(MAX_TITLE_LENGTH),
            body =
                "# $pageTitle\n\nCatalog entity: $normalizedKind:$namespace/${metadata.name}\n" +
                    "Site: ${techDocs.siteName.orEmpty()}\n\n$body",
            url =
                "${config.baseUrl}/docs/${encodePathSegment(namespace)}/" +
                    "${encodePathSegment(normalizedKind)}/${encodePathSegment(metadata.name)}",
            metadata =
                mapOf(
                    "kind" to "techdocs_page",
                    "namespace" to namespace,
                    "entityKind" to kind,
                    "entityName" to metadata.name,
                    "path" to path,
                ),
            updatedAt = techDocs.buildTimestamp?.let(::techDocsInstant) ?: Instant.EPOCH,
        )
    }

    private fun failBackstage(error: KontextError.BackstageError): Nothing = throw KontextError.Exception(error)

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 10_000
        const val MAX_ERROR_BODY_LENGTH = 2_000
        const val MAX_DOCUMENT_LENGTH = 250_000
        const val MAX_TITLE_LENGTH = 500
        const val MAX_LINKS = 20
        const val MAX_TAGS = 100
        const val MAX_RELATIONS = 100
        const val MAX_FIELD_LENGTH = 8_000
        const val MAX_METADATA_VALUE_LENGTH = 2_048
        const val DEFAULT_NAMESPACE = "default"
        const val INDEX_FILE = "index.html"
        const val MAX_TECHDOCS_PAGES_PER_ENTITY = 500
        const val MAX_TECHDOCS_PAGES_PER_SYNC = 10_000
        const val MAX_TECHDOCS_PAGE_BYTES = 2_000_000
        const val ENTITY_FIELDS =
            "kind,metadata.name,metadata.namespace,metadata.title,metadata.description,metadata.tags,metadata.links," +
                "spec.type,spec.lifecycle,spec.owner,spec.system,spec.domain,spec.description,spec.dependsOn," +
                "spec.providesApis,spec.consumesApis,relations.type,relations.targetRef"
    }
}

private fun String.isHtmlFile(): Boolean = endsWith(".html", ignoreCase = true)

private fun String.toSafeTechDocsPath(): String? {
    if (isBlank() || startsWith('/') || contains('\\')) return null
    val segments = split('/')
    return takeIf { segments.all { segment -> segment.isNotBlank() && segment !in setOf(".", "..") } }
}

private fun Document.toTechDocsText(): String {
    select("script, style, nav, header, footer, noscript").remove()
    return selectFirst(".md-content__inner, article, main, body")?.text().orEmpty()
}

private fun techDocsInstant(epochMillis: Long): Instant =
    runCatching { Instant.ofEpochMilli(epochMillis) }.getOrDefault(Instant.EPOCH)

private fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

private fun JsonObject.stringListValue(key: String): List<String>? =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { element ->
        (element as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }?.takeIf(List<String>::isNotEmpty)

@Serializable
private data class BackstageEntityPage(
    val items: List<BackstageEntity>,
    @SerialName("pageInfo") val pageInfo: BackstagePageInfo,
)

@Serializable
private data class BackstagePageInfo(
    val nextCursor: String? = null,
)

@Serializable
private data class BackstageTechDocsMetadata(
    @SerialName("site_name") val siteName: String? = null,
    @SerialName("build_timestamp") val buildTimestamp: Long? = null,
    val files: List<String>? = null,
)

@Serializable
private data class BackstageEntity(
    val kind: String,
    val metadata: BackstageMetadata,
    val spec: JsonObject = JsonObject(emptyMap()),
    val relations: List<BackstageRelation> = emptyList(),
)

@Serializable
private data class BackstageMetadata(
    val name: String,
    val namespace: String? = null,
    val title: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val links: List<BackstageLink> = emptyList(),
)

@Serializable
private data class BackstageRelation(
    val type: String,
    val targetRef: String,
)

@Serializable
private data class BackstageLink(
    val url: String,
    val title: String? = null,
)
