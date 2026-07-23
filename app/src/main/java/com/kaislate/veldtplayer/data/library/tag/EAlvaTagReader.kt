package com.kaislate.veldtplayer.data.library.tag

import android.util.Log
import ealvatag.audio.AudioFileIO
import ealvatag.tag.FieldKey
import ealvatag.tag.Tag
import java.io.File
import javax.inject.Inject

/**
 * eAlvaTag-backed [TagReader]. Reads the file at [filePath] with eAlvaTag 0.4.6 and,
 * on ANY failure (missing/unreadable path, parse error, unresolved tag, artwork
 * probe failure), returns the MediaStore-derived [fallback] unchanged — the
 * sanctioned graceful degradation (spec §4, §6.2). On a successful (even partial)
 * parse it returns [TagMerge.merge] so parsed values win but MediaStore fills gaps.
 *
 * Requires [ealvatag.tag.TagOptionSingleton.isAndroid] = true to have been set once
 * at process start (done in VeldtApp.onCreate) so eAlvaTag avoids AWT/Swing paths.
 */
class EAlvaTagReader @Inject constructor() : TagReader {

    override fun read(filePath: String?, fallback: TrackTags): TrackTags {
        if (filePath.isNullOrBlank()) return fallback
        val file = File(filePath)
        // Scoped storage (API 29+): many MediaStore _DATA paths are not directly
        // readable as a File. When we can't read it, degrade rather than throw.
        if (!file.canRead()) return fallback

        return try {
            // getTag() returns Guava Optional<Tag>; orNull() -> Tag?.
            val tag: Tag = AudioFileIO.read(file).tag.orNull() ?: return fallback

            // getValue(FieldKey) returns Guava Optional<String>; orNull() -> String?.
            fun str(key: FieldKey): String? =
                tag.getValue(key).orNull()?.takeIf { it.isNotBlank() }

            // Leading-digit parse: "3/12" -> 3, "2020-05" -> 2020.
            fun num(key: FieldKey): Int? =
                str(key)?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()

            val parsed = TrackTags(
                title = str(FieldKey.TITLE),
                artist = str(FieldKey.ARTIST),
                album = str(FieldKey.ALBUM),
                albumArtist = str(FieldKey.ALBUM_ARTIST),
                trackNumber = num(FieldKey.TRACK),
                discNumber = num(FieldKey.DISC_NO),
                year = num(FieldKey.YEAR),
                // getArtworkList() throws UnsupportedFieldException for some tag types.
                hasEmbeddedArt = runCatching { tag.artworkList.isNotEmpty() }.getOrDefault(false),
            )
            TagMerge.merge(parsed, fallback)
        } catch (t: Throwable) {
            // Never let a tag failure crash the scan — degrade to MediaStore values.
            Log.w(TAG, "eAlvaTag read failed for $filePath; using MediaStore fallback", t)
            fallback
        }
    }

    private companion object {
        const val TAG = "EAlvaTagReader"
    }
}
