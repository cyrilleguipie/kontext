plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.cyrilleguipie.kontext.MainKt")
}

dependencies {
    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)

    // Ktor Client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)

    // Arrow
    implementation(libs.arrow.core)
    implementation(libs.arrow.resilience)
    implementation(libs.suspendapp)
    implementation(libs.suspendapp.ktor)

    // Database
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.pgvector)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    // Config & Utilities
    implementation(libs.typesafe.config)
    implementation(libs.jsoup)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    // Testing
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.wiremock)
}

tasks.test {
    useJUnitPlatform()
    environment("DOCKER_API_VERSION", "1.44")
    systemProperty("DOCKER_API_VERSION", "1.44")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
}

ktlint {
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
}
