package io.nook.mcp

import io.nook.contract.CatalogOperation
import io.nook.contract.Invocation
import io.nook.contract.Project
import io.nook.contract.RecordingCore
import io.nook.contract.noProject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The shared stand-in, with what this server's checks need of it: a set of
 * projects an address can be resolved against, and a say in how that resolution
 * behaves.
 *
 * Which project an address names is the one question the door itself asks, and
 * it is asked outside any tool call — so it is answered here rather than through
 * the recorded answering, and counted apart from the tool calls below.
 */
class StandInCore : RecordingCore() {

    /** The projects this stand-in knows, each reachable by its handle and by its id. */
    val projects: MutableList<Project> = CopyOnWriteArrayList()

    /** Where a project comes from. Set by a test that wants the core unreachable, or slow. */
    var resolving: (String) -> Project = { ref ->
        projects.firstOrNull { it.slug == ref || it.id.toString() == ref } ?: noProject(ref)
    }

    /**
     * Everything that reached the core through a tool.
     *
     * The one question the door itself asks — which project an address names —
     * is not a tool call and is left out, so that "nothing reached the core" can
     * be said about a refused connection without it meaning "the door never
     * asked", which is a different claim.
     */
    val toolCalls: List<Invocation> get() = invocations.filterNot { it.operation == CatalogOperation.GET_PROJECT }

    /** How many times the door asked which project an address names. */
    val projectQuestions: List<Invocation> get() = invocationsOf(CatalogOperation.GET_PROJECT)

    override fun getProject(ref: String): Project {
        invocations += Invocation(CatalogOperation.GET_PROJECT, null, listOf(ref))
        return resolving(ref)
    }

    /**
     * A core asked to work inside a project that is not there refuses before it
     * looks at anything else, whatever it was asked to do. Which is what makes a
     * project disappearing under a connected agent something a test can arrange
     * by deleting it, rather than by scripting a refusal.
     */
    override fun before(invocation: Invocation) {
        val project = invocation.project
        if (project != null && projects.none { it.id.toString() == project }) noProject(project)
    }
}
