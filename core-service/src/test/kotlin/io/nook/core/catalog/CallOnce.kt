package io.nook.core.catalog

import io.nook.contract.CatalogClient
import kotlin.system.exitProcess

/** The address of the core to call, as a whole URL. */
const val CALLER_ADDRESS_SETTING: String = "NOOK_CORE_ADDRESS"

/**
 * A caller, as a program: it builds the calling library from a setting, makes
 * one call, and says what came back.
 *
 * It stands in for the two adapters, which do not exist yet, and it is here in
 * test sources for that reason — what it demonstrates is that both halves take
 * their address from outside and meet, which is a property of the connection
 * rather than of either adapter. Unlike the core, a caller may legitimately be
 * told to call anywhere, so its whole address is the setting.
 */
fun main() {
    val address = System.getenv(CALLER_ADDRESS_SETTING) ?: run {
        System.err.println("$CALLER_ADDRESS_SETTING is not set, and there is no default for it")
        exitProcess(1)
    }
    CatalogClient(address).use { caller ->
        println("the core answered with ${caller.listProjects().size} projects")
    }
}
