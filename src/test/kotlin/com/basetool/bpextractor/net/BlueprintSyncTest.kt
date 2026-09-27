package com.basetool.bpextractor.net

import com.basetool.bpextractor.model.BlueprintItem
import com.basetool.bpextractor.model.ItemRef
import com.basetool.bpextractor.model.Provenance
import com.basetool.bpextractor.net.auth.DpopKey
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The direct sync against a gateway stand-in that owns `bt-owned`, resolves names by a fixed table, and
 * refuses `bt-removed` as removed elsewhere: pull before push, add only what is missing, never remove.
 */
class BlueprintSyncTest {

    private lateinit var server: HttpServer
    private lateinit var client: ExchangeClient
    private val credentials = ExchangeCredentials("AT", DpopKey.generate())
    private val json = Json { ignoreUnknownKeys = true }

    /** Every request: method, path with query, body. */
    private val requests = CopyOnWriteArrayList<Triple<String, String, String>>()

    /** The names the stand-in's catalogue resolves, to their `bt`. */
    private val catalogue =
        mapOf(
            "Owned Helmet" to "bt-owned",
            "New Pistol" to "bt-new",
            "Tagged New Pistol" to "bt-new",
            "Removed Rifle" to "bt-removed",
        )

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/exchange/v1/me/blueprints") { ex ->
            val body = record(ex)
            if (ex.requestMethod == "GET") {
                val second = ex.requestURI.rawQuery.orEmpty().contains("cursor=")
                respond(
                    ex,
                    if (second) {
                        """{"items":[{"key":"k2","ref":{"bt":"bt-default"},"isDefault":true}],"removed":[],"nextCursor":"f1.9.9","hasMore":false}"""
                    } else {
                        """{"items":[{"key":"k1","ref":{"bt":"bt-owned","name":"Owned Helmet"},"isDefault":false}],"removed":[],"nextCursor":"s1.1.1.1","hasMore":true}"""
                    },
                )
            } else {
                respond(ex, 404, body)
            }
        }
        server.createContext("/exchange/v1/me/blueprints/changes") { ex ->
            val ops = json.parseToJsonElement(record(ex)).jsonObject.getValue("ops").jsonArray
            val results = JsonArray(
                ops.mapIndexedNotNull { index, op ->
                    val o = op.jsonObject
                    val bt = o.getValue("ref").jsonObject["bt"]?.jsonPrimitive?.content
                    if (bt == "bt-removed" && o["override"] == null) {
                        json.parseToJsonElement("""{"index":$index,"result":"rejected","reason":"REMOVED_ELSEWHERE"}""")
                    } else {
                        null
                    }
                },
            )
            val applied = ops.size - results.size
            respond(ex, 200, """{"dryRun":false,"applied":$applied,"unchanged":0,"notApplied":${results.size},"results":$results}""")
        }
        server.createContext("/exchange/v1/catalog/resolve") { ex ->
            val refs = json.parseToJsonElement(record(ex)).jsonObject.getValue("refs").jsonArray
            val results =
                refs.mapIndexed { index, ref ->
                    val name = (ref as JsonObject)["name"]?.jsonPrimitive?.content
                    when (val bt = catalogue[name]) {
                        null ->
                            if (name == "Twin Name") {
                                """{"index":$index,"status":"ambiguous","candidates":[{"bt":"a","name":"A"},{"bt":"b","name":"B"}]}"""
                            } else {
                                """{"index":$index,"status":"unmatched"}"""
                            }
                        else -> """{"index":$index,"status":"resolved","ref":{"bt":"$bt","name":"$name"}}"""
                    }
                }
            respond(ex, 200, """{"results":[${results.joinToString(",")}]}""")
        }
        server.start()
        client = ExchangeClient("http://localhost:${server.address.port}")
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun record(ex: HttpExchange): String {
        val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
        requests += Triple(ex.requestMethod, ex.requestURI.toString(), body)
        return body
    }

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun respond(ex: HttpExchange, body: String) = respond(ex, 200, body)

    private fun item(name: String, at: String = "2026-01-01T10:00:00.000Z") =
        BlueprintItem(ItemRef(name = name), at, Provenance(Provenance.LOG, at))

    @Test
    fun `it pulls every page first, then adds only what the member lacks`() {
        val report =
            BlueprintSync(client, credentials, "de").sync(
                listOf(item("Owned Helmet"), item("New Pistol"), item("Tagged New Pistol"), item("Unknown Thing"), item("Twin Name")),
            )

        assertEquals(listOf("GET", "GET", "POST", "POST"), requests.map { it.first })
        assertTrue(requests[0].second.startsWith("/exchange/v1/me/blueprints?limit=1000"))
        assertTrue(requests[1].second.contains("cursor=s1.1.1.1"))
        val push = json.parseToJsonElement(requests[3].third).jsonObject.getValue("ops").jsonArray
        assertEquals(1, push.size, "the owned product and the second name of the same product are not sent")
        val op = push.single().jsonObject
        assertEquals("add", op.getValue("op").jsonPrimitive.content)
        assertEquals("bt-new", op.getValue("ref").jsonObject.getValue("bt").jsonPrimitive.content)
        assertEquals("log", op.getValue("provenance").jsonObject.getValue("source").jsonPrimitive.content)
        assertNull(op["override"])
        assertEquals(1, report.added)
        assertEquals(1, report.alreadyOwned)
        assertEquals(listOf("Unknown Thing"), report.unmatched)
        assertEquals(listOf("Twin Name"), report.ambiguous)
        assertTrue(requests.none { it.third.contains("\"remove\"") }, "the sync never removes")
    }

    @Test
    fun `a product removed elsewhere is reported, and added only when asked`() {
        val sync = BlueprintSync(client, credentials, "de")

        val report = sync.sync(listOf(item("Removed Rifle")))

        assertEquals(0, report.added)
        assertEquals(listOf("Removed Rifle"), report.removedElsewhere.map { it.name })

        val again = sync.addRemovedElsewhere(report.removedElsewhere)

        assertEquals(1, again.added)
        val op = json.parseToJsonElement(requests.last().third).jsonObject.getValue("ops").jsonArray.single().jsonObject
        assertEquals("true", op.getValue("override").jsonPrimitive.content)
    }

    @Test
    fun `nothing new means no write at all`() {
        val report = BlueprintSync(client, credentials, "de").sync(listOf(item("Owned Helmet")))

        assertEquals(0, report.added)
        assertEquals(1, report.alreadyOwned)
        assertTrue(requests.none { it.second.startsWith("/exchange/v1/me/blueprints/changes") })
    }

    @Test
    fun `more than one batch of names is resolved in chunks of 500`() {
        val many = (1..1200).map { item("Thing $it") }

        val report = BlueprintSync(client, credentials, "de").sync(many)

        val resolves = requests.filter { it.second == "/exchange/v1/catalog/resolve" }
        assertEquals(3, resolves.size)
        assertEquals(1200, report.unmatched.size)
    }
}
