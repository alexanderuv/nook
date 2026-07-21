plugins {
    id("nook.application")
    id("nook.persistence-boundary")
}

application {
    mainClass = "io.nook.web.MainKt"
}

dependencies {
    implementation(project(":contract"))
    implementation(libs.ktor.server.core)
}
