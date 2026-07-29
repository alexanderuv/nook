package io.nook.system

import java.net.ServerSocket
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Properties

/**
 * The database as this run reads it: plain SQL over an ordinary JDBC
 * connection, opened and closed per read.
 *
 * The run asks the store directly, rather than through the core, wherever what
 * is being checked is what a call *left behind* — a reply saying a row records
 * somebody is the same program answering about itself, and the point of an
 * assembled run is to ask the other end.
 */

/**
 * What every connection this run opens calls itself, so the store can tell them
 * apart from the core's.
 *
 * The check that only the core ever connects reads the store's own view of who
 * is connected, and this run is connected to it too. Excluding the connection
 * doing the asking is not enough: a read opens one and closes it, so a backend
 * from a moment ago can still be there while the next read looks — and it would
 * be counted as somebody else, which is the whole of what that check is about.
 */
internal const val THIS_RUN: String = "nook-system-test"

/** The rows [query] answers with, each as its columns by name, in the order the database produced them. */
internal fun rowsFrom(jdbcUrl: String, query: String, vararg values: Any?): List<Map<String, Any?>> =
    connectedTo(jdbcUrl) { connection ->
        connection.prepareStatement(query).use { statement ->
            values.forEachIndexed { at, value -> statement.setObject(at + 1, value) }
            statement.executeQuery().use { it.readRows() }
        }
    }

/** Runs [statement] against the database at [jdbcUrl], for the writes a check makes on its own behalf. */
internal fun runOn(jdbcUrl: String, statement: String, vararg values: Any?) {
    connectedTo(jdbcUrl) { connection ->
        connection.prepareStatement(statement).use { prepared ->
            values.forEachIndexed { at, value -> prepared.setObject(at + 1, value) }
            prepared.execute()
        }
    }
}

/** Opens a connection to [jdbcUrl], hands it to [read], and closes it whatever happens. */
internal fun <T> connectedTo(jdbcUrl: String, read: (Connection) -> T): T =
    DriverManager.getConnection(jdbcUrl, Properties().apply { setProperty("ApplicationName", THIS_RUN) }).use(read)

/**
 * A port nothing is listening on. The programs and the database are told where
 * to answer rather than choosing for themselves, because a check that stops one
 * and starts it again has to point everything at the same address both times.
 */
internal fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun ResultSet.readRows(): List<Map<String, Any?>> {
    val columns = (1..metaData.columnCount).map { metaData.getColumnLabel(it) }
    return buildList {
        while (next()) {
            add(columns.withIndex().associate { (at, name) -> name to getObject(at + 1) })
        }
    }
}
