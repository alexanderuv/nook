plugins {
    id("nook.application")
    // The embedded database ships from here, because the assembled run in
    // :system-test needs the same one this module's own suite uses. Two copies
    // free to differ would have the two measuring against differently built
    // databases while appearing to agree — which is the reason :contract already
    // ships its stand-in core this way.
    id("java-test-fixtures")
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

    // The names the checks are about — the person a call is made for, the agent
    // acting — shared with both adapters, so that no two modules disagree about who
    // `alex` is.
    testImplementation(testFixtures(project(":contract")))
    testImplementation(libs.exposed.migration.jdbc)

    // The embedded database, declared where the helpers that start it live, so
    // that whoever takes the fixtures gets the binaries to run them with. `api`
    // rather than `implementation`: both helpers hand out a real server, so a
    // caller holds the library either way.
    testFixturesApi(libs.zonky.embedded.postgres)
    testFixturesApi(platform(libs.zonky.postgres.binaries.bom))
    // Apple silicon is not in the library's default platform set, and the
    // default set is what carries the Linux the continuous-integration run uses.
    testFixturesRuntimeOnly(libs.zonky.postgres.binaries.darwin.arm64v8)
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
