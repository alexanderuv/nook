package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.UpdateItem
import io.nook.contract.UpdateRelease
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Closes the public surface: the write service offers exactly the seven
 * mutations, and none of them can bring a deleted row back.
 *
 * Both halves are checked, because the operation names alone would not catch
 * the shape a way back would most plausibly take. Nobody would add a
 * `restoreItem`; someone would add a field to an existing command — leaving the
 * name set untouched and the no-go quietly reopened.
 *
 * The count is asserted rather than described, because the pressure on a
 * catalog runs both ways: two operations were folded into `updateItem` for
 * being fields wearing an operation's clothes, and the same reasoning applied
 * loosely would grow the surface back one convenience at a time.
 */
class WriteServiceSurfaceTest {

    @Test
    fun `the public surface is exactly the seven mutations`() {
        val publicOperations = WriteService::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf(
                "createProject",
                "createItem",
                "updateItem",
                "createRelease",
                "updateRelease",
                "deleteItem",
                "deleteProject",
            ),
            publicOperations,
        )
    }

    @Test
    fun `an item changes through update_item alone, whatever the field`() {
        // The fold's standing rule, not a restatement of the surface test above:
        // no second way to move an item's release or its blockers may reappear
        // beside the fields that now carry them.
        val itemFields = UpdateItem::class.java.declaredFields.map { it.name }
        assertEquals(
            listOf("name", "slug", "description", "status", "type", "parentRef", "releaseRef", "blockedBy"),
            itemFields,
        )

        val operationsTouchingAnItem = WriteService::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filter { it.contains("Item", ignoreCase = true) }
            .toSet()
        assertEquals(setOf("createItem", "updateItem", "deleteItem"), operationsTouchingAnItem)
    }

    @Test
    fun `no command carries a field that could ask for a deleted row back`() {
        val fieldNames = listOf(
            CreateProject::class.java,
            CreateItem::class.java,
            UpdateItem::class.java,
            CreateRelease::class.java,
            UpdateRelease::class.java,
        ).flatMap { command -> command.declaredFields.map { "${command.simpleName}.${it.name}" } }

        val wayBack = fieldNames.filter { field ->
            listOf("delet", "restor", "undo", "revive", "archiv").any { field.contains(it, ignoreCase = true) }
        }
        assertEquals(emptyList(), wayBack)
    }

    @Test
    fun `the delete operations return nothing, so no caller is handed a deleted row`() {
        // By exact name: the compiler also emits a method per lambda body, and
        // those carry their enclosing operation's name as a prefix.
        listOf("deleteItem", "deleteProject").forEach { name ->
            val operation = WriteService::class.java.declaredMethods.single { it.name == name }
            assertEquals(Void.TYPE, operation.returnType, "$name must return nothing")
        }
    }
}
