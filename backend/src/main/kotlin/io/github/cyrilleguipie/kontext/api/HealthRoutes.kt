package io.github.cyrilleguipie.kontext.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import javax.sql.DataSource

@Serializable
data class ComponentHealth(
    val status: String,
    val details: String? = null,
)

@Serializable
data class HealthResponse(
    val status: String,
    val database: ComponentHealth,
    val llm: ComponentHealth,
)

fun Route.healthRoutes(
    dataSource: DataSource,
    checkLlmHealth: suspend () -> ComponentHealth,
) {
    route("/api/health") {
        get {
            val dbHealth = checkDbHealth(dataSource)
            val llmHealth = checkLlmHealth()

            val overallStatus =
                if (dbHealth.status == "UP" && llmHealth.status == "UP") {
                    "UP"
                } else if (dbHealth.status == "UP") {
                    "DEGRADED"
                } else {
                    "DOWN"
                }

            val statusCode =
                if (overallStatus == "DOWN") {
                    HttpStatusCode.ServiceUnavailable
                } else {
                    HttpStatusCode.OK
                }

            call.respond(
                statusCode,
                HealthResponse(
                    status = overallStatus,
                    database = dbHealth,
                    llm = llmHealth,
                ),
            )
        }
    }
}

private fun checkDbHealth(dataSource: DataSource): ComponentHealth {
    return runCatching {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT 1").use { rs ->
                    if (rs.next()) {
                        ComponentHealth(status = "UP", details = "Database connection healthy")
                    } else {
                        ComponentHealth(status = "DOWN", details = "No result from database query")
                    }
                }
            }
        }
    }.getOrElse { ex ->
        ComponentHealth(status = "DOWN", details = ex.message ?: "Database check failed")
    }
}
