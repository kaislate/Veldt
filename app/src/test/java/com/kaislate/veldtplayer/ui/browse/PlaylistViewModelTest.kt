// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.library.db.toEntity
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.playlist.PlaylistRepository
import com.kaislate.veldtplayer.data.playlist.m3u.DocumentNameReader
import com.kaislate.veldtplayer.data.playlist.m3u.PlaylistImporter
import com.kaislate.veldtplayer.playback.PlaybackConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The import flow as the SCREEN reaches it: real importer, real resolver, real Room, real
 * `ContentResolver`.
 *
 * `PlaylistPresentationTest` proves that [PlaylistPresentation.importOutcome] converts a thrown
 * [SecurityException] into a reportable failure. This proves the view model actually ROUTES
 * through it — a guard the production path does not reach is the same defect as no guard, and
 * P1.4 has already shipped one of those.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pinned as the other playlist suites are.
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModelTest {

    /** As in `PlaylistImporterTest`: deliberately not `"local"`, so a hardcoded id is caught. */
    private class FakeSource(
        override val id: String = "test-source",
        var songs: List<Song> = emptyList(),
    ) : LibrarySource {
        override fun resolvePlayableUri(song: Song): String = song.uri
        override fun stableKey(song: Song): String = song.relativeKey ?: song.filePath ?: song.uri
        override suspend fun listSongs(): List<Song> = songs
        override suspend fun listAlbums(): List<Album> = emptyList()
        override suspend fun listArtists(): List<Artist> = emptyList()
        override suspend fun search(query: String): List<Song> = emptyList()
    }

    private lateinit var context: Context
    private lateinit var db: VeldtDatabase
    private lateinit var source: FakeSource
    private lateinit var playlists: PlaylistRepository
    private lateinit var music: MusicRepository
    private lateinit var vm: PlaylistViewModel
    private var clock = 1_000L
    private var nextId = 1L

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, VeldtDatabase::class.java)
            .allowMainThreadQueries().build()
        source = FakeSource()
        playlists = PlaylistRepository(db.playlistDao(), db.songDao(), source) { ++clock }
        music = MusicRepository(db.songDao(), source, context)
        vm = PlaylistViewModel(
            playlists = playlists,
            importer = PlaylistImporter(context, source, playlists),
            documentNames = DocumentNameReader(context),
            music = music,
            connection = PlaybackConnection(context, music),
        )
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun song(path: String, title: String = "T", album: String = "Al"): Song =
        nextId++.let { id ->
            Song(
                id = id,
                uri = "content://media/external/audio/media/$id",
                filePath = path,
                relativeKey = null,
                title = title,
                artist = "Artist",
                album = album,
                albumArtist = null,
                trackNumber = null,
                discNumber = null,
                year = null,
                durationMs = 1_000L,
                dateModifiedSec = 0L,
                hasEmbeddedArt = false,
            )
        }

    private suspend fun seedLibrary(vararg songs: Song) {
        source.songs = songs.toList()
        db.songDao().upsertAll(songs.map { it.toEntity() })
    }

    private fun serve(uri: String, text: String) =
        serveStream(uri) { ByteArrayInputStream(text.toByteArray()) }

    private fun serveStream(uri: String, stream: () -> InputStream) {
        shadowOf(context.contentResolver).registerInputStreamSupplier(Uri.parse(uri), stream)
    }

    // ------------------------------------------------------------- the confirmed crash

    /**
     * A lapsed SAF grant. This is the crash: nothing between the picker and `readBytes` catches
     * [SecurityException], so before this task the user lost the app for picking a file whose
     * permission had expired.
     *
     * Asserted on the reported CAUSE, not on "the test finished": a catch-all would satisfy the
     * weaker claim while telling the user the wrong thing.
     */
    @Test fun `a lapsed permission is reported instead of killing the app`() = runTest {
        serveStream(DOC) { throw SecurityException("Permission Denial: opening provider") }

        val outcome = importAndAwait(DOC)

        assertEquals(ImportOutcome.Failed(ImportFailure.PERMISSION_LAPSED), outcome)
        assertFalse("the affordance must not stay disabled", vm.importing.value)
    }

    /** And nothing is left behind: a failed import must not create a playlist named after it. */
    @Test fun `a failed import creates no playlist`() = runTest {
        serveStream(DOC) { throw SecurityException("Permission Denial: opening provider") }

        importAndAwait(DOC)

        assertEquals(PlaylistsUiState.Empty, PlaylistPresentation.listStateOf(cards()))
    }

    // -------------------------------------------------------- the import result is reported

    /**
     * The headline property, from the screen's side: "imported 1 of 3" AND three rows, two of them
     * greyed. A count that says three while the list shows one would be exactly the failure the
     * unresolved rows exist to prevent.
     */
    @Test fun `an incomplete import reports the count and still lists every track`() = runTest {
        seedLibrary(song("/x/Music/a.mp3", title = "Kept"))
        serve(
            DOC,
            """
            #EXTM3U
            #EXTINF:200,Beck - Lost Cause
            /x/Music/gone.mp3
            /x/Music/a.mp3
            /x/Music/also-gone.mp3
            """.trimIndent(),
        )

        val done = importAndAwait(DOC) as ImportOutcome.Done
        assertEquals(3, done.result.total)
        assertEquals(1, done.result.resolved)
        assertEquals(2, done.result.unresolved.size)
        assertEquals("Imported 1 of 3 tracks", PlaylistImportReport.headline(done.result))

        val detail = vm.detail(done.result.playlistId).first() as PlaylistDetailUiState.Ready
        assertEquals(3, detail.rows.size)
        assertEquals(listOf(false, true, false), detail.rows.map { it.playable })
        assertEquals("Lost Cause", detail.rows.first().title)
    }

    /** The name comes from the document, not from a counter. */
    @Test fun `an imported playlist is named after the file`() = runTest {
        serve(DOC, "/x/Music/a.mp3")

        importAndAwait(DOC)

        assertEquals(listOf("list"), cards().map { it.name })
    }

    /**
     * No file was ever chosen, so this must not arrive as the generic cause whose copy talks about
     * "that file" and whose button offers to try the picker again.
     */
    @Test fun `a device with no file picker reports its own cause`() = runTest {
        vm.reportPickerUnavailable()

        assertEquals(ImportOutcome.Failed(ImportFailure.PICKER_UNAVAILABLE), vm.importOutcome.value)
        assertFalse(PlaylistImportReport.retryable(ImportFailure.PICKER_UNAVAILABLE))
    }

    @Test fun `the report is held until it is dismissed`() = runTest {
        serve(DOC, "/x/Music/a.mp3")

        importAndAwait(DOC)
        assertNotNull(vm.importOutcome.value)

        vm.dismissImport()
        assertNull(vm.importOutcome.value)
    }

    // ------------------------------------------------------------------ the tab and the page

    @Test fun `the tab counts every entry and names the missing ones`() = runTest {
        seedLibrary(song("/x/Music/a.mp3"))
        serve(DOC, "/x/Music/a.mp3\n/x/Music/gone.mp3\n")

        importAndAwait(DOC)

        val card = cards().single()
        assertEquals(2, card.trackCount)
        assertEquals(1, card.missingCount)
        assertEquals("2 tracks · 1 missing", PlaylistPresentation.caption(card.trackCount, card.missingCount))
    }

    /** A playlist with nothing in it is a page, not a 404 — the two states must not collapse. */
    @Test fun `an empty playlist opens, and a deleted one reports itself gone`() = runTest {
        val id = playlists.create("Mix")

        val ready = vm.detail(id).first()
        assertEquals(PlaylistDetailUiState.Ready("Mix", emptyList(), emptyList()), ready)

        vm.delete(id)
        assertEquals(PlaylistDetailUiState.Missing, vm.detail(id).first { it is PlaylistDetailUiState.Missing })
    }

    @Test fun `a reorder is committed in playlist order`() = runTest {
        seedLibrary(
            song("/x/Music/a.mp3", title = "A"),
            song("/x/Music/b.mp3", title = "B"),
            song("/x/Music/c.mp3", title = "C"),
        )
        serve(DOC, "/x/Music/a.mp3\n/x/Music/b.mp3\n/x/Music/c.mp3\n")
        val id = (importAndAwait(DOC) as ImportOutcome.Done).result.playlistId
        awaitTitles(id, listOf("A", "B", "C"))

        vm.move(id, from = 0, to = 2)

        awaitTitles(id, listOf("B", "C", "A"))
    }

    /** An unresolved entry is the one row a user cannot fix any other way. */
    @Test fun `an unresolved entry can be removed`() = runTest {
        serve(DOC, "/x/Music/gone.mp3\n")
        val id = (importAndAwait(DOC) as ImportOutcome.Done).result.playlistId
        val row = (vm.detail(id).first() as PlaylistDetailUiState.Ready).rows.single()
        assertFalse(row.playable)

        vm.removeEntry(row.entryId)

        awaitTitles(id, emptyList())
    }

    /**
     * Start an import and wait for its report.
     *
     * The importer reads the document on [Dispatchers.IO], a real dispatcher the test scheduler
     * cannot skip, so `advanceUntilIdle` would not be enough — the report itself is the only
     * signal that the whole chain has finished. `importing` is cleared before it is published, so
     * awaiting this also settles that flag.
     */
    private suspend fun importAndAwait(uri: String): ImportOutcome {
        vm.import(uri)
        return vm.importOutcome.filterNotNull().first()
    }

    /**
     * Wait until the page reports exactly [expected], in order.
     *
     * Every mutation on the view model is a `launch` that lands in Room on Room's own executor —
     * a real thread the test scheduler has no say over — so reading the flow's FIRST emission
     * after calling one races the write and passes or fails on timing. Waiting for the specific
     * order is the only deterministic form: a reorder that never happens hangs to `runTest`'s
     * timeout and fails, rather than passing on a lucky frame.
     */
    private suspend fun awaitTitles(playlistId: Long, expected: List<String>) {
        val titles = vm.detail(playlistId)
            .map { state -> (state as? PlaylistDetailUiState.Ready)?.rows?.map { it.title } }
            .first { it == expected }
        assertEquals(expected, titles)
    }

    private suspend fun cards(): List<PlaylistCard> =
        playlists.observe().first().map { PlaylistPresentation.cardOf(it, playlists.resolve(it.id)) }

    private companion object {
        const val DOC = "content://com.android.externalstorage.documents/document/" +
            "primary%3AMusic%2Flist.m3u"
    }
}
