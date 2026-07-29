plugins {
    // The one convention, and nothing beside it. No application plugin, because
    // this module holds no program; and no persistence boundary, because the run
    // supplies the database the three programs are pointed at — so this is the
    // second module in the build, after the core, that the boundary does not
    // fence. What keeps the adapters off a database here is that they are
    // launched as their own distributions, each holding only its own jars.
    id("nook.kotlin-jvm")
}

// A target of its own rather than more tests under `check`. The run starts three
// programs and a database, and is asked for by name — `./gradlew systemTest` —
// so an ordinary build stays as fast as it is today. The cost is worth stating
// plainly: a change that breaks the assembled system passes `check`, and the
// continuous-integration run is where that gets caught, because it asks for both.
testing {
    suites {
        register<JvmTestSuite>("systemTest")
    }
}

dependencies {
    // The embedded database the run owns, and the changelog the core migrates it
    // with, from where the core's own suite takes them.
    //
    // This puts the core's classes on the classpath too, which is unavoidable
    // and worth naming: nothing here may call them. The assembled system is
    // reached over the wire, and a check that built a catalog or a service
    // directly would be measuring something else while looking identical.
    "systemTestImplementation"(testFixtures(project(":core-service")))
    // The tokens the run presents, minted against the same secret the adapters
    // are started with — the same ones both adapters' own suites present, so
    // that no two modules disagree about who `alex` is.
    "systemTestImplementation"(testFixtures(project(":contract")))
    // The protocol's own client, which is what drives the MCP server here. The
    // transport's session handling and its server-sent replies are the client's
    // business, and reimplementing them over raw requests would leave the run
    // measuring this module's idea of the protocol rather than the library's.
    "systemTestImplementation"(libs.mcp.sdk)
    // Named with its flavour, where every other module writes `kotlin("test")`.
    // The Kotlin plugin picks the flavour by looking at the test task, and it
    // looks only at the one belonging to the suite called `test` — so a suite
    // under any other name is handed the flavourless artifact, whose assertions
    // compile and whose `@Test` does not exist.
    "systemTestImplementation"(kotlin("test-junit5"))
}

// The three programs are started from the distributions the build installs — a
// folder of jars and a start script each, which is what an operator runs. Where
// each one landed is passed in rather than worked out in a test, so nothing here
// writes a path into somebody else's build directory.
tasks.named<Test>("systemTest") {
    Distributions.entries.forEach { installed ->
        val install = project(installed.module).tasks.named<Sync>("installDist")
        dependsOn(install)
        val folder = install.get().destinationDir
        inputs.dir(folder).withPropertyName(installed.property).withPathSensitivity(PathSensitivity.RELATIVE)
        systemProperty(installed.property, folder.absolutePath)
    }
}

/** The three programs, and the system property each one's folder is passed in under. */
enum class Distributions(val module: String, val property: String) {
    CORE(":core-service", "nook.distribution.core"),
    MCP(":mcp-server", "nook.distribution.mcp"),
    WEB(":web-app", "nook.distribution.web"),
}
