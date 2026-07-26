package io.nook.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails if a persistence coordinate appears anywhere in the dependency graphs
 * handed to it.
 *
 * A task class rather than a `doLast` block in the plugin script, for two
 * reasons that turn out to be the same one. A closure written in a script
 * captures the script object, so the configuration cache cannot store the task
 * and refuses the build outright. And a task whose inputs are declared — rather
 * than reached for through the project at execution time — is a task Gradle can
 * schedule, cache, and re-run only when a graph actually changed.
 */
abstract class CheckPersistenceBoundary : DefaultTask() {

    /** The module under inspection, named in any violation it reports. */
    @get:Input
    abstract val modulePath: Property<String>

    /** Dependency groups that must not appear, at any depth. */
    @get:Input
    abstract val bannedGroups: SetProperty<String>

    /** The resolved graph of each configuration to walk, keyed by its name. */
    @get:Input
    abstract val graphs: MapProperty<String, ResolvedComponentResult>

    @TaskAction
    fun check() {
        val modulePath = modulePath.get()
        val banned = bannedGroups.get()
        val violations = graphs.get().entries
            .sortedBy { it.key }
            .flatMap { (configurationName, root) ->
                bannedCoordinatesIn(root, banned).map { (coordinate, path) ->
                    "$modulePath ($configurationName): banned coordinate $coordinate\n" +
                        "      via ${(listOf(modulePath) + path).joinToString(" -> ")}"
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Persistence boundary violated (persistence stays inside :core-service):\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }

    /**
     * Every banned coordinate reachable from [root], each with the path that
     * reaches it. The walk stops at a violation rather than descending into it:
     * what a banned dependency drags in is not the finding, and reporting the
     * shortest path to the offender is what makes the message actionable.
     */
    private fun bannedCoordinatesIn(
        root: ResolvedComponentResult,
        banned: Set<String>,
    ): List<Pair<String, List<String>>> {
        val found = mutableListOf<Pair<String, List<String>>>()
        val visited = mutableSetOf<ResolvedComponentResult>()

        fun walk(component: ResolvedComponentResult, path: List<String>) {
            if (!visited.add(component)) return
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dependency ->
                val selected = dependency.selected
                val pathHere = path + selected.id.displayName
                val module = selected.moduleVersion
                if (module != null && module.group in banned) {
                    found += "${module.group}:${module.name}:${module.version}" to pathHere
                } else {
                    walk(selected, pathHere)
                }
            }
        }

        walk(root, emptyList())
        return found
    }
}
