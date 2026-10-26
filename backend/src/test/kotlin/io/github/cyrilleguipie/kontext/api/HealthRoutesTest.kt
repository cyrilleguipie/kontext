package io.github.cyrilleguipie.kontext.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import javax.sql.DataSource

class HealthRoutesTest {
    @Test
    fun `should return 200 OK and UP when all components are healthy`() =
        testApplication {
            val mockDs = mockk<DataSource>()
            val mockConn = mockk<Connection>()
            val mockStmt = mockk<Statement>()
            val mockRs = mockk<ResultSet>()

            every { mockDs.connection } returns mockConn
            every { mockConn.createStatement() } returns mockStmt
            every { mockStmt.executeQuery("SELECT 1") } returns mockRs
            every { mockRs.next() } returns true
            every { mockRs.close() } returns Unit
            every { mockStmt.close() } returns Unit
            every { mockConn.close() } returns Unit

            install(ContentNegotiation) {
                json()
            }

            routing {
                healthRoutes(mockDs) {
                    ComponentHealth(status = "UP", details = "LLM ok")
                }
            }

            val response = client.get("/api/health")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            val parsed = Json.decodeFromString<HealthResponse>(body)
            assertEquals("UP", parsed.status)
            assertEquals("UP", parsed.database.status)
            assertEquals("UP", parsed.llm.status)
        }

    @Test
    fun `should return 200 DEGRADED when database is up but LLM is down`() =
        testApplication {
            val mockDs = mockk<DataSource>()
            val mockConn = mockk<Connection>()
            val mockStmt = mockk<Statement>()
            val mockRs = mockk<ResultSet>()

            every { mockDs.connection } returns mockConn
            every { mockConn.createStatement() } returns mockStmt
            every { mockStmt.executeQuery("SELECT 1") } returns mockRs
            every { mockRs.next() } returns true
            every { mockRs.close() } returns Unit
            every { mockStmt.close() } returns Unit
            every { mockConn.close() } returns Unit

            install(ContentNegotiation) {
                json()
            }

            routing {
                healthRoutes(mockDs) {
                    ComponentHealth(status = "DOWN", details = "LLM unreachable")
                }
            }

            val response = client.get("/api/health")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            val parsed = Json.decodeFromString<HealthResponse>(body)
            assertEquals("DEGRADED", parsed.status)
            assertEquals("UP", parsed.database.status)
            assertEquals("DOWN", parsed.llm.status)
        }

    @Test
    fun `should return 503 Service Unavailable when database is down`() =
        testApplication {
            val mockDs = mockk<DataSource>()
            every { mockDs.connection } throws RuntimeException("Connection refused")

            install(ContentNegotiation) {
                json()
            }

            routing {
                healthRoutes(mockDs) {
                    ComponentHealth(status = "UP", details = "LLM ok")
                }
            }

            val response = client.get("/api/health")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)

            val body = response.bodyAsText()
            val parsed = Json.decodeFromString<HealthResponse>(body)
            assertEquals("DOWN", parsed.status)
            assertEquals("DOWN", parsed.database.status)
            assertTrue(parsed.database.details?.contains("Connection refused") == true)
        }
}
