plugins {
    id("nook.application")
}

application {
    mainClass = "io.nook.core.MainKt"
}

dependencies {
    implementation(project(":contract"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.liquibase.core)
    // Runtime only, and it stays that way: no code here may name a driver type.
    // The service talks to the database through the data-access library and
    // standard SQL types alone, so which driver ships underneath is a
    // deployment detail — see the guard below.
    runtimeOnly(libs.postgresql.jdbc)

    testImplementation(libs.zonky.embedded.postgres)
    testImplementation(platform(libs.zonky.postgres.binaries.bom))
    testRuntimeOnly(libs.zonky.postgres.binaries.darwin.arm64v8)
    testImplementation(libs.exposed.migration.jdbc)
}

// The guard behind the driver's runtime-only scope: keeping it off the compile
// classpath is what makes "no driver types in the code" mechanical rather than a
// matter of discipline — a source file that names one cannot compile. This fails
// the build the moment the driver reappears at compile time, whether declared
// here or dragged in by something else.
val checkDriverStaysAtRuntime by tasks.registering {
    group = "verification"
    description = "Fails if the database driver reaches this module's compile classpath."

    val compileClasspath = configurations.named("compileClasspath")
    val driverCoordinates = compileClasspath.map { classpath ->
        classpath.incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .filter { it.group == "org.postgresql" }
            .map { "${it.group}:${it.name}:${it.version}" }
    }

    doLast {
        val found = driverCoordinates.get()
        if (found.isNotEmpty()) {
            throw GradleException(
                "The database driver must stay runtime-only, so that no code can name its types.\n" +
                    "  On the compile classpath: ${found.joinToString()}",
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkDriverStaysAtRuntime)
}

// The Liquibase changelog lives at db/changelog (one source of truth, shared with
// the CLI); packaging it into this module's resources puts it on the classpath,
// so the service and its tests load it the same way a deployed jar will.
tasks.processResources {
    from(rootProject.layout.projectDirectory.dir("db/changelog")) {
        into("db/changelog")
    }
}
