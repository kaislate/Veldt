// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.browse

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** [ArtFileCache], the browse-art provider's disk cache (spec §6), against a real temp dir. */
class ArtFileCacheTest {

    private val dir: File = Files.createTempDirectory("art-cache-test").toFile()

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test fun `a cover is produced once and then served from disk`() {
        val cache = ArtFileCache(dir)
        var writes = 0
        val first = cache.getOrPut("song-1") { out -> writes++; out.write(byteArrayOf(1, 2, 3)); true }!!
        val second = cache.getOrPut("song-1") { writes++; true }!!
        assertEquals(first, second)
        assertEquals(1, writes)
        assertEquals(listOf<Byte>(1, 2, 3), second.readBytes().toList())
    }

    @Test fun `no art means no file and no leftover temp file`() {
        val cache = ArtFileCache(dir)
        assertNull(cache.getOrPut("song-2") { false })
        assertFalse(cache.fileFor("song-2").exists())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun `a failing producer leaves nothing behind`() {
        val cache = ArtFileCache(dir)
        assertNull(cache.getOrPut("song-3") { error("decode failed") })
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun `the key is hashed, so a path in it never reaches the file name`() {
        val name = ArtFileCache(dir).fileFor("content://x/art/1?path=/storage/a b.mp3").name
        assertTrue(name.matches(Regex("[0-9a-f]{40}\\.jpg")))
    }

    @Test fun `trim keeps only the most recently used covers`() {
        val cache = ArtFileCache(dir, maxFiles = 2)
        val a = cache.getOrPut("a") { it.write(1); true }!!
        a.setLastModified(1_000_000L)
        val b = cache.getOrPut("b") { it.write(2); true }!!
        b.setLastModified(3_000_000L)
        val c = cache.getOrPut("c") { it.write(3); true }!!
        assertFalse(a.exists())
        assertTrue(b.exists())
        assertTrue(c.exists())
    }
}
