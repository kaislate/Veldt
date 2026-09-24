// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.toDomain
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.VeldtUri

/**
 * One Navidrome/OpenSubsonic account, as a [LibrarySource] (N2 Task 2 — spec §4.2, §5.2).
 *
 * [id] is the account's `AccountEntity.sourceId` — minted once at `AccountRepository.add` and
 * never derived from url+username. Everything here is scoped to that id: [listSongs] is exactly
 * [songDao]'s `sourceId = id` projection, the rows Task 3's sync worker fills. This class never
 * talks to the network itself; it only reads what a previous sync already stored, which is what
 * makes it usable offline and keeps its shape identical to [LocalSource]'s "store only songs,
 * derive the rest" contract. [listAlbums]/[listArtists] therefore derive from [listSongs] via
 * [LibraryDerivations], the same pattern [LocalSource] uses.
 *
 * **[stableKey] prefers the server's `path`, and falls back to its track id.** N2b measured a
 * Navidrome upgrade reissuing 54 of 62 track ids with every file path unchanged: a server GUID
 * does NOT hold still the way this class's Task 2 KDoc assumed it did. So there are two rungs,
 * both namespaced for the same reason [LocalSource.stableKey] namespaces its rungs — the key
 * space is flat across every `LibrarySource` a `sourceId` might one day name, and a bare
 * externalId or path could collide with whatever scheme a different implementation happens to
 * use:
 *
 * 1. `sp:` + [Song.relativeKey] — the server's own library-relative path (see
 *    [com.kaislate.veldtplayer.data.net.SubsonicCatalogParser]), when it exposes one. Navidrome
 *    does; a Subsonic server that hides `path` does not, and [alternateKeys] below is what a
 *    library on such a server falls back to.
 * 2. `sid:` + [Song.externalId] — the server's track id, used only when there is no path.
 *
 * **[alternateKeys] keeps `sid:` reachable even once [stableKey] has moved to `sp:`.** An entry
 * cached under the old `sid:` key from before this class had a path to prefer must still resolve
 * the moment the library re-syncs with one —
 * [com.kaislate.veldtplayer.data.playlist.PlaylistRepository.resolve]'s rung 1 searches both a
 * song's [stableKey] AND its [alternateKeys], and rewrites the entry to [stableKey] on that hit
 * (the "key upgrade"). Emitted only when [stableKey] actually chose the path rung; when it fell
 * back to `sid:` itself, the id IS the stable key and there is no second key to offer.
 *
 * **[relinksByTags] is `true`.** When an id churns AND its path also changes in the same sync —
 * observed alongside the id-only case — neither rung above has anything left to match on, and
 * [com.kaislate.veldtplayer.data.playlist.PlaylistRepository.resolve]'s rung 3 is the fallback:
 * unique normalized (title, artist, album) is the server's own catalogue data, not derived from
 * either identity that just moved.
 *
 * **[search] is local-only** (design spec §5.5), a deliberate choice and not a shortcut. It
 * filters [listSongs] rather than calling the server's `search3`: the synced projection is this
 * account's contribution to the library, and a network search here would surface results the
 * library screens disagree with — or simply fail while offline, where every other read still
 * works.
 */
class SubsonicSource(
    override val id: String,
    private val songDao: SongDao,
) : LibrarySource {

    override fun resolvePlayableUri(song: Song): String = VeldtUri.track(id, song.externalId)

    override fun stableKey(song: Song): String =
        song.relativeKey?.takeIf { it.isNotBlank() }?.let { "$PATH_KEY_PREFIX$it" }
            ?: "$STABLE_KEY_PREFIX${song.externalId}"

    override fun alternateKeys(song: Song): List<String> =
        if (song.relativeKey?.isNotBlank() == true) listOf("$STABLE_KEY_PREFIX${song.externalId}") else emptyList()

    override val relinksByTags: Boolean = true

    override suspend fun listSongs(): List<Song> = songDao.getBySource(id).map { it.toDomain() }

    override suspend fun listAlbums(): List<Album> = LibraryDerivations.deriveAlbums(listSongs())

    override suspend fun listArtists(): List<Artist> = LibraryDerivations.deriveArtists(listSongs())

    override suspend fun search(query: String): List<Song> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return listSongs().filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
    }

    private companion object {
        /** See the class KDoc's note on [stableKey]'s flat key space. */
        const val STABLE_KEY_PREFIX = "sid:"

        /** The path rung's namespace. See the class KDoc. */
        const val PATH_KEY_PREFIX = "sp:"
    }
}
