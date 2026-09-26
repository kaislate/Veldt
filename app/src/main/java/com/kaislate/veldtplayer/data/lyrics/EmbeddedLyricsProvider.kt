// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import com.kaislate.veldtplayer.data.library.model.Song
import ealvatag.audio.AudioFileIO
import ealvatag.tag.FieldKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Lyrics embedded in the track's own tag — `USLT` (ID3/MP3), Vorbis `LYRICS` (FLAC/Ogg), `©lyr`
 * (M4A) — read through eAlvaTag's single [FieldKey.LYRICS] field, which already normalises the
 * format-specific frame/field name away. The tag text is itself run through [LrcParser]: many
 * taggers write full LRC — timestamps and all — into this exact field, not just plain text.
 *
 * Local source only: [Song.filePath] is null for a remote track.
 *
 * Requires [ealvatag.tag.TagOptionSingleton.isAndroid] = true to have been set once at process
 * start (`VeldtApp.onCreate`) so eAlvaTag avoids AWT/Swing paths that do not exist on a real
 * device — see [com.kaislate.veldtplayer.data.library.tag.EAlvaTagReader], whose `catch
 * (Throwable)` guard this copies verbatim: eAlvaTag can throw on a malformed or unsupported
 * file, and a lyrics lookup must degrade to "no lyrics" exactly like a tag read does, never
 * crash the caller.
 */
class EmbeddedLyricsProvider @Inject constructor() : LyricsProvider {

    override suspend fun lyricsFor(song: Song): Lyrics? {
        val filePath = song.filePath ?: return null
        return withContext(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.canRead()) return@withContext null
            try {
                val tag = AudioFileIO.read(file).tag.orNull() ?: return@withContext null
                val text = tag.getValue(FieldKey.LYRICS).orNull()?.takeIf { it.isNotBlank() }
                    ?: return@withContext null
                LrcParser.parse(text)
            } catch (t: Throwable) {
                // Never let a tag failure crash the caller — degrade to "no lyrics", exactly
                // like EAlvaTagReader degrades to its MediaStore fallback.
                null
            }
        }
    }
}
