package io.nook.web

import io.nook.contract.ALEX
import io.nook.contract.RpcReply
import io.nook.contract.anItem
import io.nook.contract.presenting
import io.nook.contract.tokenFor
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Several callers at once, and one that walks away.
 *
 * Both are about the same thing from opposite sides: what one caller does must
 * not decide what happens to another's call, or to its own work once the core
 * has begun it. A call held up somewhere slow holds nothing else behind it, and
 * a caller that stops listening leaves the work it started to run to its own
 * end — because a half-applied write is the one outcome a later read cannot
 * make sense of.
 *
 * Two callers contesting one handle is a different question with a different
 * answer: the store arbitrates it, and it is checked where a store exists.
 */
class ManyAtOnceTest {

    private val doors = BothDoors()

    private val core = doors.core

    @AfterTest
    fun letGo() {
        doors.close()
    }

    @Test
    fun `a fast call is answered while a slow one is still waiting`() {
        core.patience = 3.seconds
        val order = CopyOnWriteArrayList<String>()

        val slowly = thread {
            assertIs<RpcReply.Answered>(doors.answer(rawCall("get_item", """{"project":"p","ref":"$SLOW"}""")))
            order += "the slow one"
        }
        Thread.sleep(300)
        assertIs<RpcReply.Answered>(doors.answer(rawCall("list_projects")))
        order += "the fast one"
        slowly.join()

        assertEquals(
            listOf("the fast one", "the slow one"),
            order.toList(),
            "one caller's call waited on an unrelated call of another",
        )
    }

    @Test
    fun `a caller that stops listening mid-write leaves the write to run to its own end`() {
        val begun = AtomicInteger()
        val finished = AtomicInteger()
        core.answering = {
            begun.incrementAndGet()
            Thread.sleep(WRITING.inWholeMilliseconds)
            finished.incrementAndGet()
            anItem()
        }

        val api = URI.create(doors.apiAddress)
        repeat(RUNS) { run ->
            abandonMidWrite(
                api,
                rawCall("update_item", """{"project":"p","ref":"run-$run","name":"Run $run, written"}"""),
            )
        }

        // The writes outlive their callers, so the count has to be given time to
        // catch up before it is read.
        val deadline = System.nanoTime() + SETTLING.inWholeNanoseconds
        while (finished.get() < RUNS && System.nanoTime() < deadline) Thread.sleep(20)

        assertEquals(RUNS, begun.get(), "not every abandoned call reached the core")
        assertEquals(RUNS, finished.get(), "a write stopped when its caller did")
        assertEquals(RUNS, core.invocations.size, "the core was asked more or fewer times than it was called")
    }

    /**
     * One call written straight onto a socket, which is then closed while the
     * core is still inside the write.
     *
     * A socket rather than a client, because "the caller stopped listening" has
     * to be unambiguous: a client that gives up may or may not have let go of
     * the connection, and what is being checked is what happens when it
     * certainly has.
     */
    private fun abandonMidWrite(api: URI, body: String) {
        Socket(api.host, api.port).use { socket ->
            val request = buildString {
                append("POST ${api.path} HTTP/1.1\r\n")
                append("Host: ${api.host}:${api.port}\r\n")
                append("Content-Type: application/json\r\n")
                append("Authorization: ${presenting(tokenFor(ALEX))}\r\n")
                append("Content-Length: ${body.toByteArray().size}\r\n")
                append("Connection: close\r\n\r\n")
                append(body)
            }
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            // Long enough that the core has begun, short enough that it has not
            // finished — which is what makes this an abandoned write rather
            // than one that had already landed.
            Thread.sleep(LEAVING.inWholeMilliseconds)
        }
    }

    private companion object {
        const val RUNS = 100
        val WRITING = 200.milliseconds
        val LEAVING = 50.milliseconds
        val SETTLING = 60.seconds
    }
}
