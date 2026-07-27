package io.nook.core.write

import io.nook.contract.CreateItem
import io.nook.contract.CreateProject
import io.nook.contract.CreateRelease
import io.nook.contract.ErrorCode
import io.nook.contract.FieldChange
import io.nook.contract.StructuredErrorException
import io.nook.contract.UpdateItem
import io.nook.contract.UpdateRelease
import io.nook.core.catalog.CatalogBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * The rules the write path enforces that nothing else would.
 *
 * Each one here has no backstop underneath it: the schema cannot express "only
 * a leaf carries blockers" or "an epic has no parent", because both are read
 * off the type column, which is data. If the check in the service goes, the
 * store accepts the row and the structure quietly stops meaning what the rest
 * of the code assumes it means.
 *
 * They are gathered rather than scattered because each is exercised on its own,
 * against a fixture where nothing else could refuse the call. A test that sets
 * up two violations at once passes when either guard survives, and that is
 * indistinguishable from passing when both do.
 */
abstract class WriteServiceGuardBehavior : CatalogBehavior() {

    private fun assertFailsWithCode(code: ErrorCode, block: () -> Unit): StructuredErrorException {
        val failure = assertFailsWith<StructuredErrorException> { block() }
        assertEquals(code, failure.error.code)
        return failure
    }

    /**
     * The one guard with nothing else standing behind it at all. A reference to
     * the item itself passes the "a parent must be an epic" check on the type
     * being replaced, meets no children because the item is about to become its
     * own first one, and satisfies the composite foreign key, which the row
     * pointing at itself makes true. What it would leave behind is a leaf that
     * parents itself: absent from every parent-partitioned listing, and an
     * endless walk for anything climbing the tree.
     */
    @Test
    fun `an item cannot be made its own parent, even while its type is changing`() {
        val project = service.createProject(CreateProject("No Self Parent"))
        val epic = service.createItem(project.slug, CreateItem(type = "epic", name = "Lonely epic"))

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(
                project.slug, epic.slug,
                UpdateItem(type = FieldChange.Set("task"), parentRef = FieldChange.Set(epic.slug)),
            )
        }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(
                project.slug, epic.slug,
                UpdateItem(type = FieldChange.Set("task"), parentRef = FieldChange.Set(epic.id.toString())),
            )
        }

        val untouched = readItem(db, project.slug, epic.slug)
        assertEquals("epic", untouched.type.label, "the refused call must leave the type alone")
        assertNotEquals(untouched.id, untouched.parentId)
        assertEquals(null, untouched.parentId)
    }

    @Test
    fun `blockers apply to leaves, so an epic target is refused`() {
        val project = service.createProject(CreateProject("Epic Takes No Blockers"))
        service.createItem(project.slug, CreateItem(type = "epic", name = "The epic"))
        service.createItem(project.slug, CreateItem(type = "task", name = "A leaf"))

        val failure = assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, "the-epic", UpdateItem(blockedBy = FieldChange.Set(listOf("a-leaf"))))
        }
        assertEquals(
            true,
            failure.error.message.contains("epic"),
            "the message must say what was wrong with the target: ${failure.error.message}",
        )
        assertEquals(emptySet(), readItem(db, project.slug, "the-epic").blockedBy)
    }

    @Test
    fun `a leaf sitting under an epic cannot become an epic while it still has a parent`() {
        val project = service.createProject(CreateProject("Parented Promotion"))
        service.createItem(project.slug, CreateItem(type = "epic", name = "The parent"))
        service.createItem(project.slug, CreateItem(type = "task", name = "The child", parentRef = "the-parent"))

        // No dependency edge and no children: the parent link is the only thing
        // that can refuse this call.
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, "the-child", UpdateItem(type = FieldChange.Set("epic")))
        }

        service.updateItem(project.slug, "the-child", UpdateItem(parentRef = FieldChange.Set(null)))
        val promoted = service.updateItem(project.slug, "the-child", UpdateItem(type = FieldChange.Set("epic")))
        assertEquals("epic", promoted.type.label)
    }

    /**
     * The blocked end of an edge, not the blocking one. Both are barred from
     * becoming epics, and the guard reads two columns to say so; a test that
     * only ever converts the blocker leaves half of it free to delete.
     */
    @Test
    fun `a leaf that is blocked cannot become an epic, just as a blocking one cannot`() {
        val project = service.createProject(CreateProject("Both Ends Of An Edge"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Upstream"))
        service.createItem(project.slug, CreateItem(type = "task", name = "Downstream"))
        service.updateItem(project.slug, "downstream", UpdateItem(blockedBy = FieldChange.Set(listOf("upstream"))))

        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, "downstream", UpdateItem(type = FieldChange.Set("epic")))
        }
        assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
            service.updateItem(project.slug, "upstream", UpdateItem(type = FieldChange.Set("epic")))
        }

        service.updateItem(project.slug, "downstream", UpdateItem(blockedBy = FieldChange.Set(emptyList())))
        assertEquals(
            "epic",
            service.updateItem(project.slug, "downstream", UpdateItem(type = FieldChange.Set("epic"))).type.label,
        )
    }

    @Test
    fun `an explicit slug is validated on the update path, not only on create`() {
        val project = service.createProject(CreateProject("Update Slug Rules"))
        val item = service.createItem(project.slug, CreateItem(type = "task", name = "Renameable"))
        service.createRelease(project.slug, CreateRelease("v1"))

        listOf("", "Not Lowercase", "under_scored", item.id.toString()).forEach { slug ->
            assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
                service.updateItem(project.slug, "renameable", UpdateItem(slug = FieldChange.Set(slug)))
            }
            assertFailsWithCode(ErrorCode.VALIDATION_FAILED) {
                service.updateRelease(project.slug, "v1", UpdateRelease(slug = FieldChange.Set(slug)))
            }
        }

        assertEquals("renameable", readItem(db, project.slug, "renameable").slug)
    }
}

class WriteServiceGuardInProcessTest : WriteServiceGuardBehavior() {
    override val reach = Reach.IN_PROCESS
}

class WriteServiceGuardAcrossConnectionTest : WriteServiceGuardBehavior() {
    override val reach = Reach.ACROSS_THE_CONNECTION
}
