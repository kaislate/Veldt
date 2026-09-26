// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import com.kaislate.veldtplayer.data.library.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Lyrics from a `.lrc`/`.LRC` file sitting next to the track's own file — the local source only:
 * [Song.filePath] is null for a remote track, and null is exactly the "no lyrics" answer this
 * returns for it.
 *
 * The sidecar is found through [File.listFiles] of the track's directory rather than by
 * probing two directly-constructed [File] paths, on purpose: Windows grants "bypass traverse
 * checking" by default, so a directly-constructed `File(parent, name)` can still be opened even
 * when the directory itself has had its own read/list access denied — [File.listFiles] is the
 * operation that actually observes that denial (returning null) instead of silently succeeding
 * around it, which is what lets a genuinely unreadable directory degrade to "no lyrics" rather
 * than throw. The match against the two exact spellings happens against the names
 * [File.listFiles] actually reports, so it is unaffected by whichever of the two names is the
 * one that exists.
 *
 * A sidecar over [MAX_BYTES] is treated as absent rather than read and truncated: 512 KiB is
 * already three orders of magnitude past any real lyrics file, so this is a sanity clamp
 * against a mis-named large file, not a limit anyone should ever actually hit.
 */
class SidecarLrcProvider @Inject constructor() : LyricsProvider {

    override suspend fun lyricsFor(song: Song): Lyrics? {
        val filePath = song.filePath ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val audioFile = File(filePath)
                val parent = audioFile.parentFile ?: return@withContext null
                val base = audioFile.nameWithoutExtension
                val candidateNames = setOf("$base.lrc", "$base.LRC")
                val sidecar = parent.listFiles()
                    ?.firstOrNull { it.isFile && it.name in candidateNames }
                    ?: return@withContext null
                if (sidecar.length() > MAX_BYTES) return@withContext null
                LrcParser.parse(sidecar.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }
    }

    private companion object {
        /** See the class KDoc: a sanity clamp, not a real-world limit. */
        const val MAX_BYTES = 512 * 1024L
    }
}
