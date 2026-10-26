package io.github.cyrilleguipie.kontext

import io.github.cyrilleguipie.kontext.api.ComponentHealth
import io.github.cyrilleguipie.kontext.api.healthRoutes
import io.github.cyrilleguipie.kontext.config.KontextConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val serverLogger = LoggerFactory.getLogger("io.github.cyrilleguipie.kontext.Server")

fun Application.module(
    config: KontextConfig,
    dataSource: DataSource,
    checkLlmHealth: suspend () -> ComponentHealth = {
        ComponentHealth(status = "UP", details = "LLM health check initialized")
    },
) {
    serverLogger.debug("Configuring Ktor server module for port {}", config.server.port)

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            },
        )
    }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        anyHost()
    }

    install(SSE)

    routing {
        healthRoutes(dataSource, checkLlmHealth)
    }
}
