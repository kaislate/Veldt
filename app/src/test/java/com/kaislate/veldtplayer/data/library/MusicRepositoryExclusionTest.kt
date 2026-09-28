// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.db.IndexEntry
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.SongEntity
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hiding a folder, as the library's readers see it (Step 5 spec §4): every tag view loses the
 * folder's songs, the Folders tab keeps them and marks them, a server's songs are untouched, and
 * showing the folder again brings everything back with the same table — no rescan.
 *
 * The fake table never changes during a test. Everything that moves here moves because the stored
 * set of hidden folders did, which is the "view filter, not a scan setting" claim made observable.
 *
 * Robolectric for the real DataStore behind [SettingsRepository]. That store is shared by every
 * test in the JVM, so it is emptied before AND after each test here — a folder left hidden would
 * silently filter another suite's library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicRepositoryExclusionTest {

    private class FakeSongDao(private val rows: List<SongEntity>) : SongDao {
        override fun observeAllSongs(): Flow<List<SongEntity>> = flowOf(rows)
        // The SQL LIKE is not under test; the filter applied to whatever it returns is.
        override fun observeSearch(pattern: String): Flow<List<SongEntity>> = flowOf(rows)
        override suspend fun findIdBySourceKey(sourceId: String, externalId: String): Long? = null
        override suspend fun insertReplacing(row: SongEntity) = Unit
        override suspend fun getAllSongs(): List<SongEntity> = rows
        override suspend fun getSongsByAlbum(album: String): List<SongEntity> = emptyList()
        override suspend fun getSongsByArtist(artist: String): List<SongEntity> = emptyList()
        override suspend fun search(pattern: String): List<SongEntity> = emptyList()
        override suspend fun getIndex(sourceId: String): List<IndexEntry> = emptyList()
        override suspend fun deleteByExternalIds(sourceId: String, externalIds: List<String>) = Unit
        override suspend fun getBySource(sourceId: String): List<SongEntity> = emptyList()
        override suspend fun deleteBySource(sourceId: String) = Unit
        override suspend fun accountRowCount(sourceId: String) = 1
        override suspend fun clear() = Unit
    }

    private val localSource = object : LibrarySource {
        override val id = "local-test"
        override suspend fun listSongs() = emptyList<Song>()
        override suspend fun listAlbums() = emptyList<Album>()
        override suspend fun listArtists() = emptyList<Artist>()
        override suspend fun search(query: String) = emptyList<Song>()
        override fun resolvePlayableUri(song: Song) = song.uri
        override fun stableKey(song: Song) = song.uri
    }

    private lateinit var settings: SettingsRepository

    @Before fun setUp() {
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        runBlocking { settings.clearForTest() }
    }

    @After fun tearDown() {
        runBlocking { settings.clearForTest() }
    }

    private var nextId = 1L

    private fun row(
        title: String,
        relativeKey: String?,
        album: String,
        artist: String,
        sourceId: String = "local-test",
    ) = SongEntity(
        id = nextId++, sourceId = sourceId, externalId = "e$nextId", uri = "content://x",
        filePath = null, relativeKey = relativeKey,
        title = title, artist = artist, album = album, albumArtist = null,
        trackNumber = null, discNumber = null, year = null,
        durationMs = 0L, dateModifiedSec = 0L, hasEmbeddedArt = false,
    )

    /**
     * The owner's card: a working directory of drafts beside a real album. `Downloads Old` shares
     * the hidden folder's prefix, so a string-prefix match would take it too. The server song's
     * path parses to the hidden folder exactly, so only the source check keeps it.
     */
    private val library = listOf(
        row("Draft 1", "1234-5678:BACKUP/Downloads/d1.mp3", album = "Drafts", artist = "Me"),
        row("Draft 2", "1234-5678:BACKUP/Downloads/Sub/d2.mp3", album = "Drafts", artist = "Me"),
        row("Old", "1234-5678:BACKUP/Downloads Old/o.mp3", album = "Olds", artist = "Me"),
        row("Song", "external_primary:Music/Band/s.mp3", album = "Record", artist = "Band"),
        row(
            "Streamed", "1234-5678:BACKUP/Downloads/r.mp3", album = "Drafts", artist = "Me",
            sourceId = "navidrome-1",
        ),
        row("Nowhere", null, album = "Record", artist = "Band"),
    )

    private fun repo() = MusicRepository(
        FakeSongDao(library),
        SourceRegistry(emptySet()),
        localSource,
        ApplicationProvider.getApplicationContext(),
        settings,
    )

    /** Everything the tag views and the folder tab read, by name, in one value. */
    private suspend fun MusicRepository.views(): Map<String, Any> {
        val tree = folderTree().first()
        val marks = LinkedHashMap<String, Boolean>()
        fun walk(node: FolderNode) {
            marks[node.key] = node.hidden
            node.children.forEach(::walk)
        }
        tree.forEach(::walk)
        fun deep(node: FolderNode): List<String> =
            node.songs.map { it.title } + node.children.flatMap(::deep)
        return linkedMapOf(
            "songs" to songs().first().map { it.title }.sorted(),
            "search" to search("x").first().map { it.title }.sorted(),
            "albums" to albums().first().map { it.name to it.songCount }.sortedBy { it.first },
            "artists" to artists().first().map { it.name }.sorted(),
            "drafts album" to songsForAlbum(albums().first().single { it.name == "Drafts" }.key)
                .first().map { it.title }.sorted(),
            "me artist" to songsForArtist("Me").first().map { it.title }.sorted(),
            "folder songs" to tree.flatMap(::deep).sorted(),
            "hidden folders" to marks.filterValues { it }.keys.toList(),
        )
    }

    private val everything = linkedMapOf<String, Any>(
        "songs" to listOf("Draft 1", "Draft 2", "Nowhere", "Old", "Song", "Streamed"),
        "search" to listOf("Draft 1", "Draft 2", "Nowhere", "Old", "Song", "Streamed"),
        "albums" to listOf("Drafts" to 3, "Olds" to 1, "Record" to 2),
        "artists" to listOf("Band", "Me"),
        "drafts album" to listOf("Draft 1", "Draft 2", "Streamed"),
        "me artist" to listOf("Draft 1", "Draft 2", "Old", "Streamed"),
        // Local only — the Streamed song never had a folder.
        "folder songs" to listOf("Draft 1", "Draft 2", "Nowhere", "Old", "Song"),
        "hidden folders" to emptyList<String>(),
    )

    @Test fun `with nothing hidden, every view holds everything`() = runTest {
        assertEquals(everything, repo().views())
    }

    /**
     * The server tab's library (Task B §5): only the named sources' songs, and an empty set is
     * nothing rather than everything. Hiding the folder the server song's path parses into leaves
     * it in place, because the filter reads through [MusicRepository.songs] and inherits its
     * local-only exclusion rule.
     */
    @Test fun `songsFrom returns only the named sources' songs`() = runTest {
        val repo = repo()
        settings.setFolderHidden("1234-5678:BACKUP/Downloads", hidden = true)
        assertEquals(
            listOf(listOf("Streamed"), emptyList(), listOf("Nowhere", "Old", "Song", "Streamed")),
            listOf(
                repo.songsFrom(setOf("navidrome-1")).first().map { it.title },
                repo.songsFrom(emptySet()).first().map { it.title },
                repo.songsFrom(setOf("navidrome-1", "local-test")).first().map { it.title }.sorted(),
            ),
        )
    }

    /**
     * The whole claim, one assertion: the drafts leave songs, search, albums, artists and both
     * detail flows — including the subfolder's draft — while `Downloads Old`, the server's song and
     * the unlocated song stay. The folder tab still holds all five local songs and marks the hidden
     * folder and its child, and nothing else.
     */
    @Test fun `hiding a folder removes its songs from every tag view and only marks it in folders`() =
        runTest {
            val repo = repo()
            settings.setFolderHidden("1234-5678:BACKUP/Downloads", hidden = true)
            assertEquals(
                linkedMapOf<String, Any>(
                    "songs" to listOf("Nowhere", "Old", "Song", "Streamed"),
                    "search" to listOf("Nowhere", "Old", "Song", "Streamed"),
                    "albums" to listOf("Drafts" to 1, "Olds" to 1, "Record" to 2),
                    "artists" to listOf("Band", "Me"),
                    "drafts album" to listOf("Streamed"),
                    "me artist" to listOf("Old", "Streamed"),
                    "folder songs" to listOf("Draft 1", "Draft 2", "Nowhere", "Old", "Song"),
                    "hidden folders" to listOf(
                        "1234-5678:BACKUP/Downloads",
                        "1234-5678:BACKUP/Downloads/Sub",
                    ),
                ),
                repo.views(),
            )
        }

    /** An album and an artist whose every song is hidden are gone, not listed empty. */
    @Test fun `hiding a whole volume drops albums and artists that lived only there`() = runTest {
        val repo = repo()
        settings.setFolderHidden("external_primary", hidden = true)
        val views = repo.views()
        assertEquals(
            listOf<Any>(
                listOf("Drafts" to 3, "Olds" to 1, "Record" to 1),
                listOf("Band", "Me"),
                listOf("external_primary", "external_primary:Music", "external_primary:Music/Band"),
            ),
            listOf(views["albums"]!!, views["artists"]!!, views["hidden folders"]!!),
        )
    }

    /** Showing it again restores everything from the SAME table — the fake never changes. */
    @Test fun `showing the folder again restores every view without a rescan`() = runTest {
        val repo = repo()
        settings.setFolderHidden("1234-5678:BACKUP/Downloads", hidden = true)
        val hidden = repo.views()
        settings.setFolderHidden("1234-5678:BACKUP/Downloads", hidden = false)
        assertEquals(
            listOf(false, true),
            listOf(hidden == everything, repo.views() == everything),
        )
    }

    /**
     * ONE collection, open across the change, re-emits: the Songs tab open while a folder is hidden
     * elsewhere updates in place rather than on the next launch. Each value is received before the
     * next change is made, so `flatMapLatest` cannot legitimately skip one.
     */
    @Test fun `an open songs collection follows a hide and a show`() = runTest {
        val repo = repo()
        val emitted = Channel<List<String>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            repo.songs().collect { songs -> emitted.send(songs.map { it.title }.sorted()) }
        }
        val seen = ArrayList<List<String>>()
        seen += emitted.receive()
        settings.setFolderHidden("1234-5678:BACKUP", hidden = true)
        seen += emitted.receive()
        settings.setFolderHidden("1234-5678:BACKUP", hidden = false)
        seen += emitted.receive()
        collector.cancel()
        assertEquals(
            listOf(
                listOf("Draft 1", "Draft 2", "Nowhere", "Old", "Song", "Streamed"),
                listOf("Nowhere", "Song", "Streamed"),
                listOf("Draft 1", "Draft 2", "Nowhere", "Old", "Song", "Streamed"),
            ),
            seen,
        )
    }
}
