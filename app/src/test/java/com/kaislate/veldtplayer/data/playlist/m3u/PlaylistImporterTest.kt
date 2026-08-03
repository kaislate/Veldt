// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist.m3u

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.playlist.PlaylistRepository
import com.kaislate.veldtplayer.data.playlist.db.PlaylistDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * The import flow end to end: real `ContentResolver`, real Room, real parser, real ladder.
 *
 * Only the *document* is a fixture — Robolectric's `ShadowContentResolver` serves the bytes — so
 * `openInputStream`, `Uri` parsing and the SAF document-id shapes are all exercised rather than
 * stubbed. Everything else in the chain is the production object.
 *
 * The fake source's id is deliberately **not** `"local"`: it is the only thing that can catch a
 * hardcoded source string (Global Constraint 2), which is a review rejection on this project.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as PlaylistRepositoryTest does.
@Config(sdk = [34])
class PlaylistImporterTest {

    /**
     * A stand-in library. `stableKey` mirrors `LocalSource`'s exactly — `LocalSourceKeysTest`
     * guards the real one — and `listSongs` returns the fixture, because *unlike*
     * `PlaylistRepository.resolve`, the importer is supposed to enumerate the source: the scan may
     * not have run when the user imports.
     */
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
    private lateinit var dao: PlaylistDao
    private lateinit var source: FakeSource
    private lateinit var repo: PlaylistRepository
    private lateinit var importer: PlaylistImporter
    private var clock = 1_000L
    private var nextId = 1L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, VeldtDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.playlistDao()
        source = FakeSource()
        repo = PlaylistRepository(dao, db.songDao(), source) { ++clock }
        importer = PlaylistImporter(context, source, repo)
    }

    @After fun tearDown() = db.close()

    private fun song(
        path: String? = null,
        relativeKey: String? = null,
        title: String = "T",
        artist: String = "A",
        album: String = "Al",
    ): Song = nextId++.let { id ->
        Song(
            id = id,
            uri = "content://media/external/audio/media/$id",
            filePath = path,
            relativeKey = relativeKey,
            title = title,
            artist = artist,
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

    /** Serve [text] as the document at [uri]. A supplier, so the same uri can be opened twice. */
    private fun serve(uri: String, text: String, charset: Charset = Charsets.UTF_8) =
        serveStream(uri) { ByteArrayInputStream(text.toByteArray(charset)) }

    private fun serveStream(uri: String, stream: () -> InputStream) {
        shadowOf(context.contentResolver).registerInputStreamSupplier(Uri.parse(uri), stream)
    }

    private companion object {
        const val DOC = "content://com.android.externalstorage.documents/document/" +
            "primary%3AMusic%2Flist.m3u"
    }

    // ---------------------------------------------------------------- nothing is dropped

    /**
     * The headline property, and the reason the class exists. Counted three ways in one assertion —
     * what the file said, what the result claims, and what is actually in the table — because a
     * result that reports 5 while storing 3 is exactly as bad as one that reports 3, and only the
     * pair can tell them apart.
     */
    @Test fun `every entry in the file becomes a row, resolved or not`() = runTest {
        source.songs = listOf(song(path = "/x/Music/a.mp3"), song(path = "/x/Music/c.mp3"))
        serve(
            DOC,
            """
            #EXTM3U
            /x/Music/a.mp3
            /x/Music/missing.mp3
            /x/Music/c.mp3
            /x/Music/gone.mp3
            """.trimIndent(),
        )

        val result = importer.import(DOC, "Mix")
        val rows = dao.getEntries(result.playlistId)

        assertEquals(
            listOf(4, 2, 2, 4),
            listOf(result.total, result.resolved, result.unresolved.size, rows.size),
        )
    }

    /**
     * An unresolved row is a first-class row: it keeps its position, it keeps the playlist's own
     * path as its identity, and it keeps a caption the user can read. `songId` null is the *only*
     * thing that distinguishes it, which is what lets Task 6 grey it out without a second query.
     */
    @Test fun `an unresolved entry is stored with a null songId and a readable caption`() = runTest {
        serve(DOC, "#EXTINF:210,Beck - Lost Cause\n/x/Music/Beck/Lost Cause.mp3\n/x/Music/bare.mp3")

        val result = importer.import(DOC, "Mix")
        val rows = dao.getEntries(result.playlistId)

        assertEquals(listOf(null, null), rows.map { it.songId })
        assertEquals(
            listOf("/x/Music/Beck/Lost Cause.mp3", "/x/Music/bare.mp3"),
            rows.map { it.sourceKey },
        )
        // The EXTINF title where there is one; the filename — not the whole path — where there is
        // not. Asserted as a pair so a caption rule that always picks one source is visible.
        assertEquals(listOf("Lost Cause", "bare.mp3"), rows.map { it.sourceTitle })
        assertEquals(listOf("Beck", ""), rows.map { it.sourceArtist })
    }

    /**
     * `#EXTINF` is a hint, never a fact (`LocalEntryResolver`'s rule 2), and that has to survive
     * being written down: a resolved row is captioned by the library, an unresolved one by the
     * playlist, and the pair is asserted together because a single row cannot distinguish "the
     * library wins" from "whatever was available won".
     */
    @Test fun `a resolved row is captioned by the library and an unresolved one by the playlist`() =
        runTest {
            source.songs = listOf(
                song(path = "/x/Music/a.mp3", title = "Lost Cause", artist = "Beck", album = "Sea Change")
            )
            serve(
                DOC,
                "#EXTINF:99,STALE ARTIST - STALE TITLE\n/x/Music/a.mp3\n" +
                    "#EXTINF:99,Ghost Artist - Ghost Title\n/x/Music/ghost.mp3",
            )

            val rows = dao.getEntries(importer.import(DOC, "Mix").playlistId)

            assertEquals(listOf("Lost Cause", "Ghost Title"), rows.map { it.sourceTitle })
            assertEquals(listOf("Beck", "Ghost Artist"), rows.map { it.sourceArtist })
            assertEquals(listOf("Sea Change", ""), rows.map { it.sourceAlbum })
        }

    /**
     * The dense `0..n-1` invariant, which nothing at schema level enforces — the repository's write
     * paths are all of it. Asserted alongside the file order, because positions that are dense but
     * shuffled would satisfy the invariant and still ruin the playlist.
     */
    @Test fun `positions are dense, zero based and in file order`() = runTest {
        serve(DOC, "a.mp3\nb.mp3\nc.mp3\nd.mp3")

        val rows = dao.getEntries(importer.import(DOC, "Mix").playlistId)

        assertEquals(listOf(0, 1, 2, 3), rows.map { it.position })
        assertEquals(listOf("a.mp3", "b.mp3", "c.mp3", "d.mp3"), rows.map { it.sourceKey })
    }

    /** Source identity comes from [LibrarySource.id]; a hardcoded `"local"` is a rejection. */
    @Test fun `rows are stamped with the source's own id`() = runTest {
        serve(DOC, "a.mp3")
        val rows = dao.getEntries(importer.import(DOC, "Mix").playlistId)
        assertEquals(listOf("test-source"), rows.map { it.sourceId })
    }

    /** "Imported 43 of 47" is only useful if the four can be named. */
    @Test fun `the result carries the parsed entries that did not resolve`() = runTest {
        source.songs = listOf(song(path = "/x/a.mp3"))
        serve(DOC, "/x/a.mp3\n#EXTINF:210,Beck - Lost Cause\n/x/gone.mp3")

        val result = importer.import(DOC, "Mix")

        assertEquals(
            listOf(M3uEntry("/x/gone.mp3", durationSec = 210, title = "Lost Cause", artist = "Beck")),
            result.unresolved,
        )
    }

    // ---------------------------------------------------------------- decoding, in situ

    /**
     * [M3uText] under real conditions: the same playlist in three encodings, against a library that
     * only has the accented path. All three must import identically — the Latin-1 one is the case
     * a lone `String(bytes)` gets wrong, and the mislabelled `.m3u8` is the case a branch on the
     * extension would get wrong.
     */
    @Test fun `a playlist resolves the same in utf8, in latin1 and mislabelled as m3u8`() = runTest {
        val target = song(path = "/x/Music/Björk.mp3")
        source.songs = listOf(target)
        val text = "#EXTM3U\r\n/x/Music/Björk.mp3\r\n"
        serve("$DOC#a", text, Charsets.UTF_8)
        serve("$DOC#b", text, Charsets.ISO_8859_1)
        serve("${DOC}8", text, Charsets.ISO_8859_1)

        val resolved = listOf("$DOC#a", "$DOC#b", "${DOC}8")
            .map { importer.import(it, "Mix").resolved }

        assertEquals(listOf(1, 1, 1), resolved)
    }

    /**
     * The `file://` + percent-encoding shape VLC and Rhythmbox write, through the whole chain.
     * Paired with the bare path holding a literal `%20`, which names a different, real file — the
     * non-collapse the resolver's decode is conditioned on the scheme for.
     */
    @Test fun `a file uri entry and a literal percent entry import as two different tracks`() =
        runTest {
            val spaced = song(path = "/x/Music/a b.mp3", title = "Spaced")
            val literal = song(path = "/x/Music/a%20b.mp3", title = "Literal")
            source.songs = listOf(spaced, literal)
            serve(DOC, "file:///x/Music/a%20b.mp3\n/x/Music/a%20b.mp3")

            val rows = dao.getEntries(importer.import(DOC, "Mix").playlistId)

            assertEquals(listOf("Spaced", "Literal"), rows.map { it.sourceTitle })
        }

    // ---------------------------------------------------------------- the playlist's own folder

    /**
     * **The four cases that justify reading a directory out of a document uri at all — and the
     * three that must not be read.**
     *
     * The same one-line relative playlist and the same library throughout: two albums that each
     * contain an `01.mp3`, so a bare `01.mp3` is ambiguous on the filename rung and resolves to
     * nothing *unless* the playlist's own folder is known.
     *
     *  1. external storage, `primary:Music/Odelay/list.m3u` — the document id *is* the location by
     *     that provider's contract, so the entry resolves against `Music/Odelay`;
     *  2. **the same document id under a third-party authority** — a file manager that copied
     *     AOSP's id shape. We have no contract with it and its `primary:` may mean anything, so
     *     nothing is claimed;
     *  3. external storage with a document id carrying no volume prefix — not a shape that provider
     *     emits, and not one to improvise a reading for;
     *  4. Downloads, whose id is an opaque number with no structure at all.
     *
     * Case 2 is the load-bearing one and is the reason this test was rewritten: with only case 4 as
     * the negative, deleting the authority check entirely left the suite **green**, because an
     * opaque id yields no directory whether it is inspected or not. A control that cannot fail is
     * not a control, and "we only read ids we have a contract with" is exactly the kind of rule
     * this phase keeps finding asserted in prose and pinned by nothing.
     *
     * Both halves matter. Without case 1, every relative playlist imports grey. Without cases 2–4,
     * a guessed directory turns an honest ambiguity into a confident `NORMALISED` match against
     * some other album's track — the failure this phase has spent six defects on.
     */
    @Test fun `a directory is read only from a document id this app has a contract with`() =
        runTest {
            source.songs = listOf(
                song(relativeKey = "external_primary:Music/Odelay/01.mp3", title = "Devils Haircut"),
                song(relativeKey = "external_primary:Music/Mutations/01.mp3", title = "Cold Brains"),
            )
            val external = "content://com.android.externalstorage.documents/document/" +
                "primary%3AMusic%2FOdelay%2Flist.m3u"
            val thirdParty = "content://com.example.files.documents/document/" +
                "primary%3AMusic%2FOdelay%2Flist.m3u"
            val noVolume = "content://com.android.externalstorage.documents/document/" +
                "Music%2FOdelay%2Flist.m3u"
            val downloads = "content://com.android.providers.downloads.documents/document/1234"
            val sources = listOf(external, thirdParty, noVolume, downloads)
            sources.forEach { serve(it, "01.mp3") }

            val results = sources.map { importer.import(it, "From $it") }

            assertEquals(listOf(1, 0, 0, 0), results.map { it.resolved })
            // And nothing was dropped in any of the four: an unknown folder costs a match, never a
            // row.
            assertEquals(listOf(1, 1, 1, 1), results.map { dao.getEntries(it.playlistId).size })
            assertEquals(
                listOf("Devils Haircut", "01.mp3", "01.mp3", "01.mp3"),
                results.flatMap { dao.getEntries(it.playlistId) }.map { it.sourceTitle },
            )
        }

    // ---------------------------------------------------------------- failure leaves no trace

    /**
     * A document that fails mid-read must not leave an empty playlist named after it. The playlist
     * row is created only after the file has yielded entries, and this is what pins that ordering:
     * an import that threw is an import the user can simply retry.
     */
    @Test fun `a document that cannot be read creates no playlist`() = runTest {
        serveStream(DOC) {
            object : InputStream() {
                override fun read(): Int = throw IOException("provider died")
            }
        }

        val thrown = runCatching { importer.import(DOC, "Mix") }.exceptionOrNull()

        assertTrue("expected an IOException, got $thrown", thrown is IOException)
        assertEquals(0, repo.observe().first().size)
    }

    /**
     * The picker hands back whatever the user tapped, including a 4 GB video. Reading it whole is
     * an OOM kill that looks like a random crash; the cap turns it into an error, and truncating
     * instead of failing is not an option because a half-read file loses entries silently.
     */
    @Test fun `a document larger than the cap creates no playlist`() = runTest {
        serveStream(DOC) {
            object : InputStream() {
                override fun read(): Int = 'a'.code
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    b.fill('a'.code.toByte(), off, off + len)
                    return len
                }
            }
        }

        val thrown = runCatching { importer.import(DOC, "Huge") }.exceptionOrNull()

        assertTrue("expected an IOException, got $thrown", thrown is IOException)
        assertEquals(0, repo.observe().first().size)
    }

    /**
     * An empty file is not a failure — it is a playlist that describes no tracks, and the user gets
     * the empty playlist they asked for rather than an error they cannot act on. The distinction
     * from the two tests above is the whole point of asserting the playlist exists.
     */
    @Test fun `an empty but readable document creates an empty playlist`() = runTest {
        serve(DOC, "#EXTM3U\n\n\n")

        val result = importer.import(DOC, "Empty")

        assertEquals(listOf(0, 0, 0), listOf(result.total, result.resolved, result.unresolved.size))
        assertEquals(1, repo.observe().first().size)
        assertEquals(0, dao.getEntries(result.playlistId).size)
    }
}
