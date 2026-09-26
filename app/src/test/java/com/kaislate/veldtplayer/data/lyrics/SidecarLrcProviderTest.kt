// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import com.kaislate.veldtplayer.data.library.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Plain JVM — no Android type is touched. Real temp files and directories throughout: this
 * provider's entire job is file-system lookup, so the tests exercise the actual file system
 * rather than a fake of it.
 *
 * Negative control this file is designed to catch (see the task report): dropping the
 * `sidecar.length() <= MAX_BYTES` guard reddens the oversize test.
 */
class SidecarLrcProviderTest {

    private fun song(filePath: String?) = Song(
        id = 1L,
        sourceId = "local",
        externalId = "1",
        uri = "content://media/1",
        filePath = filePath,
        relativeKey = null,
        title = "t",
        artist = "a",
        album = "al",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = 0L,
        dateModifiedSec = 0L,
        hasEmbeddedArt = false,
    )

    private fun tempDir(): File = Files.createTempDirectory("sidecar-lrc-test").toFile()

    @Test fun `an exact-basename lrc sibling is read`() = runTest {
        val dir = tempDir()
        try {
            File(dir, "song.lrc").writeText("[00:01.00]hello")
            val result = SidecarLrcProvider().lyricsFor(song(File(dir, "song.mp3").path))
            assertEquals(Lyrics.Synced(listOf(LyricLine(1000, "hello"))), result)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A sidecar with a different-case extension is still found. This does NOT prove the
     * provider's second (`.LRC`) probe is what matched — on a case-insensitive file system
     * (this test host included: Windows/NTFS), `File(parent, "song.lrc").isFile` already
     * returns true for a file actually named `song.LRC`, so the FIRST probe alone would make
     * this test pass regardless of whether a second, differently-cased probe exists at all.
     * What this test actually pins is only "some case spelling of the sidecar is found" — a
     * real, useful guarantee, just not the one its old name implied.
     */
    @Test fun `a sidecar is found even when its extension case differs from what was created`() = runTest {
        val dir = tempDir()
        try {
            File(dir, "song.LRC").writeText("[00:02.00]hi")
            val result = SidecarLrcProvider().lyricsFor(song(File(dir, "song.mp3").path))
            assertEquals(Lyrics.Synced(listOf(LyricLine(2000, "hi"))), result)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `no sibling is null`() = runTest {
        val dir = tempDir()
        try {
            val result = SidecarLrcProvider().lyricsFor(song(File(dir, "song.mp3").path))
            assertNull(result)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a null filePath is null`() = runTest {
        assertNull(SidecarLrcProvider().lyricsFor(song(null)))
    }

    @Test fun `a sibling lrc for a different basename is not picked`() = runTest {
        val dir = tempDir()
        try {
            File(dir, "other.lrc").writeText("[00:01.00]nope")
            val result = SidecarLrcProvider().lyricsFor(song(File(dir, "song.mp3").path))
            assertNull(result)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `an oversize sidecar is null rather than truncated`() = runTest {
        val dir = tempDir()
        try {
            // Comfortably past the 512 KiB clamp, and a valid single LRC line if it WERE read —
            // so this test can only pass because of the size guard, not because the content is
            // unparsable. See the class KDoc's control.
            val big = "[00:01.00]" + "a".repeat(600_000)
            File(dir, "song.lrc").writeText(big)
            val result = SidecarLrcProvider().lyricsFor(song(File(dir, "song.mp3").path))
            assertNull(result)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Platform-neutral failure case, in place of an OS-permission simulation: a real DIRECTORY
     * happens to be named `<basename>.lrc` (nothing stops that on any filesystem). `File.isFile`
     * is false for it, so the guard in [SidecarLrcProvider.lyricsFor] skips it exactly like a
     * missing candidate — no exception, no special-casing, and no platform-specific setup
     * needed to exercise it.
     */
    @Test fun `a directory named like the sidecar is null rather than throwing`() = runTest {
        val dir = tempDir()
        try {
            File(dir, "song.lrc").mkdir()
            val result = SidecarLrcProvider().lyricsFor(song(File(dir, "song.mp3").path))
            assertNull(result)
        } finally {
            dir.deleteRecursively()
        }
    }
}
