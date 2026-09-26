// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import java.io.Closeable
import java.io.InputStream
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * A one-file HTTP server for tests.
 *
 * MockWebServer would be the obvious choice and is deliberately NOT used: it is not in this
 * project's offline Gradle cache, and adding it fails the build with `No cached version ...
 * available for offline mode` (Global Constraint 4). This covers what these tests need —
 * queued canned responses, a record of what was actually requested (method, target, and body),
 * and a per-request responder — in far less code than working around the dependency would take.
 *
 * Each connection is served on its own thread and closed immediately (`Connection: close`),
 * so no keep-alive state can leak between test methods. The queue and the request lists are
 * safe for concurrent connections: a catalog fetch runs several `getAlbum` requests at once.
 */
class FakeHttpServer : Closeable {

    private val socket = ServerSocket(0)

    // Unbounded: a test may enqueue several hundred canned pages before the client makes its
    // first request (nothing is draining the queue yet), which a bounded queue would deadlock.
    private val queued = LinkedBlockingQueue<Canned>()

    @Volatile
    private var responder: ((Recorded) -> Canned?)? = null

    /** One canned HTTP response. [contentType] has no default in [enqueueBytes] on purpose:
     * a binary payload (Task 6's cover art) is never accidentally served as JSON. */
    class Canned(val status: Int, val bodyBytes: ByteArray, val contentType: String)

    /**
     * One request as it was actually received. [body] is empty for a request with no body.
     * [headers] is keyed by lowercased header name (HTTP header names are case-insensitive), so
     * a test can assert `User-Agent` without caring how a client happened to case it.
     */
    data class Recorded(
        val method: String,
        val target: String,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    )

    /** Request lines ("GET /rest/ping?... HTTP/1.1") in arrival order. Kept for existing tests. */
    val requestLines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Every request this server has received, in arrival order. */
    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    fun enqueue(body: String, status: Int = 200, contentType: String = "application/json") {
        queued.put(Canned(status, body.toByteArray(Charsets.UTF_8), contentType))
    }

    /** For a binary body (Task 6's cover art bytes). */
    fun enqueueBytes(body: ByteArray, status: Int = 200, contentType: String) {
        queued.put(Canned(status, body, contentType))
    }

    /**
     * Consulted for every request BEFORE the FIFO queue. Returning null for a request falls
     * through to [enqueue]d responses, so a test can answer one endpoint by content (e.g.
     * "whichever `getAlbum` carries `id=a1`") while everything else still drains the queue in
     * order — which is the only way to test concurrent, unordered requests at all.
     */
    fun respond(block: (Recorded) -> Canned?) {
        responder = block
    }

    /** The single query parameter [name] of the [index]th request, or null. */
    fun queryParam(index: Int, name: String): String? {
        val line = requestLines.getOrNull(index) ?: return null
        val path = line.split(' ').getOrNull(1) ?: return null
        val query = path.substringAfter('?', "")
        return query.split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=')
    }

    /** The header [name] (case-insensitive) of the [index]th request, or null. */
    fun header(index: Int, name: String): String? =
        requests.getOrNull(index)?.headers?.get(name.lowercase())

    fun start() {
        thread(isDaemon = true, name = "FakeHttpServer") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: SocketException) { return@thread }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(client: java.net.Socket) {
        client.use { sock ->
            val input = sock.getInputStream()
            val requestLine = input.readAsciiLine() ?: return@use
            requestLines.add(requestLine)

            var contentLength = 0
            val headers = mutableMapOf<String, String>()
            while (true) {
                val header = input.readAsciiLine() ?: break
                if (header.isEmpty()) break
                val colon = header.indexOf(':')
                if (colon > 0) {
                    val name = header.substring(0, colon).trim()
                    val value = header.substring(colon + 1).trim()
                    headers[name.lowercase()] = value
                    if (name.equals("Content-Length", ignoreCase = true)) {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            val bodyBytes = ByteArray(contentLength)
            var readSoFar = 0
            while (readSoFar < contentLength) {
                val n = input.read(bodyBytes, readSoFar, contentLength - readSoFar)
                if (n == -1) break
                readSoFar += n
            }
            val body = String(bodyBytes, 0, readSoFar, Charsets.UTF_8)

            val parts = requestLine.split(' ')
            val recorded = Recorded(
                method = parts.getOrElse(0) { "" },
                target = parts.getOrElse(1) { "" },
                body = body,
                headers = headers,
            )
            requests.add(recorded)

            val canned = responder?.invoke(recorded)
                ?: queued.poll()
                ?: Canned(500, """{"error":"no response queued"}""".toByteArray(Charsets.UTF_8), "application/json")
            sock.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 ${canned.status} X\r\n" +
                            "Content-Type: ${canned.contentType}\r\n" +
                            "Content-Length: ${canned.bodyBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.UTF_8)
                )
                write(canned.bodyBytes)
                flush()
            }
        }
    }

    /**
     * One line, without the trailing CRLF/LF — read byte-by-byte so it consumes exactly the
     * header bytes and nothing past the blank line. A [java.io.BufferedReader] would over-read
     * into its own buffer and silently eat the start of a POST body that follows the headers.
     */
    private fun InputStream.readAsciiLine(): String? {
        val line = StringBuilder()
        var b = read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) line.append(b.toChar())
            b = read()
        }
        return line.toString()
    }

    override fun close() {
        socket.close()
    }
}
