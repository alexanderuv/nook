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
    implementation(libs.exposed.java.time)
    implementation(libs.liquibase.core)
    runtimeOnly(libs.postgresql.jdbc)

    testImplementation(libs.zonky.embedded.postgres)
    testImplementation(platform(libs.zonky.postgres.binaries.bom))
    testRuntimeOnly(libs.zonky.postgres.binaries.darwin.arm64v8)
    testImplementation(libs.exposed.migration.jdbc)
    testImplementation(libs.postgresql.jdbc)
}

// The Liquibase changelog lives at db/changelog (one source of truth, shared with
// the CLI); packaging it into this module's resources puts it on the classpath,
// so the service and its tests load it the same way a deployed jar will.
tasks.processResources {
    from(rootProject.layout.projectDirectory.dir("db/changelog")) {
        into("db/changelog")
    }
}
