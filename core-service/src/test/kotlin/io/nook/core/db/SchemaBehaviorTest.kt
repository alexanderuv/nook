package io.nook.core.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The schema's rules, exercised as plain SQL against the migrated database —
 * exactly what the constraints, the CHECK, and the `ready_item` view must
 * enforce regardless of any code on top. All tests share one migrated database;
 * each works in its own project rows.
 */
class SchemaBehaviorTest {

    companion object {
        private val url by lazy { EmbeddedPostgresSupport.freshMigratedDatabase() }
    }

    // Item types and statuses as stored (epic=1, task=2; todo=1, in_progress=2,
    // done=3, cancelled=4).

    @Test
    fun `happy-path inserts land in all five writable tables`() {
        withConnection { c ->
            val project = c.newProject("happy")
            val release = c.newRelease(project, "happy-r1")
            val epic = c.newItem(project, type = 1, slug = "happy-epic")
            c.exec("UPDATE project_item SET release_id = '$release' WHERE id = '$epic'")
            val task = c.newItem(project, type = 2, slug = "happy-task", parent = epic)
            val blocker = c.newItem(project, type = 2, slug = "happy-blocker")
            c.exec("INSERT INTO item_dependency (item_id, depends_on_id) VALUES ('$task', '$blocker')")
            c.exec(
                "INSERT INTO document (id, project_id, item_id, kind, name, path) " +
                    "VALUES ('${UUID.randomUUID()}', '$project', '$epic', 2, 'plan', '/epics/happy-epic/plan.md')"
            )

            assertEquals(1, c.count("SELECT COUNT(*) FROM release WHERE project_id = '$project'"))
            assertEquals(3, c.count("SELECT COUNT(*) FROM project_item WHERE project_id = '$project'"))
            assertEquals(1, c.count("SELECT COUNT(*) FROM item_dependency WHERE item_id = '$task'"))
            assertEquals(1, c.count("SELECT COUNT(*) FROM document WHERE project_id = '$project'"))
        }
    }

    @Test
    fun `ready_item excludes a blocked leaf`() {
        withConnection { c ->
            val project = c.newProject("blocked")
            val blocker = c.newItem(project, type = 2, slug = "blocked-blocker", status = 2)
            val leaf = c.newItem(project, type = 2, slug = "blocked-leaf")
            c.exec("INSERT INTO item_dependency (item_id, depends_on_id) VALUES ('$leaf', '$blocker')")

            assertEquals(0, c.count("SELECT COUNT(*) FROM ready_item WHERE id = '$leaf'"))
        }
    }

    @Test
    fun `ready_item frees a leaf once its blocker is cancelled`() {
        withConnection { c ->
            val project = c.newProject("freed")
            val blocker = c.newItem(project, type = 2, slug = "freed-blocker", status = 2)
            val leaf = c.newItem(project, type = 2, slug = "freed-leaf")
            c.exec("INSERT INTO item_dependency (item_id, depends_on_id) VALUES ('$leaf', '$blocker')")

            c.exec("UPDATE project_item SET status = 4 WHERE id = '$blocker'")

            assertEquals(1, c.count("SELECT COUNT(*) FROM ready_item WHERE id = '$leaf'"))
        }
    }

    @Test
    fun `timestamps default to the insertion time`() {
        withConnection { c ->
            val project = c.newProject("stamped")
            c.createStatement().use { statement ->
                statement.executeQuery("SELECT created_at, updated_at FROM project WHERE id = '$project'").use { rows ->
                    assertTrue(rows.next())
                    assertNotNull(rows.getTimestamp("created_at"))
                    assertNotNull(rows.getTimestamp("updated_at"))
                }
            }
        }
    }

    @Test
    fun `an item cannot block itself`() {
        withConnection { c ->
            val project = c.newProject("selfblock")
            val leaf = c.newItem(project, type = 2, slug = "selfblock-leaf")

            assertRejected("ck_dep_no_self_block") { c ->
                c.exec("INSERT INTO item_dependency (item_id, depends_on_id) VALUES ('$leaf', '$leaf')")
            }
        }
    }

    @Test
    fun `an item cannot reference a project that does not exist`() {
        assertRejected("fk_item_project") { c ->
            c.exec(
                "INSERT INTO project_item (id, project_id, type, slug, name) " +
                    "VALUES ('${UUID.randomUUID()}', '${UUID.randomUUID()}', 2, 'dangling', 'Dangling')"
            )
        }
    }

    @Test
    fun `a leaf cannot have a parent in another project`() {
        withConnection { c ->
            val projectA = c.newProject("parent-a")
            val projectB = c.newProject("parent-b")
            val epicInA = c.newItem(projectA, type = 1, slug = "parent-a-epic")

            assertRejected("fk_item_parent_same_project") { conn ->
                conn.exec(
                    "INSERT INTO project_item (id, project_id, parent_id, type, slug, name) " +
                        "VALUES ('${UUID.randomUUID()}', '$projectB', '$epicInA', 2, 'parent-b-leaf', 'Leaf')"
                )
            }
        }
    }

    @Test
    fun `an epic cannot join a release in another project`() {
        withConnection { c ->
            val projectA = c.newProject("release-a")
            val projectB = c.newProject("release-b")
            val releaseInA = c.newRelease(projectA, "release-a-r1")
            val epicInB = c.newItem(projectB, type = 1, slug = "release-b-epic")

            assertRejected("fk_item_release_same_project") { conn ->
                conn.exec("UPDATE project_item SET release_id = '$releaseInA' WHERE id = '$epicInB'")
            }
        }
    }

    @Test
    fun `a slug cannot repeat inside a project`() {
        withConnection { c ->
            val project = c.newProject("dupslug")
            c.newItem(project, type = 1, slug = "dup")

            assertRejected("uq_item_project_slug") { conn ->
                conn.exec(
                    "INSERT INTO project_item (id, project_id, type, slug, name) " +
                        "VALUES ('${UUID.randomUUID()}', '$project', 2, 'dup', 'Dup again')"
                )
            }
        }
    }

    @Test
    fun `the same slug is fine in different projects`() {
        withConnection { c ->
            val projectA = c.newProject("slugshare-a")
            val projectB = c.newProject("slugshare-b")
            c.newItem(projectA, type = 2, slug = "shared")
            c.newItem(projectB, type = 2, slug = "shared")

            assertEquals(2, c.count("SELECT COUNT(*) FROM project_item WHERE slug = 'shared'"))
        }
    }

    @Test
    fun `deleting a project cascades to everything it contains`() {
        withConnection { c ->
            val project = c.newProject("doomed")
            val release = c.newRelease(project, "doomed-r1")
            val epic = c.newItem(project, type = 1, slug = "doomed-epic")
            val task = c.newItem(project, type = 2, slug = "doomed-task", parent = epic)
            val blocker = c.newItem(project, type = 2, slug = "doomed-blocker")
            c.exec("INSERT INTO item_dependency (item_id, depends_on_id) VALUES ('$task', '$blocker')")
            c.exec(
                "INSERT INTO document (id, project_id, kind, name, path) " +
                    "VALUES ('${UUID.randomUUID()}', '$project', 8, 'adr-1', '/adrs/adr-1.md')"
            )

            c.exec("DELETE FROM project WHERE id = '$project'")

            assertEquals(0, c.count("SELECT COUNT(*) FROM release WHERE id = '$release'"))
            assertEquals(0, c.count("SELECT COUNT(*) FROM project_item WHERE project_id = '$project'"))
            assertEquals(0, c.count("SELECT COUNT(*) FROM item_dependency WHERE item_id = '$task'"))
            assertEquals(0, c.count("SELECT COUNT(*) FROM document WHERE project_id = '$project'"))
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T =
        DriverManager.getConnection(url).use(block)

    private fun assertRejected(constraint: String, attempt: (Connection) -> Unit) {
        val failure = assertFailsWith<SQLException> { withConnection(attempt) }
        assertTrue(
            failure.message.orEmpty().contains(constraint),
            "expected a $constraint violation, got: ${failure.message}",
        )
    }

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.count(sql: String): Int =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun Connection.newProject(slug: String): UUID {
        val id = UUID.randomUUID()
        exec("INSERT INTO project (id, slug, name) VALUES ('$id', '$slug', 'Project $slug')")
        return id
    }

    private fun Connection.newRelease(projectId: UUID, slug: String): UUID {
        val id = UUID.randomUUID()
        exec("INSERT INTO release (id, project_id, slug, name) VALUES ('$id', '$projectId', '$slug', 'Release $slug')")
        return id
    }

    private fun Connection.newItem(
        projectId: UUID,
        type: Int,
        slug: String,
        parent: UUID? = null,
        status: Int = 1,
    ): UUID {
        val id = UUID.randomUUID()
        val parentValue = parent?.let { "'$it'" } ?: "NULL"
        exec(
            "INSERT INTO project_item (id, project_id, parent_id, type, slug, name, status) " +
                "VALUES ('$id', '$projectId', $parentValue, $type, '$slug', 'Item $slug', $status)"
        )
        return id
    }
}
