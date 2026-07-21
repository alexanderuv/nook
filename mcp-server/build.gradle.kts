plugins {
    id("nook.application")
    id("nook.persistence-boundary")
}

application {
    mainClass = "io.nook.mcp.MainKt"
}

dependencies {
    implementation(project(":contract"))
    implementation(libs.ktor.server.core)
    implementation(libs.mcp.sdk)
}
