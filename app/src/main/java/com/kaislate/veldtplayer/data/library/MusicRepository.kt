package com.kaislate.veldtplayer.data.library

import android.content.Context
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.toDomain
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.library.scan.LibraryScanWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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

    /** Album tracks in disc-then-track order, falling back to title for untagged files. */
    fun songsForAlbum(key: String): Flow<List<Song>> = songs().map { all ->
        all.filter { LibraryKeys.normalize(it.album) == key }
            .sortedWith(
                compareBy(
                    { it.discNumber ?: 1 },
                    { it.trackNumber ?: Int.MAX_VALUE },
                    { it.title.lowercase() },
                )
            )
    }

    /** An artist's songs grouped by album, albums alphabetical, tracks in disc/track order. */
    fun songsForArtist(key: String): Flow<List<Song>> = songs().map { all ->
        all.filter { LibraryKeys.normalize(it.artist) == key }
            .sortedWith(
                compareBy(
                    { LibraryKeys.normalize(it.album) },
                    { it.discNumber ?: 1 },
                    { it.trackNumber ?: Int.MAX_VALUE },
                    { it.title.lowercase() },
                )
            )
    }

    /** Trigger a background rescan (unique WorkManager job). Non-suspend: just enqueues. */
    fun requestScan() = LibraryScanWorker.enqueue(context)

    /** The string a MediaItem should play for [song] (local: its content:// uri). */
    fun playableUri(song: Song): String = librarySource.resolvePlayableUri(song)
}
