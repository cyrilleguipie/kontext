package io.github.cyrilleguipie.kontext.llm

import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

object HttpClientFactory {
    private val logger = LoggerFactory.getLogger(HttpClientFactory::class.java)

    fun createClientResource(): Resource<HttpClient> =
        resource({
            logger.info("Initializing Ktor CIO HTTP Client")
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            prettyPrint = false
                            isLenient = true
                            ignoreUnknownKeys = true
                        },
                    )
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 60_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 60_000
                }
            }
        }) { client, exitCase ->
            logger.info("Closing Ktor CIO HTTP Client (exitCase: {})", exitCase)
            client.close()
        }
}
