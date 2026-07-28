package io.nook.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The shape every call takes and the two shapes a reply can take, which are not
 * Nook's: they are JSON-RPC 2.0's, so a caller reaches Nook with a client that
 * already exists.
 *
 * The text is asserted literally, not just round-tripped, because this shape is
 * not private: the app a person's program reaches serves it outward rather than
 * inventing a second one, so a field named here is a field every later caller
 * is written against.
 */
class RequestReplyCrossingTest {

    private fun payloadOf(command: CreateItem) =
        catalogJson.encodeToJsonElement(command).jsonObject

    @Test
    fun `a call names the operation, the project it acts inside beside its arguments, and its id`() {
        assertEquals(
            """{"jsonrpc":"2.0","method":"create_item",""" +
                """"params":{"project":"search-revamp","type":"task","name":"Add search"},"id":7}""",
            catalogJson.encodeToString(
                RpcRequestSerializer,
                RpcRequest(
                    method = "create_item",
                    params = JsonObject(
                        mapOf("project" to JsonPrimitive("search-revamp")) + payloadOf(
                            CreateItem(type = "task", name = "Add search"),
                        ),
                    ),
                    id = JsonPrimitive(7),
                ),
            ),
        )
    }

    @Test
    fun `a call and both replies survive being written out and read back`() {
        val call = RpcRequest("get_item", buildJsonObject { put("ref", "add-search") }, JsonPrimitive("a"))
        assertEquals(
            call,
            catalogJson.decodeFromString(RpcRequestSerializer, catalogJson.encodeToString(RpcRequestSerializer, call)),
        )

        val replies = listOf(
            RpcReply.Answered(JsonPrimitive(1), catalogJson.encodeToJsonElement(EmptyPayload)),
            RpcReply.Answered(JsonPrimitive(1), null),
            RpcReply.Failed(
                JsonPrimitive(1),
                StructuredError(ErrorCode.NOT_FOUND, "no item \"add-search\"", mapOf("ref" to "add-search"))
                    .asRpcError(),
            ),
            RpcReply.Failed(JsonNull, RpcError(RpcCode.INTERNAL_ERROR, NO_VERDICT)),
        )
        replies.forEach { reply ->
            val text = catalogJson.encodeToString(RpcReplySerializer, reply)
            assertEquals(reply, catalogJson.decodeFromString(RpcReplySerializer, text))
        }

        // A result is written even where the operation produced none, so that a
        // delete is plainly a success rather than something a caller has to
        // guess about.
        assertEquals(
            """{"jsonrpc":"2.0","result":null,"id":1}""",
            catalogJson.encodeToString(RpcReplySerializer, RpcReply.Answered(JsonPrimitive(1), null)),
        )
        assertEquals(
            """{"jsonrpc":"2.0","error":{"code":-32003,"message":"a loop","data":{"reason":"cycle"}},"id":1}""",
            catalogJson.encodeToString(
                RpcReplySerializer,
                RpcReply.Failed(JsonPrimitive(1), StructuredError(ErrorCode.CYCLE, "a loop").asRpcError()),
            ),
        )
    }

    @Test
    fun `the four domain failures travel under the numbers the decision records`() {
        // Spelled out here rather than read off the code being checked, so that
        // a number changing in one place is a failure rather than an agreement.
        val table = mapOf(
            ErrorCode.VALIDATION_FAILED to -32602,
            ErrorCode.NOT_FOUND to -32001,
            ErrorCode.CONFLICT to -32002,
            ErrorCode.CYCLE to -32003,
        )
        assertEquals(ErrorCode.entries.toSet(), table.keys, "the four refusals were not all accounted for")
        table.forEach { (code, number) -> assertEquals(number, code.rpcCode, code.label) }

        // And the five the specification reserves for the request itself.
        assertEquals(-32700, RpcCode.PARSE_ERROR)
        assertEquals(-32600, RpcCode.INVALID_REQUEST)
        assertEquals(-32601, RpcCode.METHOD_NOT_FOUND)
        assertEquals(-32602, RpcCode.INVALID_PARAMS)
        assertEquals(-32603, RpcCode.INTERNAL_ERROR)
    }

    @Test
    fun `a refusal's details arrive beside the reason naming the failure, and read back whole`() {
        val refusal = StructuredError(
            ErrorCode.NOT_FOUND,
            "no project matches reference \"gone\"",
            Missing.PROJECT.asDetails(),
        )
        val carried = refusal.asRpcError()

        assertEquals(
            buildJsonObject {
                put("reason", "not_found")
                put("missing", "project")
            },
            carried.data,
        )
        assertEquals(refusal, carried.asStructuredError())
        assertEquals(Missing.PROJECT, Missing.of(carried))
    }

    @Test
    fun `a failure naming no reason is not a refusal, whatever number it carries`() {
        // There is nothing in the call for its caller to correct, so it must
        // never read as though there were.
        assertNull(RpcError(RpcCode.INTERNAL_ERROR, NO_VERDICT).asStructuredError())
        assertNull(RpcError(RpcCode.METHOD_NOT_FOUND, "no such operation").asStructuredError())
        assertNull(
            RpcError(-32004, "too many", buildJsonObject { put("reason", "rate_limited") }).asStructuredError(),
        )
    }

    @Test
    fun `a field no operation defines is refused rather than ignored, wherever it appears`() {
        listOf<() -> Any>(
            { catalogJson.decodeFromString(RpcRequestSerializer, """{"jsonrpc":"2.0","method":"a","id":1,"x":1}""") },
            { catalogJson.decodeFromString<EmptyPayload>("""{"hurry":true}""") },
            { catalogJson.decodeFromString<TargetRef>("""{"ref":"add-search","hurry":true}""") },
            { catalogJson.decodeFromString<CreateProject>("""{"name":"A","colour":"red"}""") },
            { catalogJson.decodeFromString<CreateItem>("""{"type":"task","name":"A","colour":"red"}""") },
            { catalogJson.decodeFromString<CreateRelease>("""{"name":"v1","colour":"red"}""") },
            { catalogJson.decodeFromString<ItemFilter>("""{"types":["task"],"colour":"red"}""") },
            { catalogJson.decodeFromString(ItemUpdateSerializer, """{"ref":"a","colour":"red"}""") },
            { catalogJson.decodeFromString(ReleaseUpdateSerializer, """{"ref":"v1","colour":"red"}""") },
        ).forEach { undefined -> assertFailsWith<SerializationException> { undefined() } }
    }

    @Test
    fun `an envelope missing what the standard requires is not a call this connection accepts`() {
        listOf(
            "a version nobody named" to """{"method":"list_projects","id":1}""",
            "a version this does not speak" to """{"jsonrpc":"1.0","method":"list_projects","id":1}""",
            "no operation named" to """{"jsonrpc":"2.0","id":1}""",
            "no id at all, which would be a call expecting no reply" to
                """{"jsonrpc":"2.0","method":"list_projects"}""",
            "an id that pairs nothing" to """{"jsonrpc":"2.0","method":"list_projects","id":null}""",
            "arguments that are not a set of named fields" to
                """{"jsonrpc":"2.0","method":"list_projects","params":[1],"id":1}""",
            "several calls at once, which this does not serve" to """[{"jsonrpc":"2.0","method":"a","id":1}]""",
        ).forEach { (what, envelope) ->
            assertFailsWith<SerializationException>(what) {
                catalogJson.decodeFromString(RpcRequestSerializer, envelope)
            }
        }
    }

    @Test
    fun `a reply saying both how the call ended and how it did not is no reply at all`() {
        listOf(
            "neither a result nor an error" to """{"jsonrpc":"2.0","id":1}""",
            "both at once" to """{"jsonrpc":"2.0","result":null,"error":{"code":-1,"message":"x"},"id":1}""",
            "no id to pair it with" to """{"jsonrpc":"2.0","result":null}""",
            // The word the envelope this replaced needed to tell its three
            // endings apart. Nothing defines it now, so it is refused like any
            // other field nobody defined.
            "a field this envelope does not define" to """{"jsonrpc":"2.0","result":null,"id":1,"outcome":"answer"}""",
        ).forEach { (what, reply) ->
            assertFailsWith<SerializationException>(what) {
                catalogJson.decodeFromString(RpcReplySerializer, reply)
            }
        }
    }
}
