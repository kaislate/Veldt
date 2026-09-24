// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist

import com.kaislate.veldtplayer.data.library.LibraryKeys
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
     *    not the source's own key, so this rung is what survives one. The map also carries each
     *    song's [LibrarySource.alternateKeys] — an OLD identity [stableKey] has since moved on
     *    from — so an entry cached under a stale-but-still-findable key hits here too; see the
     *    key-upgrade note below.
     * 2. the cached [PlaylistEntryEntity.songId] — accepted only when the row it finds belongs to
     *    the entry's source, so one source's ids can never collide into another's match.
     * 3. **tag relink**, only when [LibrarySource.relinksByTags] is true for this entry's source —
     *    normalized `(title, artist, album)` (`LibraryKeys.normalize`, matching the album/artist
     *    grouping fold) against the entry's own denormalised `sourceTitle`/`sourceArtist`/
     *    `sourceAlbum`. A hit requires this source's library to hold **exactly one** song with
     *    that normalized triple; two or more is an unresolved ambiguity, never a guess — a library
     *    routinely holds two editions of one album sharing a track's tags, and picking one would
     *    corrupt the `songId` cache with no way for the user to know which edition they actually
     *    got. Skipped entirely — on both the entry side and the song side — when the tags are too
     *    thin to mean anything: `normalize` folds a missing tag to `""`, so an unknown artist and
     *    album would otherwise "match" on title alone and an all-blank entry would match any other
     *    all-blank one; see [tagKeyOf]. Off for the local source (see [LibrarySource.relinksByTags]'s
     *    KDoc): a local tag match is not independent evidence, since it comes from the same file the
     *    other two rungs already failed to relocate. On for a server source: an id reissue that ALSO
     *    moved the path leaves rungs 1 and 2 nothing to match on, and the server's own tags are
     *    catalogue data, not derived from either identity that just moved.
     *
     * An entry naming a source the [SourceRegistry] does not hold resolves to `null` at rung 0 and
     * is never written to at all — see the early return.
     *
     * Two corrections are written back, and they are deliberately not the same one:
     * - `songId`: written whenever a `song` was found and its id disagrees with the cached one.
     * - `sourceKey`: written whenever a `song` was found whose [LibrarySource.stableKey] disagrees
     *   with the entry's own key — which happens after a rung-2 or rung-3 hit (rung 1 missed
     *   entirely, so the two necessarily differ), AND after a rung-1 hit that matched only an
     *   ALTERNATE key (the "key upgrade": [LibrarySource.stableKey] moved on from the identity the
     *   entry is still cached under, so the entry is rewritten to the current one). A rung-1 hit
     *   on the CURRENT stable key writes nothing here, because that key, by construction, already
     *   equals `stableKey(song)`.
     *
     * The `songId` correction exists because a rung-2 hit is looked up *by* `entry.songId`, so
     * `song.id` always equals `entry.songId` there and only a rung-1 or rung-3 hit can disagree.
     * The `sourceKey` correction's file-MOVED case is the same as ever: the row keeps its
     * MediaStore `_ID`, the scan re-upserts it with a new location (see
     * [com.kaislate.veldtplayer.data.library.scan.ScanDiffer]), rung 1 misses on the old key, rung
     * 2 carries it — and without a write-back the entry's key stays stale FOREVER. It would then
     * hang entirely off the cached id, and the next id reissue (a remount, a MediaStore rebuild —
     * the exact case rung 1 exists for) would blank it. Writing the key restores rung 1 as the load
     * bearing rung. The key-upgrade case is the same story for a server source whose id-only key
     * predates a path becoming available.
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
     * - `sourceKey`: populated under `stableKey(song) != entry.sourceKey`, for whatever `song`
     *   rung 1, 2 or 3 found. **That inequality is what terminates it**, and it terminates it
     *   because the value compared and written — `stableKey(song)` — is a function of `song` ALONE
     *   and never of `entry.sourceKey`: whatever it computes on the repair pass it computes again
     *   on the next, finds it already stored (now `entry.sourceKey` too), and writes nothing. A
     *   `freshKey` derived from the entry's own key could oscillate; do not make one. This is also
     *   why a rung-1 hit on the CURRENT stable key never writes: `stableKey(song)` is, by
     *   construction, the very key that was just looked up, so the two can never disagree there —
     *   no separate guard is needed to keep that case quiet.
     *
     * Separately — and this is the *point* of the repair rather than its safety property — the value
     * written is `stableKey(song)` for a `song` **taken from the very map rung 1 searched**, so
     * `byKey[entry.sourceId][stableKey(song)]` is non-null by construction afterwards and rung 1
     * becomes load bearing again. That is the difference between repairing the entry and merely
     * moving its staleness somewhere new. A repair that rung 1 would still miss leaves the entry
     * hanging off its cached id exactly as before, and `resolve still quiesces when two library rows
     * collide on one key` is the test that can tell the two apart (a mutant writing an unfindable
     * key is caught there, not by the single-entry quiescence test — the guard above hides it).
     *
     * If two songs collide on one key, the LAST one inserted wins and rung 1 returns that one —
     * still a hit, so the key is not rewritten; one further `songId` correction settles it. Bounded
     * at two extra bounces, never unbounded. Note that collision is now scoped *within* a source:
     * two songs from DIFFERENT sources sharing a key string do not collide at all, which is the
     * whole reason the map is nested rather than flat. A stable key always wins a collision against
     * an alternate key of a DIFFERENT song — alternates are inserted into the per-source map first,
     * stable keys second — so a live identity can never be shadowed by a stale one still lingering
     * as somebody else's fallback.
     */
    suspend fun resolve(playlistId: Long): List<PlaylistTrack> {
        val entries = dao.getEntries(playlistId)
        if (entries.isEmpty()) return emptyList()

        // The Room projection, not source.listSongs(): these are the tag-merged rows the rest of
        // the app renders, and it is one indexed table read instead of a device-wide enumeration.
        val songs = songDao.getAllSongs().map { it.toDomain() }
        val songsBySource: Map<String, List<Song>> = songs.groupBy { it.sourceId }

        // Per-source key maps, NOT one flat map over every song. Two sources may legitimately emit
        // the SAME key string for different tracks — nothing coordinates their key spaces — and a
        // flat map silently keeps whichever came last, handing both entries the same song. That is
        // the P1.4 defect class exactly: locally correct, collapses two distinct inputs. Songs
        // whose source is not registered are dropped from the maps rather than keyed under a
        // source that cannot describe them.
        //
        // Alternates are inserted FIRST and stable keys SECOND, so a stable key always wins a
        // string collision against some other song's alternate — a live identity is never shadowed
        // by a stale one still lingering as a fallback.
        val byKey: Map<String, Map<String, Song>> = songsBySource.mapNotNull { (sid, list) ->
            val src = registry.byId(sid) ?: return@mapNotNull null
            val map = LinkedHashMap<String, Song>()
            for (song in list) for (alt in src.alternateKeys(song)) map[alt] = song
            for (song in list) map[src.stableKey(song)] = song
            sid to map
        }.toMap()
        val byId = songs.associateBy { it.id }
        // Rung 3, built only for a source that opts in — most sources' tags describe the very file
        // the other two rungs already failed on, and building this map for them would be pure
        // waste. Grouped, not associateBy: a normalized triple shared by two songs (two editions of
        // one album) must read back as an ambiguous bucket, not silently keep the last one. Songs
        // whose tags are too thin to identify anything ([tagKeyOf] returns null — see its KDoc)
        // are dropped rather than keyed, so they can never BE the match either.
        val byTagKey: Map<String, Map<List<String>, List<Song>>> = songsBySource.mapNotNull { (sid, list) ->
            val src = registry.byId(sid) ?: return@mapNotNull null
            if (!src.relinksByTags) return@mapNotNull null
            val keyed = list.mapNotNull { song ->
                tagKeyOf(song.title, song.artist, song.album)?.let { key -> key to song }
            }
            sid to keyed.groupBy({ it.first }, { it.second })
        }.toMap()

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

            // The three rungs are kept apart, not collapsed into one elvis, because the shape of
            // the KDoc's write-back reasoning depends on knowing which one answered — rung 1 alone
            // can turn out to need no key correction; rungs 2 and 3 always do.
            val byKeyHit = byKey[entry.sourceId]?.get(entry.sourceKey)
            // Rung 2 is guarded BY SOURCE. Surrogate ids now share one AUTOINCREMENT space across
            // every source, so a cached id names a real row that may belong to somebody else; it
            // may only count when the row it finds belongs to this entry's own source. Without the
            // takeIf, a stale cache resolves cross-source into a different track entirely.
            val song = byKeyHit
                ?: entry.songId?.let { byId[it] }?.takeIf { it.sourceId == entry.sourceId }
                // Rung 3: unique tag match, entry-side key built from the SAME denormalised fields
                // `addSongs`/`addEntries` cached at import time — an unresolved entry has no `Song`
                // of its own to ask. `tagKeyOf` returning null (tags too thin — see its KDoc) skips
                // rung 3 entirely for this entry, same as `byTagKey` having no entry for the source.
                ?: tagKeyOf(entry.sourceTitle, entry.sourceArtist, entry.sourceAlbum)?.let { key ->
                    byTagKey[entry.sourceId]?.get(key)?.singleOrNull()
                }

            if (song != null && song.id != entry.songId) corrections[entry.id] = song.id
            // Written whenever the song's OWN current key disagrees with what the entry is cached
            // under — true after any rung 2 or 3 hit (rung 1 missed entirely), and true after a
            // rung 1 hit that matched only an alternate key (the key upgrade). A rung 1 hit on the
            // current stable key can never disagree, by construction, so it needs no separate guard
            // to stay quiet — see the KDoc.
            val freshKey = song?.let { src.stableKey(it) }?.takeIf { it != entry.sourceKey }
            if (freshKey != null) keyCorrections[entry.id] = freshKey

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

    /**
     * Rung 3's grouping/lookup key: normalized `(title, artist, album)`, using the same fold
     * [LibraryKeys.normalize] applies for album/artist grouping — so "Poppy" / "poppy " / "POPPY"
     * are one artist here exactly as they are one artist on the artist screen.
     *
     * A `List`, not a joined string: [LibraryKeys.normalize] can fold a field to the empty string
     * (a missing tag), and a delimiter-joined key would then let `("", "a:b")` and `("a", "b")`
     * alias onto each other. A `List<String>` compares structurally with no such collision.
     *
     * **Null when the tags are too thin to identify anything**, and null is what keeps a thin
     * triple out of rung 3 on BOTH sides — a song this excludes from [byTagKey] and an entry this
     * makes [resolve] skip rung 3 for entirely. [LibraryKeys.normalize] folds a missing tag to `""`,
     * so without this guard an entry with an unknown artist and album would "relink" on title
     * alone — not a real identity check, since two completely different tracks sharing only a
     * title (a cover, a live version, a reissue's title track) would collide into one bucket and
     * either look ambiguous for the wrong reason or, worse, match uniquely and be wrong — and an
     * entry with every tag missing would match every other all-blank entry in the same source. The
     * rule: skip when the normalized title is empty, OR when the normalized artist AND album are
     * BOTH empty. Title alone is not enough either — see the first clause — because title-only
     * matching is exactly the collision this guard exists to prevent; it takes title plus at least
     * one of artist/album for the triple to mean anything.
     */
    private fun tagKeyOf(title: String, artist: String, album: String): List<String>? {
        val t = LibraryKeys.normalize(title)
        val a = LibraryKeys.normalize(artist)
        val al = LibraryKeys.normalize(album)
        if (t.isEmpty() || (a.isEmpty() && al.isEmpty())) return null
        return listOf(t, a, al)
    }
}
