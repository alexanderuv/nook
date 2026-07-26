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

// No serialization plugin and no serializer dependency, deliberately. Nothing
// here is @Serializable — the wire format is designed when a wire exists (see
// Entities.kt) — and carrying the plugin meanwhile cost more than it looked
// like: applying it here gave this module a buildscript classpath of its own,
// which loads the Kotlin plugin a second time. Gradle's own warning for that
// says it "is not supported and may break the build".
