package io.nook.web

import io.nook.contract.ErrorCode
import io.nook.contract.NO_VERDICT
import io.nook.contract.REASON
import io.nook.contract.RpcCode
import io.nook.contract.RpcReply
import io.nook.contract.RpcReplySerializer
import io.nook.contract.asStructuredError
import io.nook.contract.catalogJson
import io.nook.contract.rpcCode
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How a call ends through this surface, and what a caller can tell from it.
 *
 * Two things are being kept apart. A failure the caller can act on carries the
 * core's own verdict, whole, with the name of that verdict beside its number.
 * A call that produced no verdict carries none of that — and says nothing about
 * which part of Nook gave way, because a caller can do nothing differently for
 * knowing and naming a part behind the surface makes that part a promise.
 *
 * The ending is never read off the number the reply arrives under: every reply
 * arrives under the same one, so that number decides nothing.
 */
class CallEndingTest {

    private val corePort = freePort()

    private val both = CoreAndApi(waitLimit = 2.seconds, corePort = corePort)

    private val core = both.core

    @AfterTest
    fun letGo() {
        both.close()
    }

    private fun getItem(ref: String) = rawCall("get_item", """{"project":"search-revamp","ref":"$ref"}""")

    /** What the app answered, with the record of what reached the core wiped first. */
    private fun ending(body: String): Answer {
        core.invocations.clear()
        return sentTo(both.apiAddress, body)
    }

    private fun replyOf(answered: Answer): RpcReply =
        catalogJson.decodeFromString(RpcReplySerializer, answered.said)

    /** The next call is served normally, which is what says the app is still serving. */
    private fun stillServing() {
        core.answerNormally()
        assertIs<RpcReply.Answered>(both.answer(rawCall("list_projects")), "the next call was not served normally")
    }

    @Test
    fun `every reply comes back under one number, success and failure alike`() {
        val everyEnding = listOf(
            rawCall("list_projects"),
            getItem("add-search"),
            getItem(NOT_THERE),
            getItem(TAKEN),
            getItem(A_LOOP),
            getItem(NOT_ALLOWED),
            getItem(A_DEFECT),
            "this is not JSON",
            rawCall("teleport_item"),
        )
        everyEnding.forEach { body ->
            assertEquals(200, sentTo(both.apiAddress, body).status, "an ending was left to the number: $body")
        }
    }

    @Test
    fun `each domain failure carries the core's own code, message and data unchanged`() {
        val expected = mapOf(
            NOT_THERE to (
                ErrorCode.NOT_FOUND to buildJsonObject {
                    put(REASON, "not_found")
                    put("missing", "item")
                }
                ),
            TAKEN to (ErrorCode.CONFLICT to buildJsonObject { put(REASON, "conflict") }),
            A_LOOP to (ErrorCode.CYCLE to buildJsonObject { put(REASON, "cycle") }),
            NOT_ALLOWED to (ErrorCode.VALIDATION_FAILED to buildJsonObject { put(REASON, "validation_failed") }),
        )
        assertEquals(ErrorCode.entries.toSet(), expected.values.map { it.first }.toSet(), "not all four were driven")

        expected.forEach { (ref, refusal) ->
            val (code, data) = refusal
            val failed = assertIs<RpcReply.Failed>(both.answer(getItem(ref)), ref)
            assertEquals(code.rpcCode, failed.error.code, ref)
            assertEquals(data, failed.error.data, ref)
            // The message is the core's own, word for word, rather than
            // something this surface wrote about it.
            assertTrue(failed.error.message.contains("\"$ref\""), "${failed.error.message} does not name $ref")
            assertEquals(code, failed.error.asStructuredError()?.code, ref)
        }
        stillServing()
    }

    @Test
    fun `a project that is not there and an item that is not there are told apart by the refusal itself`() {
        // Which of the things a call named was missing decides what the caller
        // does next: a missing item is a reference to correct, while a missing
        // project means everything it does afterwards fails the same way. The
        // difference rides in the failure's own data, so reading it needs no
        // guessing at wording.
        mapOf(
            "project" to rawCall("get_item", """{"project":"$GONE","ref":"add-search"}"""),
            "item" to getItem(NOT_THERE),
        ).forEach { (missing, body) ->
            val failed = assertIs<RpcReply.Failed>(both.answer(body), missing)
            assertEquals(RpcCode.NOT_FOUND, failed.error.code, missing)
            assertEquals(missing, failed.error.asStructuredError()?.details?.get("missing"), missing)
        }
        stillServing()
    }

    @Test
    fun `a defect inside the core and a core that was never started are the same reply`() {
        val defect = ending(getItem(A_DEFECT))
        assertEquals(1, core.invocations.size, "a defect did not reach the core exactly once")
        stillServing()

        both.stopCore()
        val neverStarted = ending(getItem("add-search"))

        assertEquals(defect, neverStarted, "the two failures are told apart by something the caller can see")
        listOf(defect, neverStarted).forEach { answered ->
            val failed = assertIs<RpcReply.Failed>(replyOf(answered))
            assertEquals(RpcCode.INTERNAL_ERROR, failed.error.code)
            assertNull(failed.error.data, "a call that produced no verdict carried a reason")
            assertNull(failed.error.asStructuredError(), "a call that produced no verdict read back as a refusal")
            assertEquals(NO_VERDICT, failed.error.message)
        }

        // And once the core is back, the same call succeeds — with nothing
        // restarted and no caller rebuilt.
        both.startCore()
        stillServing()
    }

    @Test
    fun `a core stopped mid-call, and one that answers nothing at all, both produce no verdict`() {
        core.patience = 30.seconds
        val outcome = AtomicReference<RpcReply>()
        core.invocations.clear()
        val calling = thread { outcome.set(both.answer(getItem(SLOW))) }
        Thread.sleep(300)
        both.stopCore()
        calling.join()

        val brokenOff = assertIs<RpcReply.Failed>(outcome.get())
        assertEquals(RpcCode.INTERNAL_ERROR, brokenOff.error.code)
        assertNull(brokenOff.error.data)
        assertEquals(NO_VERDICT, brokenOff.error.message)
        assertEquals(1, core.invocations.size, "the interrupted call did not reach the core exactly once")

        both.startCore()
        stillServing()

        // A core that answers nothing at all: the wait runs out, and the request
        // reached it once — nothing sent again on the caller's behalf.
        core.invocations.clear()
        val silent = assertIs<RpcReply.Failed>(both.answer(getItem(SILENT)))
        assertEquals(RpcCode.INTERNAL_ERROR, silent.error.code)
        assertNull(silent.error.data)
        assertEquals(NO_VERDICT, silent.error.message)
        assertEquals(1, core.invocations.size, "a call held past the limit reached the core more than once")

        stillServing()
    }

    @Test
    fun `a failure that produced no verdict names no part of Nook`() {
        val failed = assertIs<RpcReply.Failed>(both.answer(getItem(A_DEFECT)))
        listOf("core", "connection", "database", "store", "catalog", "web", "app", "defect", corePort.toString())
            .forEach {
                assertTrue(
                    !failed.error.message.contains(it, ignoreCase = true),
                    "the reply named something behind the surface: ${failed.error.message}",
                )
            }
        stillServing()
    }
}
