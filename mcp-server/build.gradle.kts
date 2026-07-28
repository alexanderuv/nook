plugins {
    id("nook.application")
    id("nook.persistence-boundary")
}

application {
    mainClass = "io.nook.mcp.MainKt"
}

dependencies {
    implementation(project(":contract"))
    // The stand-in core and its entity fixtures, shared with the other door.
    testImplementation(testFixtures(project(":contract")))
    // The protocol, and the web container that hosts it. Nothing here serves
    // with Ktor: the library ships its transport as a servlet, which Jetty runs
    // unchanged, and the Ktor *client* the calling library is built on reaches
    // the core from the same program, with nothing shared between the two.
    implementation(libs.mcp.sdk)
    implementation(libs.jetty.server)
    implementation(libs.jetty.ee11.servlet)
}
