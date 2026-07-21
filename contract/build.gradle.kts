plugins {
    id("nook.kotlin-jvm")
    id("nook.persistence-boundary")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
