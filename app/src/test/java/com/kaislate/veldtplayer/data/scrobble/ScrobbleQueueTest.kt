// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [ScrobbleQueue] (design spec §4). Plain JVM — real temp directories throughout, same reasoning
 * as `LrclibCacheTest`: this class's whole job is file-system persistence, so the tests exercise
 * the real thing rather than a fake filesystem.
 *
 * Negative control this file is designed to catch (see the task report): dropping the
 * `takeLast(CAP)` truncation in [ScrobbleQueue.add] — an uncapped queue growing forever — reddens
 * the "cap keeps the newest 1000" test below.
 */
class ScrobbleQueueTest {

    private fun tempDir(): File = Files.createTempDirectory("scrobble-queue-test").toFile()

    private fun entry(sourceId: String = "acct-1", externalId: String = "song-1", timeMs: Long = 1_000L) =
        QueuedScrobble(sourceId, externalId, timeMs)

    @Test fun `an added entry round-trips through a second instance over the same directory`() {
        val dir = tempDir()
        try {
            ScrobbleQueue(dir).add(entry(externalId = "song-1"))
            val second = ScrobbleQueue(dir)
            assertEquals(listOf(entry(externalId = "song-1")), second.forSource("acct-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `forSource returns entries oldest first and excludes other sources`() {
        val dir = tempDir()
        try {
            val queue = ScrobbleQueue(dir)
            queue.add(entry(sourceId = "acct-1", externalId = "song-1"))
            queue.add(entry(sourceId = "acct-2", externalId = "song-x"))
            queue.add(entry(sourceId = "acct-1", externalId = "song-2"))
            assertEquals(
                listOf(entry(sourceId = "acct-1", externalId = "song-1"), entry(sourceId = "acct-1", externalId = "song-2")),
                queue.forSource("acct-1"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `remove drops exactly the matching entry and leaves the rest`() {
        val dir = tempDir()
        try {
            val queue = ScrobbleQueue(dir)
            val e1 = entry(externalId = "song-1")
            val e2 = entry(externalId = "song-2")
            queue.add(e1)
            queue.add(e2)
            queue.remove(e1)
            assertEquals(listOf(e2), queue.forSource("acct-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `purge drops only that source's entries and its auth-block`() {
        val dir = tempDir()
        try {
            val queue = ScrobbleQueue(dir)
            queue.add(entry(sourceId = "acct-1", externalId = "song-1"))
            queue.add(entry(sourceId = "acct-2", externalId = "song-x"))
            queue.setAuthBlocked("acct-1", true)

            queue.purge("acct-1")

            assertEquals("acct-1's entries must be gone", emptyList<QueuedScrobble>(), queue.forSource("acct-1"))
            assertEquals(
                "acct-2's entry must survive acct-1's purge",
                listOf(entry(sourceId = "acct-2", externalId = "song-x")),
                queue.forSource("acct-2"),
            )
            assertFalse("purge must also clear the auth-block", queue.isAuthBlocked("acct-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `isEmpty and sourcesWithEntries track additions and removal`() {
        val dir = tempDir()
        try {
            val queue = ScrobbleQueue(dir)
            assertTrue(queue.isEmpty())
            assertEquals(emptySet<String>(), queue.sourcesWithEntries())

            val e = entry(sourceId = "acct-1")
            queue.add(e)
            assertFalse(queue.isEmpty())
            assertEquals(setOf("acct-1"), queue.sourcesWithEntries())

            queue.remove(e)
            assertTrue(queue.isEmpty())
            assertEquals(emptySet<String>(), queue.sourcesWithEntries())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `setAuthBlocked toggles independently per source`() {
        val dir = tempDir()
        try {
            val queue = ScrobbleQueue(dir)
            assertFalse(queue.isAuthBlocked("acct-1"))
            queue.setAuthBlocked("acct-1", true)
            assertTrue(queue.isAuthBlocked("acct-1"))
            assertFalse("a different source must not be affected", queue.isAuthBlocked("acct-2"))
            queue.setAuthBlocked("acct-1", false)
            assertFalse(queue.isAuthBlocked("acct-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Fix round 1, fold-in (a): the auth-block must survive process death, so it has to be in the
     *  same file as the entries, not an in-memory field a fresh process starts blank. */
    @Test fun `an auth-block persists across a fresh instance over the same directory`() {
        val dir = tempDir()
        try {
            ScrobbleQueue(dir).setAuthBlocked("acct-1", true)
            assertTrue("a new instance over the same dir must see the block", ScrobbleQueue(dir).isAuthBlocked("acct-1"))

            ScrobbleQueue(dir).setAuthBlocked("acct-1", false)
            assertFalse("clearing the block must persist too", ScrobbleQueue(dir).isAuthBlocked("acct-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Fold-in (a): a corrupt or absent file must read as "not blocked", the same rule the
     *  entries side already followed — a new failure mode must not get a new default. */
    @Test fun `a corrupt or absent file reads every source as not auth-blocked`() {
        val dir = tempDir()
        try {
            assertFalse("an absent file", ScrobbleQueue(dir).isAuthBlocked("acct-1"))

            val queue = ScrobbleQueue(dir)
            queue.setAuthBlocked("acct-1", true)
            File(dir, "queue.json").writeText("{ not : valid json ]")
            assertFalse("a corrupt file", queue.isAuthBlocked("acct-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Design spec §4's cap: beyond 1000 entries the OLDEST are dropped, never the newest — this
     *  file's control target, see the class KDoc. */
    @Test fun `beyond the 1000 cap the oldest entries are dropped, keeping the newest 1000`() {
        val dir = tempDir()
        try {
            val queue = ScrobbleQueue(dir)
            repeat(1_005) { i -> queue.add(entry(sourceId = "acct-1", externalId = "song-$i")) }
            val remaining = queue.forSource("acct-1")
            assertEquals(1_000, remaining.size)
            assertEquals("song-5", remaining.first().externalId)
            assertEquals("song-1004", remaining.last().externalId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a corrupt file reads as empty, and the next add replaces it with a valid file`() {
        val dir = tempDir()
        try {
            val queue = ScrobbleQueue(dir)
            queue.add(entry())
            val file = dir.listFiles()!!.single { it.name == "queue.json" }
            file.writeText("{ not : valid json ]")

            assertEquals("a corrupt file must read as no entries", emptyList<QueuedScrobble>(), queue.forSource("acct-1"))

            queue.add(entry(externalId = "song-2"))
            assertEquals(listOf(entry(externalId = "song-2")), queue.forSource("acct-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a write leaves no temp file behind`() {
        val dir = tempDir()
        try {
            val queue = ScrobbleQueue(dir)
            queue.add(entry())
            queue.add(entry(externalId = "song-2"))
            queue.remove(entry())
            val names = dir.listFiles()!!.map { it.name }
            assertEquals("expected exactly queue.json, got $names", listOf("queue.json"), names)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a missing file reads as no entries`() {
        val dir = tempDir()
        try {
            assertEquals(emptyList<QueuedScrobble>(), ScrobbleQueue(dir).forSource("acct-1"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
