import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

// Database/persistence dependencies are allowed only inside :core-service; the
// adapters reach the core over HTTP and hold no module edge to it. This plugin is
// applied to every other module, whose entire compile and runtime graphs must
// therefore stay free of the coordinates below. Must be applied after a JVM plugin
// (the compileClasspath and runtimeClasspath configurations have to exist).

val bannedGroups = setOf(
    "org.jetbrains.exposed",
    "org.postgresql",
    "org.xerial",
    "org.liquibase",
)

val checkPersistenceBoundary by tasks.registering {
    group = "verification"
    description =
        "Fails if a persistence dependency appears anywhere in this module's " +
            "resolved compile or runtime graph."

    val modulePath = project.path
    val graphRoots = listOf("compileClasspath", "runtimeClasspath").map { configurationName ->
        configurationName to configurations.named(configurationName)
            .flatMap { it.incoming.resolutionResult.rootComponent }
    }

    doLast {
        val violations = mutableListOf<String>()
        graphRoots.forEach { (configurationName, rootProvider) ->
            val visited = mutableSetOf<ResolvedComponentResult>()
            fun walk(component: ResolvedComponentResult, path: List<String>) {
                if (!visited.add(component)) return
                component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dependency ->
                    val selected = dependency.selected
                    val pathHere = path + selected.id.displayName
                    val module = selected.moduleVersion
                    if (module != null && module.group in bannedGroups) {
                        violations += "$modulePath ($configurationName): banned coordinate " +
                            "${module.group}:${module.name}:${module.version}\n" +
                            "      via ${pathHere.joinToString(" -> ")}"
                    } else {
                        walk(selected, pathHere)
                    }
                }
            }
            walk(rootProvider.get(), listOf(modulePath))
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Persistence boundary violated (persistence stays inside :core-service):\n" +
                    violations.joinToString("\n") { "  - $it" }
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkPersistenceBoundary)
}
