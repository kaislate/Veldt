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
 * `sidecar.length() > MAX_BYTES` clamp reddens the oversize test.
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

    @Test fun `the uppercase LRC extension variant is read`() = runTest {
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
            // so this test can only pass because of the size clamp, not because the content is
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
     * A directory whose own listing is denied must degrade to null, not throw. Simulated with a
     * real, OS-level access restriction rather than a mock: on Windows, `icacls /deny` on the
     * directory itself; on POSIX, stripping all permissions. Restored before cleanup either way
     * — see [withListingDenied].
     */
    @Test fun `an unreadable directory is null rather than throwing`() = runTest {
        val dir = tempDir()
        try {
            File(dir, "song.lrc").writeText("[00:01.00]hidden")
            withListingDenied(dir) {
                val result = SidecarLrcProvider().lyricsFor(song(File(dir, "song.mp3").path))
                assertNull(result)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Denies [dir]'s own read/list access for the duration of [action], then restores it —
     * restoration happens even if [action] throws, and happens BEFORE the caller's own
     * `deleteRecursively()`, which itself needs to list the directory.
     *
     * Windows grants the "bypass traverse checking" privilege by default, so a plain
     * `File.setReadable(false)` on a directory has no effect there (measured) — an explicit
     * `icacls /deny` ACE is what actually makes `File.listFiles()` observe the denial. POSIX
     * systems get there with an ordinary permission strip.
     */
    private suspend fun withListingDenied(dir: File, action: suspend () -> Unit) {
        val user = System.getProperty("user.name")
        val windows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
        if (windows) {
            ProcessBuilder("icacls", dir.absolutePath, "/deny", "$user:(RX)").start().waitFor()
            try {
                action()
            } finally {
                ProcessBuilder("icacls", dir.absolutePath, "/remove:d", user).start().waitFor()
            }
        } else {
            dir.setReadable(false, false)
            dir.setExecutable(false, false)
            try {
                action()
            } finally {
                dir.setReadable(true, false)
                dir.setExecutable(true, false)
            }
        }
    }
}
