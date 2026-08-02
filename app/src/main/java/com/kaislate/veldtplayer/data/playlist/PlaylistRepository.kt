// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist

import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.toDomain
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.playlist.db.PlaylistDao
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntity
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One playlist row joined to the library song it currently points at.
 *
 * [song] is null when the entry does not resolve — the file moved, the volume is unmounted, the
 * source has not been scanned. That is a first-class state, not an error: an unresolved track is
 * still returned so the UI can render it greyed out under its imported [PlaylistEntryEntity.sourceTitle].
 * Dropping it would silently shrink the user's playlist.
 */
data class PlaylistTrack(
    val entry: PlaylistEntryEntity,
    val song: Song?,
)

/**
 * One track about to be appended to a playlist, already reduced to what the table stores.
 *
 * This exists because [PlaylistRepository.addSongs] cannot express the case an `.m3u` import
 * produces most of: a track the playlist names that **is not in the library**. That entry has no
 * [Song] to take an identity or a caption from, and it must still be stored — dropping it would
 * silently shrink the playlist the user just imported. So identity and display are passed in
 * separately rather than derived from a `Song`.
 *
 * [sourceKey] is the caller's stable identity for the track. For a resolved track that is
 * [com.kaislate.veldtplayer.data.library.LibrarySource.stableKey]; for an unresolved one it is
 * whatever durable text the import had — the playlist's own path — which is not a `stableKey` and
 * will usually match none, but can only ever match the file it names.
 *
 * [songId] is the resolution cache and is null for an unresolved entry. [sourceId] is never
 * supplied here: it comes from the repository's own source (spec §3.1.1).
 */
data class NewPlaylistEntry(
    val sourceKey: String,
    val songId: Long?,
    val title: String,
    val artist: String,
    val album: String,
)

/**
 * Playlist CRUD, ordering and re-resolution (spec §3.1).
 *
 * **The positions invariant.** Every playlist's entries carry `position = 0..n-1`, dense and
 * unique. The schema cannot enforce it — a unique index on `(playlistId, position)` would abort a
 * shift-by-one reorder halfway through — so it is this class's job on *every* write path:
 * [addSongs] appends at `max + 1`, [remove] closes the gap it leaves, [move] renumbers the whole
 * sequence. `PlaylistRepositoryTest` asserts the invariant directly, not just via reorder output.
 *
 * **The re-resolution ladder.** [resolve] matches entries to songs by `(sourceId, sourceKey)`
 * FIRST and only then falls back to the cached [PlaylistEntryEntity.songId]. This is the whole
 * rescan-survival story: the library scan deletes and reinserts `songs` rows keyed on MediaStore
 * `_ID`, so a file that moves — or a volume that remounts — comes back under a different id. An
 * entry keyed on that id alone would go permanently blank. Keyed on source identity it re-links
 * itself, and the corrected id is written back so the next resolve is a cache hit.
 *
 * That only works because `sourceKey` is [LibrarySource.stableKey], **not**
 * [LibrarySource.resolvePlayableUri] — the local playable uri embeds the MediaStore `_ID`, so
 * keying on it would make rung 1 fail in precisely the case it was written for.
 *
 * **Reads go through the Room `songs` projection, not [LibrarySource.listSongs].** The scanner's
 * tag merge lands in Room, so the library screens render tag-augmented rows; resolving against a
 * live MediaStore enumeration would show a different title for the same track on the playlist
 * screen, and would re-enumerate the whole device on every call.
 */
@Singleton
class PlaylistRepository(
    private val dao: PlaylistDao,
    private val songDao: SongDao,
    private val source: LibrarySource,
    private val now: () -> Long,
) {
    /** Hilt entry point. The clock is a seam for tests, not a graph dependency. */
    @Inject constructor(dao: PlaylistDao, songDao: SongDao, source: LibrarySource) :
        this(dao, songDao, source, System::currentTimeMillis)

    fun observe(): Flow<List<PlaylistEntity>> = dao.observePlaylists()

    fun observeEntries(playlistId: Long): Flow<List<PlaylistEntryEntity>> =
        dao.observeEntries(playlistId)

    suspend fun create(name: String): Long {
        val t = now()
        return dao.insertPlaylist(PlaylistEntity(id = 0, name = name, createdAt = t, updatedAt = t))
    }

    suspend fun rename(playlistId: Long, name: String) = dao.rename(playlistId, name, now())

    suspend fun delete(playlistId: Long) = dao.deletePlaylist(playlistId)

    /**
     * Append [songs] to the end of the playlist, in the order given.
     *
     * Duplicates are allowed and are NOT deduped: a playlist legitimately contains the same track
     * twice, and silently swallowing the second add would be the wrong surprise.
     *
     * Source identity comes from [LibrarySource.id] and [LibrarySource.stableKey] — never a
     * hardcoded `"local"` (spec §3.1.1), and never the playable uri, which embeds the MediaStore
     * id a rescan reissues. The display strings are denormalised at add time so the entry still
     * says something after the song leaves the library.
     */
    suspend fun addSongs(playlistId: Long, songs: List<Song>) = addEntries(
        playlistId,
        songs.map {
            NewPlaylistEntry(
                sourceKey = source.stableKey(it),
                songId = it.id,
                title = it.title,
                artist = it.artist,
                album = it.album,
            )
        },
    )

    /**
     * Append [entries] to the end of the playlist, in the order given, resolved or not.
     *
     * **The only append path.** [addSongs] delegates here rather than duplicating the arithmetic,
     * because the dense `0..n-1` invariant is enforced by this class and by nothing at schema level
     * — there is deliberately no unique index on `(playlistId, position)`, since one would abort a
     * shift-by-one reorder mid-transaction. A caller that reaches for [PlaylistDao.insertEntries]
     * directly is choosing its own positions and will get them wrong; that is what this method is
     * for.
     *
     * An entry with a null [NewPlaylistEntry.songId] is stored exactly like a resolved one. It is
     * the import's unmatched track, it renders greyed under its captured title, and it is a
     * first-class row: `import` returning "43 of 47" must leave 47 rows behind, not 43.
     */
    suspend fun addEntries(playlistId: Long, entries: List<NewPlaylistEntry>) {
        if (entries.isEmpty()) return
        val start = dao.maxPosition(playlistId) + 1
        dao.insertEntries(
            entries.mapIndexed { i, entry ->
                PlaylistEntryEntity(
                    id = 0,
                    playlistId = playlistId,
                    position = start + i,
                    sourceId = source.id,
                    sourceKey = entry.sourceKey,
                    songId = entry.songId,
                    sourceTitle = entry.title,
                    sourceArtist = entry.artist,
                    sourceAlbum = entry.album,
                )
            }
        )
        dao.touch(playlistId, now())
    }

    /** Remove one entry by row id and close the position gap it leaves. No-op if it is gone. */
    suspend fun remove(entryId: Long) {
        val playlistId = dao.deleteEntryAndCompact(entryId) ?: return
        dao.touch(playlistId, now())
    }

    /**
     * Move the entry at [from] to index [to], renumbering the whole sequence `0..n-1` and swapping
     * it in as one transaction — a reorder is never observable half-written.
     *
     * Row ids are carried through the replace rather than regenerated, so a drag does not
     * invalidate the entry id the UI is holding for a subsequent [remove].
     */
    suspend fun move(playlistId: Long, from: Int, to: Int) {
        val entries = dao.getEntries(playlistId)
        if (from == to || from !in entries.indices || to !in entries.indices) return
        val renumbered = PlaylistOrdering.reorder(entries, from, to)
            .mapIndexed { index, entry -> entry.copy(position = index) }
        dao.replaceEntries(playlistId, renumbered)
        dao.touch(playlistId, now())
    }

    /**
     * Join a playlist's entries to the current library.
     *
     * The ladder, in order:
     * 1. `(sourceId, sourceKey)` — the durable identity, [LibrarySource.stableKey]. A rescan
     *    changes MediaStore ids but not the source's own key, so this rung is what survives one.
     * 2. the cached [PlaylistEntryEntity.songId] — only for entries owned by this source, so a
     *    future second source's ids can never collide into a local match.
     *
     * When rung 1 hits and the cached id disagrees, the corrected id is written back.
     *
     * A stale id that resolves to nothing is deliberately left alone rather than nulled: an empty
     * library (unmounted volume, scan not yet run) would otherwise wipe every fallback in the
     * playlist on a single sweep, and there would be nothing to restore it from.
     *
     * Entries that resolve to nothing are still returned, in position order, with `song = null`.
     */
    suspend fun resolve(playlistId: Long): List<PlaylistTrack> {
        val entries = dao.getEntries(playlistId)
        if (entries.isEmpty()) return emptyList()

        // The Room projection, not source.listSongs(): these are the tag-merged rows the rest of
        // the app renders, and it is one indexed table read instead of a device-wide enumeration.
        val songs = songDao.getAllSongs().map { it.toDomain() }
        val byKey = songs.associateBy { source.stableKey(it) }
        val byId = songs.associateBy { it.id }

        val corrections = LinkedHashMap<Long, Long?>()
        val tracks = entries.map { entry ->
            val mine = entry.sourceId == source.id
            val song = when {
                !mine -> null
                else -> byKey[entry.sourceKey] ?: entry.songId?.let { byId[it] }
            }
            if (song != null && song.id != entry.songId) corrections[entry.id] = song.id
            PlaylistTrack(
                entry = if (song != null) entry.copy(songId = song.id) else entry,
                song = song,
            )
        }
        if (corrections.isNotEmpty()) dao.updateResolvedSongIds(corrections)
        return tracks
    }
}
