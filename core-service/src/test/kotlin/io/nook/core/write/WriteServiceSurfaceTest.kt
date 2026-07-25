package io.nook.core.write

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Closes the public surface: the write service offers exactly the seven
 * mutations — in particular, nothing that deletes or removes anything.
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
                "setItemBlockedBy",
                "createRelease",
                "updateRelease",
                "assignEpicToRelease",
            ),
            publicOperations,
        )
    }
}
