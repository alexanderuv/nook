pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// The build pins a JDK 25 toolchain. Without a resolver, a machine that has no
// JDK 25 fails at configuration time — nothing builds, and the message is about
// a missing toolchain rather than about how to get one. With it, Gradle
// downloads the toolchain instead of refusing.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "nook"

include(":contract")
include(":core-service")
include(":mcp-server")
include(":web-app")
