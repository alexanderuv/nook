plugins {
    id("nook.application")
}

application {
    mainClass = "io.nook.core.MainKt"
}

dependencies {
    implementation(project(":contract"))
    implementation(libs.ktor.server.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.liquibase.core)
    runtimeOnly(libs.postgresql.jdbc)
    runtimeOnly(libs.sqlite.jdbc)
}
