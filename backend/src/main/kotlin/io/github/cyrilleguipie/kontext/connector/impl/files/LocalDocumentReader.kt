package io.github.cyrilleguipie.kontext.connector.impl.files

import org.apache.tika.exception.TikaException
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.xml.sax.SAXException
import java.nio.file.Files
import java.nio.file.Path

object LocalDocumentReader {
    const val MAX_DOCUMENT_LENGTH = 250_000
    const val MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024

    fun read(
        path: Path,
        extension: String,
    ): String {
        val size = Files.size(path)
        require(size <= MAX_FILE_SIZE_BYTES) {
            "Local document exceeds the $MAX_FILE_SIZE_BYTES byte file limit: $path"
        }
        if (extension in PLAIN_TEXT_EXTENSIONS) {
            return Files.readString(path, Charsets.UTF_8).also(::requireWithinTextLimit)
        }
        val handler = BodyContentHandler(MAX_DOCUMENT_LENGTH)
        val parser = AutoDetectParser()
        TikaInputStream.get(path).use { input ->
            try {
                parser.parse(input, handler, Metadata(), ParseContext())
            } catch (cause: SAXException) {
                throw IllegalArgumentException("Could not extract text from local document: $path", cause)
            } catch (cause: TikaException) {
                throw IllegalArgumentException("Could not extract text from local document: $path", cause)
            }
        }
        return handler.toString().also(::requireWithinTextLimit)
    }

    private fun requireWithinTextLimit(text: String) {
        require(text.length <= MAX_DOCUMENT_LENGTH) {
            "Extracted local document exceeds the $MAX_DOCUMENT_LENGTH character limit"
        }
    }

    private val PLAIN_TEXT_EXTENSIONS = setOf("md", "markdown", "txt")
}
