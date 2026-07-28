package io.nook.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * A core that was never started, and a core that answered and settled nothing.
 *
 * Two situations, one thing to say: the call produced no verdict, the request
 * was not the problem, and which half of Nook gave way is nobody's business
 * outside this library. So what these two throw says the same words, carries no
 * refusal code, and differs in exactly one place — the one this library keeps
 * for deciding its own recovery, and never passes on.
 */
class VerdictlessCallTest {

    private companion object {
        val coreThatBroke = """{"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"$NO_VERDICT"}}"""
    }

    @Test
    fun `a core that broke and a core that was never started say the same thing`() {
        var fromBrokenCore: BreakdownException? = null
        coreAnswering(coreThatBroke) { caller ->
            fromBrokenCore = assertFailsWith { caller.listProjects() }
        }
        var fromNoCore: BreakdownException? = null
        noCoreAt { caller ->
            fromNoCore = assertFailsWith { caller.listProjects() }
        }

        val broke = checkNotNull(fromBrokenCore)
        val absent = checkNotNull(fromNoCore)
        assertEquals(NO_VERDICT, broke.message)
        assertEquals(broke.message, absent.message, "the two say different things to whoever catches them")

        // And the one place they differ, which stays here: an answer that
        // settled nothing will settle nothing next time either, while a core
        // that is not up yet is reached by the same caller later.
        assertEquals(BreakdownOrigin.CORE, broke.origin)
        assertEquals(BreakdownOrigin.CONNECTION, absent.origin)
        assertNotEquals(broke.origin, absent.origin)
    }

    @Test
    fun `a failure that produced no verdict is never mistaken for something to fix`() {
        coreAnswering(coreThatBroke) { caller ->
            assertFailsWith<BreakdownException> { caller.listProjects() }
        }
        // The same reply read as a refusal would have an adapter tell an agent
        // to correct arguments that were never wrong.
        assertEquals(null, RpcError(RpcCode.INTERNAL_ERROR, NO_VERDICT).asStructuredError())
    }

    @Test
    fun `a refusal the core made still arrives as the core's own verdict`() {
        // The other half of the same rule: widening what counts as "no verdict"
        // must not swallow the failures a caller can actually act on.
        coreAnswering(
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32001,"message":"no project matches reference \"gone\"",""" +
                """"data":{"reason":"not_found","missing":"project"}}}""",
        ) { caller ->
            val refused = assertFailsWith<StructuredErrorException> { caller.getProject("gone") }
            assertEquals(ErrorCode.NOT_FOUND, refused.error.code)
            assertEquals("no project matches reference \"gone\"", refused.error.message)
            assertEquals(Missing.PROJECT, Missing.of(refused.error))
        }
    }
}
