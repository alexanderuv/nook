package io.nook.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What the calling library makes of a reply it cannot read.
 *
 * A core one version ahead of this build is the case these are about: it
 * answers in words this build was never written against, and every one of those
 * answers arrived. So none of them is the connection, and none of them is
 * anything the caller can fix by changing its call — the two things a caller
 * would otherwise be told.
 *
 * No core is built here and nothing is stored. The replies are written out and
 * served by the runtime's own web server, which is the only way to say "a
 * version ahead" without having one.
 */
class UnreadableReplyTest {

    private companion object {

        /** A created item, answered as a core holding a fifth status would answer it. */
        val itemWithAnUnknownStatus = """
            {"jsonrpc":"2.0","id":1,"result":{
              "id":"0199a1f0-0000-7000-8000-000000000001",
              "projectId":"0199a1f0-0000-7000-8000-000000000002",
              "type":"task","slug":"add-search","name":"Add search",
              "status":"blocked","blockedBy":[],
              "createdAt":"2026-07-27T02:27:11.357547Z",
              "updatedAt":"2026-07-27T02:27:11.357547Z"}}
        """.trimIndent()
    }

    @Test
    fun `a write that landed is never reported as something wrong with the call`() {
        // The item was created. Were this to arrive as a refusal, an adapter
        // would tell an agent its arguments were bad, and the agent would fix
        // them by sending the same write again — creating a second item.
        coreAnswering(itemWithAnUnknownStatus) { caller ->
            val breakdown = assertFailsWith<BreakdownException> {
                caller.createItem("search", CreateItem(type = "task", name = "Add search"))
            }
            assertEquals(BreakdownOrigin.CORE, breakdown.origin)
        }
    }

    @Test
    fun `a failure naming a reason this build does not know says the core answered`() {
        // Reported as the connection, this reads as "no answer arrived" and as
        // something a later attempt gets past. The core will refuse the same way
        // every time, so there is nothing for a retry to reach.
        coreAnswering(
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32004,"message":"too many",""" +
                """"data":{"reason":"rate_limited"}}}""",
        ) { caller ->
            val breakdown = assertFailsWith<BreakdownException> { caller.listProjects() }
            assertEquals(BreakdownOrigin.CORE, breakdown.origin)
        }
    }

    @Test
    fun `a reply answering a call this did not make says the core answered`() {
        // Two calls sharing one connection would otherwise be free to swap
        // answers, and an answer belonging to another call is no answer to this
        // one at all.
        coreAnswering("""{"jsonrpc":"2.0","id":999,"result":[]}""") { caller ->
            assertEquals(
                BreakdownOrigin.CORE,
                assertFailsWith<BreakdownException> { caller.listProjects() }.origin,
            )
        }
    }

    @Test
    fun `a reply that is not a reply at all says the core answered`() {
        coreAnswering("""{"jsonrpc":"2.0","id":1}""") { caller ->
            assertEquals(
                BreakdownOrigin.CORE,
                assertFailsWith<BreakdownException> { caller.listProjects() }.origin,
            )
        }
        coreAnswering("this is not JSON") { caller ->
            assertEquals(
                BreakdownOrigin.CORE,
                assertFailsWith<BreakdownException> { caller.listProjects() }.origin,
            )
        }
        // A status carrying no reply is still a core that answered: nothing that
        // arrives at all is the connection's doing.
        coreAnswering("", status = 500) { caller ->
            assertEquals(
                BreakdownOrigin.CORE,
                assertFailsWith<BreakdownException> { caller.listProjects() }.origin,
            )
        }
    }

    @Test
    fun `nothing listening is still the connection`() {
        // The other half of the same rule, so that widening what counts as the
        // core cannot quietly swallow the case the origin exists for.
        noCoreAt { caller ->
            assertEquals(
                BreakdownOrigin.CONNECTION,
                assertFailsWith<BreakdownException> { caller.listProjects() }.origin,
            )
        }
    }
}
