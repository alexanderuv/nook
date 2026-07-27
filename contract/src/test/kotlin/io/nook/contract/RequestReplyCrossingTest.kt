package io.nook.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * The shape every call takes, and the three shapes a reply can take.
 *
 * The text is asserted literally, not just round-tripped, because this shape is
 * not private: the web app serves it outward rather than inventing a second
 * one, so a field named here is a field every later caller is written against.
 */
class RequestReplyCrossingTest {

    private fun payloadOf(command: CreateItem) =
        catalogJson.encodeToJsonElement(command).jsonObject

    @Test
    fun `a request names its operation, the project it acts inside, and its payload`() {
        assertEquals(
            """{"operation":"create_item","project":"search-revamp",""" +
                """"payload":{"type":"task","name":"Add search"}}""",
            catalogJson.encodeToString(
                CatalogRequest(
                    operation = "create_item",
                    project = "search-revamp",
                    payload = payloadOf(CreateItem(type = "task", name = "Add search")),
                ),
            ),
        )
    }

    @Test
    fun `an instance-level request carries no project, and one asking nothing carries no payload`() {
        assertEquals(
            """{"operation":"list_projects"}""",
            catalogJson.encodeToString(CatalogRequest(operation = "list_projects")),
        )
    }

    @Test
    fun `each of the three endings names itself, and survives being written out and read back`() {
        val endings = listOf(
            CatalogReply.Answer(catalogJson.encodeToJsonElement(EmptyPayload)),
            CatalogReply.Answer(),
            CatalogReply.Refusal(
                StructuredError(ErrorCode.NOT_FOUND, "no item \"add-search\"", mapOf("ref" to "add-search")),
            ),
            CatalogReply.Fault("something inside the core failed"),
        )
        endings.forEach { ending ->
            val text = catalogJson.encodeToString(CatalogReply.serializer(), ending)
            assertEquals(ending, catalogJson.decodeFromString(CatalogReply.serializer(), text))
        }

        assertEquals(
            """{"outcome":"answer"}""",
            catalogJson.encodeToString(CatalogReply.serializer(), CatalogReply.Answer()),
        )
        assertEquals(
            """{"outcome":"refusal","error":{"code":"cycle","message":"a loop"}}""",
            catalogJson.encodeToString(
                CatalogReply.serializer(),
                CatalogReply.Refusal(StructuredError(ErrorCode.CYCLE, "a loop")),
            ),
        )
        assertEquals(
            """{"outcome":"fault","message":"broken"}""",
            catalogJson.encodeToString(CatalogReply.serializer(), CatalogReply.Fault("broken")),
        )
    }

    @Test
    fun `a field no operation defines is refused rather than ignored, wherever it appears`() {
        listOf<() -> Any>(
            { catalogJson.decodeFromString<CatalogRequest>("""{"operation":"list_projects","hurry":true}""") },
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
    fun `a reply that names no ending cannot be read as one`() {
        // The discriminator is the reply's own word for what it is, so a reply
        // without it is not a reply this connection produced.
        assertFailsWith<SerializationException> {
            catalogJson.decodeFromString(CatalogReply.serializer(), """{"result":null}""")
        }
    }
}
