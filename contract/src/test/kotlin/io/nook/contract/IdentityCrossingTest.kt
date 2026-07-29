package io.nook.contract

import com.sun.net.httpserver.HttpServer
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement

/** One call as the core received it: the text of the request, and every header that came with it. */
private data class Received(val body: String, val headers: Map<String, List<String>>) {

    /** Header names arrive under whatever casing a client wrote them in, and are matched without it. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    val identity: Actor get() = Actor(header(SUBJECT_HEADER), header(AGENT_HEADER))
}

/**
 * The two identities crossing to the core, driven against a core that records
 * what it was actually sent rather than what a caller believed it sent.
 *
 * The subject here is the crossing and nothing else: what the request holds,
 * what rides beside it, what does not ride at all, and whether a call under load
 * is told about the person it was made for. What the core then does with an
 * identity is the write path's, and is checked where that lives.
 */
class IdentityCrossingTest {

    /**
     * The request `create_project` sent before there were identities to carry,
     * written out rather than built — so that a change to how a request is
     * shaped is caught here rather than agreed to by both sides at once.
     */
    private val theRequestAsItAlwaysWas =
        """{"jsonrpc":"2.0","method":"create_project","params":{"name":"Search revamp"},"id":1}"""

    @Test
    fun `the request is unchanged, the two identities ride beside it, and no token rides at all`() {
        recordingCore { core, caller ->
            caller.forActor(Actor(A_PERSON, AN_AGENT)).createProject(CreateProject("Search revamp"))

            val received = core.single()
            assertEquals(theRequestAsItAlwaysWas, received.body, "the request the core receives has changed")
            assertEquals(Actor(A_PERSON, AN_AGENT), received.identity)
            assertTrue(
                received.headers.values.flatten().none { it.contains("Bearer", ignoreCase = true) },
                "a header carried something looking like a caller's token: ${received.headers}",
            )
        }
    }

    @Test
    fun `a call made through a calling library that bound nothing reaches the core naming nobody`() {
        recordingCore { core, caller ->
            caller.createProject(CreateProject("Search revamp"))

            val received = core.single()
            assertEquals(theRequestAsItAlwaysWas, received.body)
            assertNull(received.header(SUBJECT_HEADER), "an unbound caller named a person")
            assertNull(received.header(AGENT_HEADER), "an unbound caller named an agent")
        }
    }

    @Test
    fun `an agent that named itself with nothing crosses as no agent at all`() {
        recordingCore { core, caller ->
            caller.forActor(Actor(A_PERSON, null)).createProject(CreateProject("Search revamp"))

            assertEquals(A_PERSON, core.single().header(SUBJECT_HEADER))
            assertNull(core.single().header(AGENT_HEADER))
        }
    }

    @Test
    fun `sixteen callers making ten calls each are every one of them told the right person`() {
        recordingCore { core, caller ->
            val callers = 16
            val each = 10
            val together = CountDownLatch(callers)
            val threads = Executors.newFixedThreadPool(callers)
            try {
                repeat(callers) { which ->
                    threads.execute {
                        val actor = Actor("person-$which", "agent-$which")
                        together.countDown()
                        together.await()
                        repeat(each) { caller.forActor(actor).createProject(CreateProject("Search revamp")) }
                    }
                }
                threads.shutdown()
                assertTrue(threads.awaitTermination(1, TimeUnit.MINUTES), "the callers did not finish")
            } finally {
                threads.shutdownNow()
            }

            assertEquals(callers * each, core.size, "the core was called a different number of times")
            val misattributed = core.filter { it.header(AGENT_HEADER) != it.header(SUBJECT_HEADER)?.replace("person", "agent") }
            assertEquals(emptyList(), misattributed, "a call was told a person and an agent that were not a pair")
            assertEquals(
                (0 until callers).map { "person-$it" }.toSet(),
                core.mapNotNull { it.header(SUBJECT_HEADER) }.toSet(),
            )
        }
    }

    @Test
    fun `binding a hundred thousand views leaves the program's threads where they were`() {
        recordingCore { _, caller ->
            val before = ManagementFactory.getThreadMXBean().threadCount
            repeat(100_000) { caller.forActor(Actor("person-$it", "agent-$it")) }
            assertEquals(
                before,
                ManagementFactory.getThreadMXBean().threadCount,
                "binding an identity cost threads, which a caller doing it per call would accumulate",
            )
        }
    }

    /**
     * A core that records every request whole, and answers each one with
     * something the calling library can read, for as long as [check] runs.
     *
     * The runtime's own web server rather than the core's, for the reason
     * [coreAnswering] gives: what this is about is the bytes on the wire, and
     * anything that read them into shapes first would be reporting its own
     * reading of them.
     */
    private fun recordingCore(check: (List<Received>, CatalogClient) -> Unit) {
        val received = CopyOnWriteArrayList<Received>()
        val server = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)
        server.executor = Executors.newFixedThreadPool(16)
        server.createContext("/") { call ->
            val body = call.requestBody.use { it.readAllBytes() }.toString(Charsets.UTF_8)
            received += Received(body, call.requestHeaders.toMap())
            val answer = answerTo(body).toByteArray(Charsets.UTF_8)
            call.sendResponseHeaders(200, answer.size.toLong())
            call.responseBody.use { it.write(answer) }
        }
        server.start()
        try {
            CatalogClient("http://$LOOPBACK:${server.address.port}").use { check(received, it) }
        } finally {
            server.stop(0)
        }
    }

    /** A project, answered to whatever call asked — every call here is `create_project`. */
    private fun answerTo(request: String): String {
        val call = catalogJson.decodeFromString(RpcRequestSerializer, request)
        val project: JsonElement = catalogJson.encodeToJsonElement(Project.serializer(), aProject())
        return catalogJson.encodeToString(RpcReplySerializer, RpcReply.Answered(call.id, project))
    }
}
