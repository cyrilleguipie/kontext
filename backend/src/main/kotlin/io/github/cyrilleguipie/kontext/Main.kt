package io.github.cyrilleguipie.kontext

import arrow.continuations.SuspendApp
import arrow.core.raise.fold
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.resourceScope
import io.github.cyrilleguipie.kontext.config.KontextConfig.Companion.validate
import io.github.cyrilleguipie.kontext.store.Database
import io.github.cyrilleguipie.kontext.store.Database.validateEmbeddingDimension
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.awaitCancellation
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("io.github.cyrilleguipie.kontext.Main")

fun main() =
    SuspendApp {
        logger.info("Starting Kontext...")

        val config =
            fold(
                block = { validate() },
                recover = { error ->
                    logger.error("Configuration validation failed: {}", error.message)
                    exitProcess(1)
                },
                transform = { it },
            )

        logger.info(
            "Configuration loaded successfully: LLM model={}, embeddingDim={}, port={}",
            config.llm.chatModel,
            config.llm.embeddingDimension,
            config.server.port,
        )

        resourceScope {
            val dataSource = Database.dataSourceResource(config.database).bind()

            Database.migrate(dataSource, config.llm.embeddingDimension)

            fold(
                block = { validateEmbeddingDimension(dataSource, config.llm.embeddingDimension) },
                recover = { error ->
                    logger.error("Database dimension check failed: {}", error.message)
                    exitProcess(1)
                },
                transform = { },
            )

            resource({
                val server =
                    embeddedServer(Netty, port = config.server.port) {
                        module(config, dataSource)
                    }
                server.start(wait = false)
                server
            }) { server, exitCase ->
                logger.info("Shutting down Ktor server (exitCase: {})...", exitCase)
                server.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
            }.bind()

            logger.info("Kontext is ready! Listening on http://localhost:{}", config.server.port)
            awaitCancellation()
        }
    }
