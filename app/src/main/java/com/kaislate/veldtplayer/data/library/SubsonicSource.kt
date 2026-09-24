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
 * **[stableKey] is the server's own track id, not a path.** A Subsonic GUID does not move the way
 * a file does, so there is no ladder to climb — one rung, namespaced `sid:` for the same reason
 * [LocalSource.stableKey] namespaces its rungs: the key space is flat across every
 * `LibrarySource` a `sourceId` might one day name, and a bare externalId could collide with
 * whatever scheme a different implementation happens to use.
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

    override fun stableKey(song: Song): String = "$STABLE_KEY_PREFIX${song.externalId}"

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
    }
}
