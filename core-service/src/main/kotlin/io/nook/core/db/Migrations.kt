package io.nook.core.db

import liquibase.Scope
import liquibase.command.CommandScope
import liquibase.resource.ClassLoaderResourceAccessor

/** Classpath location of the Liquibase master changelog (packaged by the build). */
private const val CHANGELOG_PATH = "db/changelog/db.changelog-master.yaml"

/**
 * Applies the schema changelog to the database at [jdbcUrl], creating Liquibase's
 * bookkeeping tables on first use. Safe to call repeatedly: already-applied
 * changesets are skipped. Runs Liquibase in-process — no CLI, no build-tool
 * plugin — so service startup and test fixtures migrate the same way.
 */
fun migrateDatabase(jdbcUrl: String, username: String? = null, password: String? = null) {
    Scope.child(mapOf(Scope.Attr.resourceAccessor.name to ClassLoaderResourceAccessor())) {
        val update = CommandScope("update")
        update.addArgumentValue("changelogFile", CHANGELOG_PATH)
        update.addArgumentValue("url", jdbcUrl)
        username?.let { update.addArgumentValue("username", it) }
        password?.let { update.addArgumentValue("password", it) }
        update.execute()
    }
}
