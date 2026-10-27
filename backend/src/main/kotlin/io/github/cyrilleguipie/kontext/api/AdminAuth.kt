package io.github.cyrilleguipie.kontext.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.security.MessageDigest

internal suspend fun ApplicationCall.requireAdminAuthorization(configuredToken: String): Boolean {
    val suppliedToken =
        request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            .orEmpty()
    val failure =
        when {
            configuredToken.isBlank() ->
                HttpStatusCode.ServiceUnavailable to "Admin API is disabled; configure KONTEXT_ADMIN_TOKEN"
            !MessageDigest.isEqual(
                configuredToken.toByteArray(Charsets.UTF_8),
                suppliedToken.toByteArray(Charsets.UTF_8),
            ) -> HttpStatusCode.Unauthorized to "Invalid admin token"
            else -> null
        }
    failure?.let { (status, message) -> respond(status, mapOf("error" to message)) }
    return failure == null
}
