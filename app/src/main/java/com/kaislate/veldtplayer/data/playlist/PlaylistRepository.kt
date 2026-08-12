// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist

import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.library.SourceRegistry
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
 * [songId] is the resolution cache and is null for an unresolved entry.
 *
 * [sourceId] **is** supplied here, and takes no default on purpose (Global Constraint 4). It used
 * to be filled in by the repository from its one source — a sentence that stopped being meaningful
 * when the repository gained a registry instead. Every construction site now has to say which
 * source the track belongs to, and the compiler visits each one; a default would let a call site
 * quietly inherit whichever source happened to be first.
 */
data class NewPlaylistEntry(
    val sourceId: String,
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
    private val registry: SourceRegistry,
    private val now: () -> Long,
) {
    /** Hilt entry point. The clock is a seam for tests, not a graph dependency. */
    @Inject constructor(dao: PlaylistDao, songDao: SongDao, registry: SourceRegistry) :
        this(dao, songDao, registry, System::currentTimeMillis)

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
     * Source identity comes from **each song's own** source — `registry.require(it.sourceId)` — and
     * never from a hardcoded `local` (spec §3.1.1), never from a single ambient source, and never
     * from the playable uri, which embeds the MediaStore id a rescan reissues. One `addSongs` call
     * may legitimately carry songs from two sources (a search result spanning both), so reading the
     * source from anywhere but the song is a defect the moment a second source exists.
     *
     * [SourceRegistry.require] rather than `byId`: these songs came out of the library, so an
     * unregistered source is a wiring bug and not the removed-account state a playlist *entry* can
     * legitimately be in.
     *
     * The display strings are denormalised at add time so the entry still says something after the
     * song leaves the library.
     */
    suspend fun addSongs(playlistId: Long, songs: List<Song>) = addEntries(
        playlistId,
        songs.map {
            val src = registry.require(it.sourceId)
            NewPlaylistEntry(
                sourceId = src.id,
                sourceKey = src.stableKey(it),
                // NEVER cache Song.UNSAVED. A `0` here stops being a sentinel and starts claiming
                // to be a real id, pinning the entry to a row Room can never issue — a permanently
                // dead cache the self-healing ladder then has no reason to repair. Decided here,
                // inside the tested function, not at the call sites (Global Constraint 10).
                songId = it.id.takeUnless { id -> id == Song.UNSAVED },
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
                    sourceId = entry.sourceId,
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
     * 1. `(sourceId, sourceKey)` — the durable identity, [LibrarySource.stableKey] **of the entry's
     *    own source**, looked up in that source's own key map. A rescan changes MediaStore ids but
     *    not the source's own key, so this rung is what survives one.
     * 2. the cached [PlaylistEntryEntity.songId] — accepted only when the row it finds belongs to
     *    the entry's source, so one source's ids can never collide into another's match.
     *
     * An entry naming a source the [SourceRegistry] does not hold resolves to `null` at rung 0 and
     * is never written to at all — see the early return.
     *
     * Two corrections are written back, and they are deliberately not the same one:
     * - rung 1 hit, cached id disagrees ⇒ write the corrected `songId`.
     * - rung 1 **missed** and rung 2 hit ⇒ write a fresh `sourceKey` = [LibrarySource.stableKey] of
     *   the song the id found.
     *
     * The second exists because a rung-2 hit is looked up *by* `entry.songId`, so `song.id` always
     * equals `entry.songId` and the first correction can never fire for it. That is the file-MOVED
     * case: the row keeps its MediaStore `_ID`, the scan re-upserts it with a new location (see
     * [com.kaislate.veldtplayer.data.library.scan.ScanDiffer]), rung 1 misses on the old key, rung 2
     * carries it — and without a write-back the entry's key stays stale FOREVER. It would then hang
     * entirely off the cached id, and the next id reissue (a remount, a MediaStore rebuild — the
     * exact case rung 1 exists for) would blank it. Writing the key restores rung 1 as the load
     * bearing rung.
     *
     * A stale id that resolves to nothing is deliberately left alone rather than nulled: an empty
     * library (unmounted volume, scan not yet run) would otherwise wipe every fallback in the
     * playlist on a single sweep, and there would be nothing to restore it from.
     *
     * Entries that resolve to nothing are still returned, in position order, with `song = null`.
     *
     * **This method writes, and its one UI consumer re-enters it on its own writes.**
     * `PlaylistViewModel` calls it from a `mapLatest` over a flow that includes `observeEntries`,
     * so a write-back re-triggers the very flow that called it. Widening a guard — writing back
     * unconditionally, or touching `updatedAt` here — turns the same call site into an endless
     * re-resolve. **Both** write conditions are therefore self-extinguishing against an unchanged
     * `songs` table, and `PlaylistRepositoryTest` counts the writes rather than trusting this prose:
     *
     * - `songId`: populated under `song.id != entry.songId`. The pass that writes it makes the ids
     *   agree, so the next pass writes nothing.
     * - `sourceKey`: populated under "rung 1 missed and rung 2 hit", and guarded on
     *   `freshKey != entry.sourceKey`. **That guard is what terminates it**, and it terminates it
     *   because `freshKey` is a function of `song` ALONE and never of `entry.sourceKey`: whatever it
     *   computes on the repair pass it computes again on the next, finds it already stored, and
     *   writes nothing. A `freshKey` derived from the entry's own key could oscillate; do not make
     *   one.
     *
     * Separately — and this is the *point* of the repair rather than its safety property — the value
     * written is `stableKey(song)` for a `song` **taken from the very map rung 1 searched**, so
     * `byKey[entry.sourceKey]` is non-null by construction afterwards and rung 1 becomes load
     * bearing again. That is the difference between repairing the entry and merely moving its
     * staleness somewhere new. A repair that rung 1 would still miss leaves the entry hanging off
     * its cached id exactly as before, and `resolve still quiesces when two library rows collide on
     * one key` is the test that can tell the two apart (a mutant writing an unfindable key is caught
     * there, not by the single-entry quiescence test — the guard above hides it).
     *
     * If two songs collide on one key, `associateBy` keeps the last and rung 1 returns that one —
     * still a hit, so the key is not rewritten; one further `songId` correction settles it. Bounded
     * at two extra bounces, never unbounded. Note that collision is now scoped *within* a source:
     * two songs from DIFFERENT sources sharing a key string do not collide at all, which is the
     * whole reason the map is nested rather than flat.
     */
    suspend fun resolve(playlistId: Long): List<PlaylistTrack> {
        val entries = dao.getEntries(playlistId)
        if (entries.isEmpty()) return emptyList()

        // The Room projection, not source.listSongs(): these are the tag-merged rows the rest of
        // the app renders, and it is one indexed table read instead of a device-wide enumeration.
        val songs = songDao.getAllSongs().map { it.toDomain() }
        // Per-source key maps, NOT one flat associateBy over every song. Two sources may
        // legitimately emit the SAME key string for different tracks — nothing coordinates their
        // key spaces — and a flat map silently keeps whichever came last, handing both entries the
        // same song. That is the P1.4 defect class exactly: locally correct, collapses two distinct
        // inputs. Songs whose source is not registered are dropped from the maps rather than keyed
        // under a source that cannot describe them.
        val byKey: Map<String, Map<String, Song>> = songs.groupBy { it.sourceId }
            .mapNotNull { (sid, list) ->
                val src = registry.byId(sid) ?: return@mapNotNull null
                sid to list.associateBy { src.stableKey(it) }
            }.toMap()
        val byId = songs.associateBy { it.id }

        val corrections = LinkedHashMap<Long, Long?>()
        val keyCorrections = LinkedHashMap<Long, String>()
        val tracks = entries.map { entry ->
            // An entry whose source is not registered — the account was removed, the module is
            // absent — is a first-class unresolved row, NOT an error. It renders greyed and is
            // NEVER rewritten: there is no source to compute a fresh key with, and writing anything
            // would destroy the identity the user needs back if they re-add the source
            // (spec §4.3, §5.2). Returning early is what guarantees the zero writes.
            val src = registry.byId(entry.sourceId)
                ?: return@map PlaylistTrack(entry = entry, song = null)

            // Rung 1 and rung 2 are kept apart, not collapsed into one elvis, because WHICH rung
            // answered is itself the signal: only a rung-2-after-rung-1-missed hit means the key
            // went stale under a preserved id.
            val byKeyHit = byKey[entry.sourceId]?.get(entry.sourceKey)
            // Rung 2 is guarded BY SOURCE. Surrogate ids now share one AUTOINCREMENT space across
            // every source, so a cached id names a real row that may belong to somebody else; it
            // may only count when the row it finds belongs to this entry's own source. Without the
            // takeIf, a stale cache resolves cross-source into a different track entirely.
            val song = byKeyHit
                ?: entry.songId?.let { byId[it] }?.takeIf { it.sourceId == entry.sourceId }

            if (song != null && song.id != entry.songId) corrections[entry.id] = song.id
            // The file moved but kept its id. Two things are load bearing here and they are NOT
            // the same thing (see the KDoc): the inequality guard is what makes this terminate,
            // and `stableKey(song)` — a key of a row already in `byKey` — is what makes rung 1 hit
            // again afterwards instead of the entry staying pinned to its cached id.
            val freshKey = if (byKeyHit == null && song != null) src.stableKey(song) else null
            if (freshKey != null && freshKey != entry.sourceKey) keyCorrections[entry.id] = freshKey

            PlaylistTrack(
                entry = entry.copy(
                    songId = song?.id ?: entry.songId,
                    sourceKey = freshKey ?: entry.sourceKey,
                ),
                song = song,
            )
        }
        if (corrections.isNotEmpty()) dao.updateResolvedSongIds(corrections)
        if (keyCorrections.isNotEmpty()) dao.updateResolvedSourceKeys(keyCorrections)
        return tracks
    }
}
