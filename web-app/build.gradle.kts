plugins {
    id("nook.application")
    id("nook.persistence-boundary")
}

application {
    mainClass = "io.nook.web.MainKt"
}

dependencies {
    implementation(project(":contract"))
    // The stand-in core and its entity fixtures, shared with the other adapter.
    testImplementation(testFixtures(project(":contract")))
    // The engine the core already answers with, so the two adapters differ
    // in what they serve rather than in what serves it. The calling library and
    // its own engine arrive with the contract.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
}
