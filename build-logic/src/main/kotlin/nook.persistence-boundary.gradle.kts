import io.nook.gradle.CheckPersistenceBoundary
import org.gradle.api.tasks.SourceSetContainer

// Database/persistence dependencies are allowed only inside :core-service; the
// adapters reach the core over HTTP and hold no module edge to it. This plugin is
// applied to every other module, whose entire compile and runtime graphs must
// therefore stay free of the coordinates below. Must be applied after a JVM plugin
// (the source sets and their classpath configurations have to exist).
//
// Every source set is walked, not just the main one. Test code is where an
// adapter first reaches for a database — a fixture that "just needs a table for
// a moment" — and a boundary that stopped at production code would let exactly
// that through while reporting the module clean.

val bannedPersistenceGroups = setOf(
    "org.jetbrains.exposed",
    "org.postgresql",
    "org.xerial",
    "org.liquibase",
)

val checkPersistenceBoundary = tasks.register<CheckPersistenceBoundary>("checkPersistenceBoundary") {
    group = "verification"
    description =
        "Fails if a persistence dependency appears anywhere in any of this " +
            "module's resolved compile or runtime graphs."

    modulePath = project.path
    bannedGroups = bannedPersistenceGroups
}

the<SourceSetContainer>().all {
    val graphRoots = listOf(compileClasspathConfigurationName, runtimeClasspathConfigurationName)
        .associateWith { name ->
            configurations.named(name).flatMap { it.incoming.resolutionResult.rootComponent }
        }
    checkPersistenceBoundary.configure {
        graphRoots.forEach { (name, root) -> graphs.put(name, root) }
    }
}

tasks.named("check") {
    dependsOn(checkPersistenceBoundary)
}
