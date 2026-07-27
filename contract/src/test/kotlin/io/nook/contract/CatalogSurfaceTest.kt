package io.nook.contract

import java.lang.reflect.Modifier
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.valueParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Closes the catalog: eleven operations, no twelfth, and the project an
 * operation acts inside taken by exactly the seven that act inside one.
 *
 * The count is asserted rather than described, because the pressure on a
 * catalog runs both ways — three operations were folded away for being fields
 * wearing an operation's clothes, and the same reasoning applied loosely would
 * grow the surface back one convenience at a time. The two lists are checked
 * against each other rather than each against a literal, so an operation added
 * to one side and not the other fails here rather than at a caller.
 */
class CatalogSurfaceTest {

    private val declared: Collection<KFunction<*>> = OperationCatalog::class.declaredMemberFunctions

    private val projectScoped = setOf(
        "createItem", "updateItem", "deleteItem", "createRelease", "updateRelease", "getItem", "listItems",
    )

    private val instanceLevel = setOf("createProject", "getProject", "listProjects", "deleteProject")

    /** `create_project`, as the interface spells it: `createProject`. */
    private fun String.asOperationName(): String =
        split("_").mapIndexed { position, word ->
            if (position == 0) word else word.replaceFirstChar { it.uppercase() }
        }.joinToString("")

    @Test
    fun `the catalog offers exactly the eleven operations, under the names they travel by`() {
        assertEquals(11, CatalogOperation.entries.size)
        assertEquals(
            CatalogOperation.entries.map { it.label.asOperationName() }.toSet(),
            declared.map { it.name }.toSet(),
        )
        assertEquals(11, declared.size, "an operation is declared twice")
    }

    @Test
    fun `every operation is either project-scoped or instance-level, and the two lists are the eleven`() {
        assertEquals(declared.map { it.name }.toSet(), projectScoped + instanceLevel)
        assertTrue((projectScoped intersect instanceLevel).isEmpty())
    }

    @Test
    fun `the seven project-scoped operations take the project they act inside, first`() {
        val takingAProject = declared
            .filter { it.valueParameters.firstOrNull()?.name == "projectRef" }
            .map { it.name }
            .toSet()
        assertEquals(projectScoped, takingAProject)
    }

    @Test
    fun `the four instance-level operations offer no place to name a project to act inside`() {
        declared.filter { it.name in instanceLevel }.forEach { operation ->
            assertEquals(
                emptyList(),
                operation.valueParameters.map { it.name }.filter { it == "projectRef" },
                "${operation.name} acts on the instance and must take no project to act inside",
            )
        }
    }

    @Test
    fun `the calling library offers the same eleven, and one way to let go of the connection`() {
        val offered = CatalogClient::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()
        // `close` is not a twelfth operation: it asks the core for nothing and
        // is how a caller lets go of the web client the other eleven share.
        assertEquals(declared.map { it.name }.toSet() + "close", offered)
    }

    @Test
    fun `the two deletes hand back nothing, so no caller is given a row that no longer exists`() {
        listOf("deleteItem", "deleteProject").forEach { name ->
            val operation = OperationCatalog::class.java.declaredMethods.single { it.name == name }
            assertEquals(Void.TYPE, operation.returnType, "$name must return nothing")
        }
    }
}
