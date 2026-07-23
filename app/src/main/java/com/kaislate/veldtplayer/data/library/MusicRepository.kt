package com.kaislate.veldtplayer.data.library

import android.content.Context
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.toDomain
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

    /** Trigger a background rescan (unique WorkManager job). Non-suspend: just enqueues. */
    fun requestScan() = LibraryScanWorker.enqueue(context)

    /** The string a MediaItem should play for [song] (local: its content:// uri). */
    fun playableUri(song: Song): String = librarySource.resolvePlayableUri(song)
}
