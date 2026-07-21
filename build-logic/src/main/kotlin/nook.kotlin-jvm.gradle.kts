plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "io.nook"

kotlin {
    jvmToolchain(25)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
