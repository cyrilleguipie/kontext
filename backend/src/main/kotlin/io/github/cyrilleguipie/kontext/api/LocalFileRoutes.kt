package io.github.cyrilleguipie.kontext.api

import io.github.cyrilleguipie.kontext.connector.impl.files.LocalDocumentReader
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

fun Route.localFileRoutes(configuredDirectory: String) {
    if (configuredDirectory.isBlank()) return
    val root = Path.of(configuredDirectory).toAbsolutePath().normalize()
    route("/api/sources/local-files") {
        get {
            val relativePath = call.request.queryParameters["path"]
            if (relativePath.isNullOrBlank() || relativePath.length > 2_048) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A valid file path is required"))
                return@get
            }
            val file = root.resolve(relativePath).normalize()
            if (!file.startsWith(root)) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid file path"))
                return@get
            }
            val text =
                withContext(Dispatchers.IO) {
                    val realRoot = root.toRealPath()
                    val realFile = runCatching { file.toRealPath() }.getOrNull()
                    if (realFile == null || !realFile.startsWith(realRoot) ||
                        !Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        null
                    } else {
                        val extension = realFile.fileName.toString().substringAfterLast('.', "").lowercase()
                        LocalDocumentReader.read(realFile, extension)
                    }
                }
            if (text == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Source file not found"))
            } else {
                call.respondText(text, ContentType.Text.Plain)
            }
        }
    }
}
