package io.nook.system

import java.io.File

/**
 * One of the three programs, as the folder the build installs it into: its own
 * jars, and the start script an operator runs.
 *
 * Every program in this run is started from here and never from this run's own
 * classpath. The two look alike, and starting a program the other way is the one
 * mistake that would erase what the run exists to observe before its first call:
 * this module holds the core's classes, the data-access library and the driver,
 * and an adapter started with those on it could reach a database whatever the
 * assembled system does.
 */
internal enum class Distribution(private val installedAt: String, private val script: String) {

    CORE("nook.distribution.core", "core-service"),
    MCP("nook.distribution.mcp", "mcp-server"),
    WEB("nook.distribution.web", "web-app"),
    ;

    /** The start script, which is the thing that gets run. */
    val startScript: File get() = File(folder, "bin/$script")

    /** Every jar this program starts with, by file name. */
    val jars: List<String> get() = File(folder, "lib").list().orEmpty().sorted()

    override fun toString(): String = script

    private val folder: File
        get() = File(
            checkNotNull(System.getProperty(installedAt)) {
                "nothing said where $script is installed; the build passes that folder in as $installedAt"
            },
        )
}
