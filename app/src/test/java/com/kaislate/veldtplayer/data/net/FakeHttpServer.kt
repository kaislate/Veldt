// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import java.io.Closeable
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

/**
 * A one-file HTTP server for tests.
 *
 * MockWebServer would be the obvious choice and is deliberately NOT used: it is not in this
 * project's offline Gradle cache, and adding it fails the build with `No cached version ...
 * available for offline mode` (Global Constraint 4). This covers what these tests need —
 * queued canned responses and a record of what was actually requested — in far less code
 * than working around the dependency would take.
 *
 * Each connection is served on its own thread and closed immediately (`Connection: close`),
 * so no keep-alive state can leak between test methods.
 */
class FakeHttpServer : Closeable {

    private val socket = ServerSocket(0)
    private val queued = ArrayBlockingQueue<Canned>(32)

    /** Request lines ("GET /rest/ping?... HTTP/1.1") in arrival order. */
    val requestLines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    private data class Canned(val status: Int, val body: String, val contentType: String)

    fun enqueue(body: String, status: Int = 200, contentType: String = "application/json") {
        queued.put(Canned(status, body, contentType))
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

    fun start() {
        thread(isDaemon = true, name = "FakeHttpServer") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: SocketException) { return@thread }
                thread(isDaemon = true) {
                    client.use { sock ->
                        val reader = sock.getInputStream().bufferedReader()
                        val requestLine = reader.readLine() ?: return@use
                        requestLines.add(requestLine)
                        // Drain headers. The client sends no body for these GETs.
                        while (true) {
                            val header = reader.readLine()
                            if (header.isNullOrEmpty()) break
                        }
                        val canned = queued.poll()
                            ?: Canned(500, """{"error":"no response queued"}""", "application/json")
                        val payload = canned.body.toByteArray(Charsets.UTF_8)
                        sock.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 ${canned.status} X\r\n" +
                                        "Content-Type: ${canned.contentType}\r\n" +
                                        "Content-Length: ${payload.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray(Charsets.UTF_8)
                            )
                            write(payload)
                            flush()
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        socket.close()
    }
}
