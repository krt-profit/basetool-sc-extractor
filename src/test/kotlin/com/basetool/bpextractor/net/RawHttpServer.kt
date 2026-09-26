package com.basetool.bpextractor.net

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A hand-written HTTP/1.1 test server that controls the `Date` response header, which
 * [com.sun.net.httpserver.HttpServer] always overwrites. Every response closes its connection, and it
 * binds to loopback only.
 *
 * @param handler builds the complete response text for the n-th request (1-based)
 */
class RawHttpServer(private val handler: (attempt: Int, request: Request) -> String) : AutoCloseable {

    /** One received request, parsed just far enough to assert on its headers. */
    class Request(val head: String) {

        /**
         * One request header, case-insensitively.
         *
         * @param name the header name
         * @return its value, or `null` when the request did not carry it
         */
        fun header(name: String): String? =
            head.lineSequence()
                .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
    }

    private val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

    /** Every request received, in arrival order. */
    val received = CopyOnWriteArrayList<Request>()

    /** Where to point a client. `localhost`, because the clients refuse a non-loopback plain-HTTP URL. */
    val baseUrl = "http://localhost:${server.localPort}"

    init {
        Thread {
            runCatching {
                while (!server.isClosed) {
                    server.accept().use { client ->
                        val request = Request(readRequest(client.getInputStream()))
                        received += request
                        client.getOutputStream().apply {
                            write(handler(received.size, request).toByteArray())
                            flush()
                        }
                    }
                }
            }
        }
            .apply { isDaemon = true }
            .start()
    }

    override fun close() = server.close()

    /** Reads the head, then drains the declared body so the client's write always completes. */
    private fun readRequest(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte == -1) break
            head.append(byte.toInt().toChar())
        }
        val length =
            head.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull() ?: 0
        repeat(length) { if (input.read() == -1) return@repeat }
        return head.toString()
    }

    companion object {
        /**
         * A complete response whose `Date` claims the server's clock is [clockSkewSeconds] ahead of
         * this machine's — the drift the client is supposed to measure and correct for.
         *
         * @param status the status line remainder, e.g. `200 OK`
         * @param body the JSON body (may be empty)
         * @param clockSkewSeconds how far the pretend server runs ahead of local time
         * @return the raw response text
         */
        fun response(status: String, body: String, clockSkewSeconds: Long = 0): String {
            val date =
                DateTimeFormatter.RFC_1123_DATE_TIME.format(
                    ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(clockSkewSeconds),
                )
            return "HTTP/1.1 $status\r\n" +
                "Content-Type: application/json\r\n" +
                "Date: $date\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                body
        }
    }
}
