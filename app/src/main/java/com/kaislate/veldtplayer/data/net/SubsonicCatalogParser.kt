// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import com.kaislate.veldtplayer.data.library.DisplayNames
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.VeldtUri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads `getAlbumList2` and `getAlbum` response bodies.
 *
 * **No `@Serializable` anywhere** — same reason as [SubsonicEnvelope]: the kotlinx-serialization
 * compiler plugin is not available offline (Global Constraint 3), so every field is read by
 * navigating [kotlinx.serialization.json.JsonElement] with safe casts.
 */
object SubsonicCatalogParser {

    /**
     * The album ids of one `getAlbumList2` page, in the server's own order.
     *
     * **Navidrome omits the `album` array entirely for an empty page** rather than sending
     * `"album":[]` (measured) — so the missing-key case must read as an empty list, not as a
     * malformed response.
     */
    fun albumIds(body: JsonObject): List<String> {
        val albumList2 = body["albumList2"] as? JsonObject ?: return emptyList()
        val albums = albumList2["album"] as? JsonArray ?: return emptyList()
        return albums.mapNotNull { (it as? JsonObject)?.stringOrNull("id") }
    }

    /**
     * The songs of one `getAlbum` response, mapped onto this app's [Song] projection.
     *
     * A song with no `id` is dropped rather than kept with a blank [Song.externalId]: the pair
     * `(sourceId, externalId)` is the whole identity, and a blank half of it is not "unknown",
     * it is a collision waiting to happen the moment a second such song appears.
     */
    fun songs(body: JsonObject, sourceId: String): List<Song> {
        val album = body["album"] as? JsonObject ?: return emptyList()
        val albumArtist = DisplayNames.tagOrNull(album.stringOrNull("artist"))
        val songArray = album["song"] as? JsonArray ?: return emptyList()
        return songArray.mapNotNull { element ->
            val songObj = element as? JsonObject ?: return@mapNotNull null
            val id = songObj.stringOrNull("id") ?: return@mapNotNull null
            val durationSec = songObj.intOrNull("duration")
            Song(
                id = Song.UNSAVED,
                sourceId = sourceId,
                externalId = id,
                uri = VeldtUri.track(sourceId, id),
                filePath = null,
                // The server's own library-relative path, when it exposes one (Navidrome does).
                // This is what lets `SubsonicSource.stableKey` key a playlist entry on the FILE
                // rather than the track id — a Navidrome upgrade has been observed to reissue ids
                // for unchanged files (N2b), and the path is what survives that. Blank is treated
                // the same as absent: an empty string is not a path, and letting it through would
                // give `SubsonicSource.stableKey` an `"sp:"` key that matches nothing and shadows
                // the id fallback that would otherwise have worked.
                relativeKey = songObj.stringOrNull("path")?.takeIf { it.isNotBlank() },
                title = DisplayNames.title(songObj.stringOrNull("title")),
                artist = DisplayNames.artist(songObj.stringOrNull("artist")),
                album = DisplayNames.album(songObj.stringOrNull("album")),
                albumArtist = albumArtist,
                trackNumber = songObj.intOrNull("track"),
                discNumber = songObj.intOrNull("discNumber"),
                year = songObj.intOrNull("year"),
                durationMs = (durationSec?.toLong() ?: 0L) * 1000L,
                // A remote source has no local scan timestamp; the sync worker (Task 3) is what
                // decides whether a track changed, not this field.
                dateModifiedSec = 0L,
                // For a remote row this flag means "the server has art for it" — a non-blank
                // coverArt id, not whether this device has ever fetched or cached it.
                hasEmbeddedArt = !DisplayNames.isMissing(songObj.stringOrNull("coverArt")),
            )
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
}
