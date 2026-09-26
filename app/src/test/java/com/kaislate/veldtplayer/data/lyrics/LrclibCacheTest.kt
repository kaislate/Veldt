// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Plain JVM — pure file-system reads/writes and JSON, no Android type touched. Real temp
 * directories throughout, same reasoning as `SidecarLrcProviderTest`: this class's whole job is
 * file-system persistence, so the tests exercise the real thing.
 *
 * Negative control this file is designed to catch (see the task report): removing the `kind ==
 * "none"` age check in [LrclibCache.read] reddens the "8 days old" test below by returning
 * [LrclibAnswer.NotFound] forever instead of null past the 7-day window.
 */
class LrclibCacheTest {

    private fun tempDir(): File = Files.createTempDirectory("lrclib-cache-test").toFile()

    private fun cache(dir: File, at: Long = 1_000_000L): LrclibCache = LrclibCache(dir) { at }

    @Test fun `a Found synced answer round-trips`() {
        val dir = tempDir()
        try {
            val lyrics = Lyrics.Synced(listOf(LyricLine(1000, "hello"), LyricLine(2000, "world")))
            val cache = cache(dir)
            cache.write("k1", LrclibAnswer.Found(lyrics))
            assertEquals(LrclibAnswer.Found(lyrics), cache.read("k1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a Found plain answer round-trips`() {
        val dir = tempDir()
        try {
            val lyrics = Lyrics.Plain("some plain text\nsecond line")
            val cache = cache(dir)
            cache.write("k1", LrclibAnswer.Found(lyrics))
            assertEquals(LrclibAnswer.Found(lyrics), cache.read("k1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a fresh NotFound entry reads back as NotFound`() {
        val dir = tempDir()
        try {
            val writeTime = 1_000_000L
            cache(dir, writeTime).write("k1", LrclibAnswer.NotFound)
            val sixDaysLater = LrclibCache(dir) { writeTime + 6 * DAY_MS }
            assertEquals(LrclibAnswer.NotFound, sixDaysLater.read("k1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a NotFound entry 8 days old reads as null`() {
        val dir = tempDir()
        try {
            val writeTime = 1_000_000L
            cache(dir, writeTime).write("k1", LrclibAnswer.NotFound)
            val eightDaysLater = LrclibCache(dir) { writeTime + 8 * DAY_MS }
            assertNull(eightDaysLater.read("k1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a Failed answer is never written to disk`() {
        val dir = tempDir()
        try {
            cache(dir).write("k1", LrclibAnswer.Failed)
            assertNull(cache(dir).read("k1"))
            assertEquals(0, dir.listFiles()?.size ?: 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a missing entry reads as null`() {
        val dir = tempDir()
        try {
            assertNull(cache(dir).read("nope"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a corrupt file reads as null and is deleted`() {
        val dir = tempDir()
        try {
            val cache = cache(dir)
            cache.write("k1", LrclibAnswer.Found(Lyrics.Plain("x")))
            val file = dir.listFiles()!!.single()
            file.writeText("{ not : valid json ]")
            assertNull(cache.read("k1"))
            assertFalse("the corrupt file should have been deleted", file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `different keys are cached under different files`() {
        val dir = tempDir()
        try {
            val cache = cache(dir)
            cache.write("k1", LrclibAnswer.Found(Lyrics.Plain("one")))
            cache.write("k2", LrclibAnswer.Found(Lyrics.Plain("two")))
            assertEquals(2, dir.listFiles()?.size)
            assertEquals(LrclibAnswer.Found(Lyrics.Plain("one")), cache.read("k1"))
            assertEquals(LrclibAnswer.Found(Lyrics.Plain("two")), cache.read("k2"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
