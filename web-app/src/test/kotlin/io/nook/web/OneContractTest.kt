package io.nook.web

import io.nook.contract.RpcCode
import io.nook.contract.ALEX
import io.nook.contract.Actor
import io.nook.contract.RpcReply
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * One contract and not two: a request written once, sent to the core's own
 * connection and to this app, coming back saying the same thing at each.
 *
 * Nothing is rewritten between the two sends, because there is one shape and
 * this app serves it. And nothing is asserted about *what* the replies say —
 * only that they are the same reply — so an operation whose answer changes
 * needs nothing here edited, while a second design creeping in fails
 * immediately.
 *
 * The requests that cannot be read matter as much as the eleven that can: each
 * has to be turned down at both addresses, in the same words, and reach the core at
 * neither.
 */
class OneContractTest {

    private val both = CoreAndApi()

    private val core = both.core

    @AfterTest
    fun letGo() {
        both.close()
    }

    /**
     * [body] sent to each adapter in turn, with the record of what reached the
     * core wiped between them: the two replies, and the two ways the core was
     * reached, have to be equal as whole values.
     */
    private fun bothAgreeOn(what: String, body: String, reaching: Int) {
        core.invocations.clear()
        // The core is reached the way an adapter reaches it, naming who the call
        // is for beside the request — so that what the two are compared on is
        // the request and its verdict, not which identity happened to carry it.
        val throughTheCore = replyFrom(both.coreAddress, body, naming = Actor(ALEX))
        val reachedByTheCore = core.invocations.toList()

        core.invocations.clear()
        val throughTheApp = replyFrom(both.apiAddress, body)
        val reachedByTheApp = core.invocations.toList()

        assertEquals(throughTheCore, throughTheApp, "$what: the two both answered differently")
        assertEquals(reachedByTheCore, reachedByTheApp, "$what: the two both reached the core differently")
        assertEquals(reaching, reachedByTheApp.size, "$what: the core was reached ${reachedByTheApp.size} times")
    }

    @Test
    fun `each of the eleven operations answers the same at both addresses, reaching the core once`() {
        eleven.forEach { (what, body) -> bothAgreeOn(what, body, reaching = 1) }
        assertEquals(11, eleven.size, "the eleven operations were not all driven")
    }

    @Test
    fun `each of the four refusals answers the same at both addresses`() {
        fourRefusals.forEach { (what, body) -> bothAgreeOn(what, body, reaching = 1) }
        // Each of them really was a failure, and the four were really four.
        val codes = fourRefusals.values.map { assertIs<RpcReply.Failed>(both.answer(it)).error.code }
        assertEquals(4, codes.toSet().size, "the four refusals did not arrive under four different numbers")
    }

    @Test
    fun `each request that cannot be read is turned down the same at both addresses, reaching the core at neither`() {
        unreadable.forEach { (what, body) -> bothAgreeOn(what, body, reaching = 0) }
        assertEquals(8, unreadable.size, "the requests that cannot be read were not all driven")
    }

    @Test
    fun `an operation nobody defined is not the web server's own answer for an address nobody defined`() {
        // One address rather than one per operation is what makes this possible
        // at all: an unknown operation is a reply naming it, and an unknown
        // address is the web server's business.
        val unknown = assertIs<RpcReply.Failed>(both.answer(rawCall("teleport_item")))
        assertEquals(RpcCode.METHOD_NOT_FOUND, unknown.error.code)
        assertTrue(unknown.error.message.contains("teleport_item"), unknown.error.message)
    }

    @Test
    fun `every other address is the web server's own reply, carrying no error of Nook's`() {
        val elsewhere = mapOf(
            "the root, which the interface arrives at later" to
                sentTo(both.appAddress + "/", rawCall("list_projects")),
            "reading the one address rather than sending it a request" to
                sentTo(both.apiAddress, "", method = "GET"),
            "an operation given an address of its own" to
                sentTo("${both.apiAddress}/create_project", rawCall("create_project", """{"name":"A"}""")),
        )
        elsewhere.forEach { (what, answered) ->
            assertNotEquals(200, answered.status, "$what: this app served an address it does not define")
            assertTrue(
                !answered.said.contains("jsonrpc") && !answered.said.contains("\"error\""),
                "$what: the web server's own reply carried an error of Nook's: ${answered.said}",
            )
        }
        assertEquals(emptyList(), core.invocations, "an address this app does not define reached the core")

        // And the one address it does define is still served.
        assertIs<RpcReply.Answered>(both.answer(rawCall("list_projects")))
    }

    private companion object {

        const val PROJECT = "search-revamp"

        /** Every operation the catalog holds, each written the way a caller writes it. */
        val eleven = mapOf(
            "create_project" to rawCall("create_project", """{"name":"Search revamp"}"""),
            "get_project" to rawCall("get_project", """{"ref":"$PROJECT"}"""),
            "list_projects" to rawCall("list_projects"),
            "delete_project" to rawCall("delete_project", """{"ref":"$PROJECT"}"""),
            "create_item" to rawCall("create_item", """{"project":"$PROJECT","type":"task","name":"Add search"}"""),
            "update_item" to rawCall("update_item", """{"project":"$PROJECT","ref":"add-search","status":"done"}"""),
            "delete_item" to rawCall("delete_item", """{"project":"$PROJECT","ref":"add-search"}"""),
            "create_release" to rawCall("create_release", """{"project":"$PROJECT","name":"Autumn"}"""),
            "update_release" to
                rawCall("update_release", """{"project":"$PROJECT","ref":"autumn","targetDate":"2026-12-24"}"""),
            "get_item" to rawCall("get_item", """{"project":"$PROJECT","ref":"add-search"}"""),
            "list_items" to rawCall("list_items", """{"project":"$PROJECT","types":["task"],"heldUp":false}"""),
        )

        /** One request producing each of the four refusals, by naming a reference the core turns down. */
        val fourRefusals = mapOf(
            "nothing answers to it" to rawCall("get_item", """{"project":"$PROJECT","ref":"$NOT_THERE"}"""),
            "something else already holds it" to rawCall("get_item", """{"project":"$PROJECT","ref":"$TAKEN"}"""),
            "it would wait on itself" to rawCall("get_item", """{"project":"$PROJECT","ref":"$A_LOOP"}"""),
            "it is not a thing to ask for" to rawCall("get_item", """{"project":"$PROJECT","ref":"$NOT_ALLOWED"}"""),
        )

        /** Eight requests neither adapter can read, one for each way of getting it wrong. */
        val unreadable = mapOf(
            "contents that are not the format at all" to "this is not JSON",
            "an envelope that is not a request" to """{"method":"list_projects","id":1}""",
            "an operation nobody defined" to rawCall("teleport_item", """{"project":"$PROJECT"}"""),
            "a field the operation does not define" to
                rawCall("create_item", """{"project":"$PROJECT","type":"task","name":"A","colour":"red"}"""),
            "an argument the operation requires, left out" to rawCall("get_item", """{"project":"$PROJECT"}"""),
            "an argument holding the wrong kind of value" to
                rawCall("get_item", """{"project":"$PROJECT","ref":42}"""),
            "a project-scoped call naming no project" to
                rawCall("create_item", """{"type":"task","name":"Nowhere"}"""),
            "an instance-level call naming a project" to rawCall("list_projects", """{"project":"$PROJECT"}"""),
        )
    }
}
