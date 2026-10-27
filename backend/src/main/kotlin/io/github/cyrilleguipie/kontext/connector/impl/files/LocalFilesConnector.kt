package io.github.cyrilleguipie.kontext.connector.impl.files

import io.github.cyrilleguipie.kontext.connector.spi.RawDocument
import io.github.cyrilleguipie.kontext.connector.spi.SourceConnector
import io.github.cyrilleguipie.kontext.domain.DocumentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

class LocalFilesConnector(directory: String) : SourceConnector {
    override val connectorId: String = "local-files"
    override val source: DocumentSource = DocumentSource.FILES
    private val root = Path.of(directory).toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Local files source path is not a directory: $root"
        }
    }

    override fun fetchDocuments(since: Instant?): Flow<RawDocument> =
        flow {
            val paths =
                withContext(Dispatchers.IO) {
                    Files.walk(root).use { walk ->
                        walk.filter { path ->
                            path != root &&
                                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                                path.fileName.toString().substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
                        }.sorted().toList()
                    }
                }
            for (path in paths) {
                val document = withContext(Dispatchers.IO) { readDocument(path) }
                if (document.body.isNotBlank() && (since == null || document.updatedAt.isAfter(since))) emit(document)
            }
        }

    private fun readDocument(path: Path): RawDocument {
        val updatedAt = Files.getLastModifiedTime(path).toInstant()
        val relativePath = root.relativize(path).toString().replace(path.fileSystem.separator, "/")
        val title = path.fileName.toString().substringBeforeLast('.')
        val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()
        val body = LocalDocumentReader.read(path, extension)
        return RawDocument(
            externalId = relativePath,
            title = title,
            body = body,
            url = "/api/sources/local-files?path=${URLEncoder.encode(relativePath, Charsets.UTF_8)}",
            metadata = mapOf("path" to relativePath, "filename" to path.fileName.toString()),
            updatedAt = updatedAt,
        )
    }

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("md", "markdown", "txt", "pdf", "docx")
    }
}
