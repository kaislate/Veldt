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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified read API over the Room [SongDao] projection (spec §6.2). Concrete class with an
 * `@Inject` constructor — Hilt provides it directly, no `@Binds` needed. All reads map the
 * stored [com.kaislate.veldtplayer.data.library.db.SongEntity] rows to framework-free [Song].
 */
@Singleton
class MusicRepository @Inject constructor(
    private val songDao: SongDao,
    private val librarySource: LibrarySource,
    @ApplicationContext private val context: Context,
) {
    /** Observe the full library, ordered by title. */
    fun songs(): Flow<List<Song>> =
        songDao.observeAllSongs().map { rows -> rows.map { it.toDomain() } }

    /** Observe a title/artist/album substring search. */
    fun search(term: String): Flow<List<Song>> =
        songDao.observeSearch("%${term.trim()}%").map { rows -> rows.map { it.toDomain() } }

    /**
     * Albums/artists are DERIVED from the songs flow rather than queried with GROUP BY.
     * At local-library scale this is cheaper than maintaining a second query path and it
     * reuses pure, tested code. If a library ever makes this measurably slow, moving to
     * DAO aggregation is a contained change behind this interface.
     */
    fun albums(): Flow<List<Album>> = songs().map { LibraryDerivations.deriveAlbums(it) }

    fun artists(): Flow<List<Artist>> = songs().map { LibraryDerivations.deriveArtists(it) }

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

    /** The string a MediaItem should play for [song] (local: its content:// uri). */
    fun playableUri(song: Song): String = librarySource.resolvePlayableUri(song)
}
