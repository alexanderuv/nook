import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.jvm")
    // Both Kotlin plugins are applied from here, never from a module. A module
    // that declares one gets a buildscript classpath of its own, which loads the
    // Kotlin plugin a second time — Gradle warns that this "is not supported and
    // may break the build". Applying it to every module costs nothing: without a
    // @Serializable declaration it generates nothing.
    id("org.jetbrains.kotlin.plugin.serialization")
}

group = "io.nook"

kotlin {
    jvmToolchain(25)

    compilerOptions {
        // A warning nobody has to act on is a warning nobody reads, and the ones
        // that matter here arrive from a data-access library on a 1.x line: a
        // deprecation is how a version bump announces that an API this code
        // depends on is going away. Making them failures is what keeps that
        // announcement from scrolling past.
        allWarningsAsErrors = true
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Two bounds, because they fail differently. The per-test one interrupts the
    // test that overran and reports it as a failure, naming it; the task-level
    // one is the backstop for anything that survives interruption. Without
    // either, a test that blocks forever — and the ones about writers waiting on
    // each other are exactly that shape — takes the whole job down with it, with
    // no assertion message and no report to read afterwards.
    systemProperty("junit.jupiter.execution.timeout.default", "2 m")
    timeout = Duration.ofMinutes(20)
}
