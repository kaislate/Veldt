// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import android.content.Context
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.toDomain
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import androidx.work.WorkManager
import com.kaislate.veldtplayer.data.library.scan.LibraryScanWorker
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.di.LocalLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified read API over the Room [SongDao] projection (spec §6.2). Concrete class with an
 * `@Inject` constructor — Hilt provides it directly, no `@Binds` needed. All reads map the
 * stored [com.kaislate.veldtplayer.data.library.db.SongEntity] rows to framework-free [Song].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MusicRepository internal constructor(
    private val songDao: SongDao,
    private val registry: SourceRegistry,
    private val local: LibrarySource,
    private val context: Context,
    private val excludedFolders: Flow<Set<String>>,
) {
    /**
     * The hidden folders are read from [SettingsRepository.excludedFolders]; the primary
     * constructor takes the bare flow only so a test can hand it one that COMPLETES. DataStore's
     * never does, and a repository flow built over it therefore never completes either — which
     * would take away `MusicRepositoryFolderTreeTest`'s `toList()`, the one way it has of
     * asserting that no extra emission arrived.
     */
    @Inject constructor(
        songDao: SongDao,
        registry: SourceRegistry,
        @LocalLibrary local: LibrarySource,
        @ApplicationContext context: Context,
        settings: SettingsRepository,
    ) : this(songDao, registry, local, context, settings.excludedFolders)

    /**
     * The hidden folders, compiled once per change (Step 5 spec §4).
     *
     * `distinctUntilChanged` because DataStore re-emits its whole preference map on ANY write — a
     * change of folder sort would otherwise re-filter the library and rebuild the folder tree for
     * a set of hidden folders that did not change.
     */
    private fun exclusion(): Flow<FolderExclusion> =
        excludedFolders.distinctUntilChanged().map { FolderExclusion(it, local.id) }

    /**
     * [rows] with the hidden folders' local songs taken out.
     *
     * **A view filter and nothing more**: the scan still indexes every file and the table still
     * holds every row, which is what makes showing a folder again instant and rescan-free.
     * Playlists do not read through here (`PlaylistRepository.resolve` reads the table), so hiding
     * a folder never blanks a playlist entry.
     *
     * `flatMapLatest` over the exclusion rather than `combine`, so no song-list emission is merged
     * away while the first DataStore read is in flight: every list the table emits after that read
     * reaches the collector, which is the property `MusicRepositoryFolderTreeTest`'s sequence tests
     * assert. A change of hidden folders re-subscribes the table, which re-reads it once — the
     * same cost a scan batch already has.
     */
    private fun visible(rows: Flow<List<Song>>): Flow<List<Song>> =
        exclusion().flatMapLatest { hidden ->
            if (hidden.isEmpty) rows else rows.map { all -> all.filterNot(hidden::isExcluded) }
        }

    private fun allSongs(): Flow<List<Song>> =
        songDao.observeAllSongs().map { rows -> rows.map { it.toDomain() } }

    /**
     * Observe the library, ordered by title, without the hidden folders' songs.
     *
     * Everything the tag views show is derived from here — [albums], [artists], [songsForAlbum],
     * [songsForArtist] — so a hidden folder leaves all of them at once, and an album whose every
     * track is hidden is not listed as an empty album.
     */
    fun songs(): Flow<List<Song>> = visible(allSongs())

    /** Observe a title/artist/album substring search, without the hidden folders' songs. */
    fun search(term: String): Flow<List<Song>> = visible(
        songDao.observeSearch("%${term.trim()}%").map { rows -> rows.map { it.toDomain() } },
    )

    /**
     * Albums/artists are DERIVED from the songs flow rather than queried with GROUP BY.
     * At local-library scale this is cheaper than maintaining a second query path and it
     * reuses pure, tested code. If a library ever makes this measurably slow, moving to
     * DAO aggregation is a contained change behind this interface.
     */
    fun albums(): Flow<List<Album>> = songs().map { LibraryDerivations.deriveAlbums(it) }

    fun artists(): Flow<List<Artist>> = songs().map { LibraryDerivations.deriveArtists(it) }

    /**
     * The folder tree, derived once per emission (global constraint 14).
     *
     * The derivation is heavier than [LibraryDerivations.deriveAlbums] — it splits strings and
     * builds a tree — so it happens here, once, and never per row and never per recomposition.
     *
     * **What `distinctUntilChanged()` actually buys, stated precisely because the obvious reading
     * is wrong.** It sits BEFORE the build, which is the correct side: after it, the build would
     * run first and the equality check would then deep-compare two whole trees to discover the work
     * was wasted, instead of comparing two `List<Song>`. **That placement is upheld by review, not
     * by a test.** And it is not merely a cost difference: downstream, the comparison is between two
     * `List<FolderNode>`, and **two different song lists can build EQUAL trees** — the tree groups
     * by folder and erases the global ordering the library query (ordered by title) imposes across
     * folders. The downstream form therefore SUPPRESSES emissions the upstream form lets through.
     *
     * **Reaching that needs the SAME [com.kaislate.veldtplayer.data.library.model.Song] objects in
     * the same WITHIN-folder order, differing only in cross-folder interleaving** — a narrow window,
     * and two tempting examples do not fit through it. A retag does not: [FolderNode] holds the song
     * objects, so a changed title makes the node itself unequal. Nor does reordering inside one
     * folder: [FolderTree.build] appends in list order, so within-folder order survives into the
     * tree. What is left is tie-order among rows with equal `title COLLATE NOCASE`, which SQLite
     * does not promise to be stable — narrower than any routine edit, and **not demonstrated**. The
     * generic risk above is real regardless of whether that path is; do not read a failed example as
     * refuting it. No fixture covers any of this, by decision rather than oversight — see
     * `MusicRepositoryFolderTreeTest`. Treat a proposal to move this line as unguarded. But it suppresses only **no-net-change**
     * re-emissions — a steady-state rescan upserting identical rows. It does **not** bound a first
     * scan: every upsert batch genuinely changes the row set, so the lists differ, the check passes
     * them straight through, and a 5,000-track scan arriving in ~50 batches rebuilds the whole tree
     * ~50 times with the folder tab open.
     *
     * **The bound on that burst is collector-side, and it is Task 5's `stateIn`.** A `StateFlow`
     * retains only the latest value, so a run of batches collapses to the newest tree. Deliberately
     * NOT `conflate()` here: one conflation point, and it belongs where the subscription lifecycle
     * already lives. Do not add a second.
     *
     * Constraint 14 has two halves and this flow supplies one. The build runs exactly once
     * per emission **per collector**, upstream of the UI — so no row, and no [FolderTree.find] call,
     * ever rebuilds. Surviving RECOMPOSITION is the collector's half, as above. A second concurrent
     * collector re-derives rather than sharing; if one ever appears this wants `shareIn` rather than
     * a comment.
     *
     * **Local-only (N2 Task 2).** The folder tree is a filesystem concept: a server track has no
     * path, so the library is filtered to `sourceId == `[local]`.id` before anything else runs.
     * Without it a remote song — [filePath] and [relativeKey] both null — would fall into
     * [FolderTree.build]'s `unfiled` bucket, and every account added would grow a folder tab
     * "Unfiled" pile that has nothing to do with folders at all.
     *
     * **Hidden folders are MARKED here, not removed (Step 5 spec §4).** This reads the unfiltered
     * library rather than [songs], because the Folders tab is where a hidden folder stays browsable
     * and playable, and where it is shown again. A change of hidden folders rebuilds the tree from
     * the same song list: the marks are part of each [FolderNode], so the old tree is wrong. That
     * is also why the `distinctUntilChanged()` sits INSIDE the `flatMapLatest` — still before the
     * build, but reset by a change of hidden folders, which must rebuild an unchanged song list.
     */
    fun folderTree(): Flow<List<FolderNode>> =
        exclusion().flatMapLatest { hidden ->
            allSongs().map { it.filter { song -> song.sourceId == local.id } }
                .distinctUntilChanged()
                .map { FolderTree.build(it, hidden::covers) }
        }

    /**
     * One album's tracks, in disc-then-track order. [key] is an [Album.key]; it is
     * re-normalized because normalize is idempotent on a well-formed key, so accepting a
     * stray raw display name costs nothing and saves a silent empty result.
     */
    fun songsForAlbum(key: String): Flow<List<Song>> {
        val wanted = LibraryKeys.normalize(key)
        return songs().map { all ->
            LibraryDerivations.sortAlbumTracks(all.filter { LibraryKeys.albumKey(it) == wanted })
        }
    }

    /** One artist's songs, grouped by album, tracks in disc/track order. [key] is an [Artist.key]. */
    fun songsForArtist(key: String): Flow<List<Song>> {
        val wanted = LibraryKeys.normalize(key)
        return songs().map { all ->
            LibraryDerivations.sortArtistTracks(all.filter { LibraryKeys.artistKey(it) == wanted })
        }
    }

    /** Trigger a background rescan (unique WorkManager job). Non-suspend: just enqueues. */
    fun requestScan() = LibraryScanWorker.enqueue(context)

    /**
     * True while a library scan is pending or running.
     *
     * Read from WorkManager's own record of the unique scan work rather than from a flag
     * this class sets in [requestScan], so it stays honest about scans this process did
     * not start — a scan surviving process death, or one enqueued by another entry point.
     *
     * `!state.isFinished` covers ENQUEUED, RUNNING and BLOCKED. Bundling BLOCKED in with
     * the other two is deliberate: to a caller asking "is there a scan coming?" a blocked
     * scan is still coming, and treating it as finished would show an empty library as
     * settled when it is not.
     */
    fun scanning(): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(LibraryScanWorker.UNIQUE_NAME)
            .map { infos -> infos.any { !it.state.isFinished } }
            .distinctUntilChanged()

    /**
     * The string a MediaItem should play for [song], resolved by the song's **own** source
     * (local: its `content://` uri).
     *
     * [SourceRegistry.require], not [SourceRegistry.byId]: a row that came out of the library table
     * necessarily belongs to a registered source, so an absent one is a wiring bug — a module that
     * failed to bind, not user state. Failing loudly at the play call beats handing the player a
     * uri of the wrong shape and letting it surface as an unplayable track. (A *playlist entry* is
     * the opposite case and uses `byId`: its source going away is ordinary user state.)
     */
    fun playableUri(song: Song): String =
        registry.require(song.sourceId).resolvePlayableUri(song)
}
