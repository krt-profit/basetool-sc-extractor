package com.basetool.bpextractor.refinery

import com.basetool.bpextractor.net.RawHttpServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The `/api/chat` request [HttpOllamaClient] sends for a panel read. */
class HttpOllamaClientTest {

    private val answer = """{"message":{"role":"assistant","content":"| a | b |"},"done_reason":"stop"}"""

    @Test
    fun `a chat read switches thinking off and keeps sampling deterministic`() {
        RawHttpServer { _, _ -> RawHttpServer.response("200 OK", answer) }.use { server ->
            val result = HttpOllamaClient(server.baseUrl).chat("qwen3.5:9b", "read", "aW1n", 512, "10m")

            assertEquals("| a | b |", result.text)
            val body = Json.parseToJsonElement(server.received.single().body).jsonObject
            assertEquals(false, body.getValue("think").jsonPrimitive.boolean)
            assertEquals(0, body.getValue("options").jsonObject.getValue("temperature").jsonPrimitive.int)
            assertEquals("qwen3.5:9b", body.getValue("model").jsonPrimitive.content)
        }
    }
}
