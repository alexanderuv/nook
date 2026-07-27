plugins {
    id("nook.kotlin-jvm")
    id("nook.persistence-boundary")
}

// This module is the shape every other one agrees on, so its public surface is
// declared rather than inferred: explicit visibility on everything, and nothing
// public by accident.
kotlin {
    explicitApi()
}

dependencies {
    // The wire, and the one library that turns these shapes into text and back.
    // `api` rather than `implementation`: the request and reply shapes are part
    // of what this module offers, so anyone holding them holds their format too.
    //
    // The plugin that writes the conversion code is applied by nook.kotlin-jvm,
    // never here — a module that declares it gets a buildscript classpath of its
    // own, which loads the Kotlin plugin twice.
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.client.core)
    // Which engine drives the client is this module's own choice, so it ships
    // one: a caller builds the calling library and nothing else.
    implementation(libs.ktor.client.cio)

    // Java reflection reports a method's parameter types but not their names,
    // and what the catalog's surface has to be held to is partly the names: the
    // project an operation acts inside is a named argument of the seven that
    // take one, and the four that do not must offer no place for it.
    testImplementation(kotlin("reflect"))
}
