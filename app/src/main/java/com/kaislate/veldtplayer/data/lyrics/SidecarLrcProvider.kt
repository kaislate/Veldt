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
 * Two candidate paths are probed directly, in order — `<basename>.lrc` then `<basename>.LRC` —
 * rather than listing the directory: production behaviour is chosen for Android (where
 * `.lrc`/`.LRC` are the two spellings actually seen in the wild, spec §3), not for whatever a
 * development host's file-system semantics happen to make convenient to test. Each candidate
 * must be an existing FILE (`isFile`, so a same-named directory is skipped, not an error),
 * readable, and no larger than [MAX_BYTES] — 512 KiB is already three orders of magnitude past
 * any real lyrics file, so this is a sanity clamp against a mis-named large file, not a limit
 * anyone should ever actually hit. The read itself runs inside a blanket `catch (Throwable)`:
 * any I/O failure not already excluded by the guards above degrades to "no lyrics" rather than
 * propagating.
 */
class SidecarLrcProvider @Inject constructor() : LyricsProvider {

    override suspend fun lyricsFor(song: Song): Lyrics? {
        val filePath = song.filePath ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val audioFile = File(filePath)
                val parent = audioFile.parentFile ?: return@withContext null
                val base = audioFile.nameWithoutExtension
                val sidecar = listOf(File(parent, "$base.lrc"), File(parent, "$base.LRC"))
                    .firstOrNull { it.isFile && it.canRead() && it.length() <= MAX_BYTES }
                    ?: return@withContext null
                LrcParser.parse(sidecar.readText(Charsets.UTF_8))
            } catch (t: Throwable) {
                null
            }
        }
    }

    private companion object {
        /** See the class KDoc: a sanity clamp, not a real-world limit. */
        const val MAX_BYTES = 512 * 1024L
    }
}
