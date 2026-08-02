// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.playlist.PlaylistTrack
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntity
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntryEntity
import com.kaislate.veldtplayer.data.playlist.m3u.ImportResult
import com.kaislate.veldtplayer.data.playlist.m3u.M3uEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The playlist screens' decisions, pinned where the composables cannot be.
 *
 * Two properties here are the whole point of the task and are asserted in the shape that can tell
 * a regression apart from a coincidence:
 *
 *  - an unresolved entry is RENDERED, never filtered — asserted on the row count AND on the row's
 *    own `playable`, because a list that keeps the row but plays it anyway is a different bug with
 *    the same symptom;
 *  - an import that throws is REPORTED, per cause — asserted on the [ImportFailure] and not merely
 *    on "nothing escaped", which the catch-all arm would satisfy on its own.
 */
class PlaylistPresentationTest {

    private var nextId = 1L

    private fun song(
        title: String = "Title",
        artist: String = "Artist",
        album: String = "Album",
        albumArtist: String? = null,
    ): Song = nextId++.let { id ->
        Song(
            id = id,
            uri = "content://media/external/audio/media/$id",
            filePath = "/x/Music/$id.mp3",
            relativeKey = "external_primary:Music/$id.mp3",
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = null,
            discNumber = null,
            year = null,
            durationMs = 1_000L,
            dateModifiedSec = 0L,
            hasEmbeddedArt = false,
        )
    }

    private fun entry(
        id: Long,
        position: Int,
        title: String = "Captured title",
        artist: String = "Captured artist",
    ) = PlaylistEntryEntity(
        id = id,
        playlistId = 7L,
        position = position,
        sourceId = "test-source",
        sourceKey = "key-$id",
        songId = null,
        sourceTitle = title,
        sourceArtist = artist,
        sourceAlbum = "",
    )

    private fun resolved(id: Long, position: Int, song: Song) =
        PlaylistTrack(entry = entry(id, position), song = song)

    private fun unresolved(
        id: Long,
        position: Int,
        title: String = "Captured title",
        artist: String = "Captured artist",
    ) = PlaylistTrack(entry = entry(id, position, title, artist), song = null)

    // ------------------------------------------------------------- nothing is hidden, ever

    @Test fun `an unresolved entry still produces a row`() {
        val rows = PlaylistPresentation.rowsOf(
            listOf(resolved(1, 0, song()), unresolved(2, 1), resolved(3, 2, song())),
        )
        assertEquals(3, rows.size)
        assertEquals(listOf(true, false, true), rows.map { it.playable })
    }

    @Test fun `a playlist of nothing but unresolved entries still renders every one`() {
        val rows = PlaylistPresentation.rowsOf((0L until 4L).map { unresolved(it + 1, it.toInt()) })
        assertEquals(4, rows.size)
        assertTrue(rows.none { it.playable })
    }

    @Test fun `an unresolved row keeps the title and artist the import captured`() {
        val row = PlaylistPresentation.rowOf(unresolved(1, 0, title = "Lost Cause", artist = "Beck"))
        assertEquals("Lost Cause", row.title)
        assertTrue("expected the artist in '${row.subtitle}'", row.subtitle.contains("Beck"))
    }

    /**
     * The note itself is pinned to a LITERAL, here and below.
     *
     * `subtitle.contains(MISSING_NOTE)` reads like an assertion and is not one: set the constant
     * to `""` and every string in the world contains it. Same shape as the `HEADER_ITEM_COUNT`
     * tautology and the `COVER_LIMIT` one — the constant compared against the code that applies
     * it can only ever agree with itself.
     */
    @Test fun `the missing note is the sentence an unplayable row explains itself with`() {
        assertEquals("Not in your library", PlaylistPresentation.MISSING_NOTE)
    }

    @Test fun `an unresolved row says why it cannot be played`() {
        val row = PlaylistPresentation.rowOf(unresolved(1, 0))
        assertTrue(
            "expected the explanation in '${row.subtitle}'",
            row.subtitle.contains("Not in your library"),
        )
        assertFalse(row.playable)
    }

    /**
     * An unresolved entry with no captured artist must still explain itself. Without the branch
     * this would read " · Not in your library" with a leading separator and no subject.
     */
    @Test fun `an unresolved row with no captured artist is still explained`() {
        val row = PlaylistPresentation.rowOf(unresolved(1, 0, artist = "   "))
        assertEquals("Not in your library", row.subtitle)
    }

    /** The library's tags win for a resolved row — the same rule every other list follows. */
    @Test fun `a resolved row is described by the library, not by the captured strings`() {
        val row = PlaylistPresentation.rowOf(
            PlaylistTrack(
                entry = entry(1, 0, title = "Stale title", artist = "Stale artist"),
                song = song(title = "Real Title", artist = "Real Artist", album = "Real Album"),
            ),
        )
        assertEquals("Real Title", row.title)
        assertEquals("Real Artist · Real Album", row.subtitle)
    }

    // ------------------------------------------------------------------------ play in context

    /**
     * The index the player is given is counted in the QUEUE, not in the displayed list. With two
     * missing rows above the tap the two numbers differ, which is the only case that can catch a
     * straight pass-through.
     */
    @Test fun `tapping past a missing row starts on the track that was tapped`() {
        val third = song(title = "Third")
        val rows = PlaylistPresentation.rowsOf(
            listOf(
                resolved(1, 0, song(title = "First")),
                unresolved(2, 1),
                resolved(3, 2, song(title = "Second")),
                unresolved(4, 3),
                resolved(5, 4, third),
            ),
        )
        val target = PlaylistPresentation.playTarget(rows, index = 4)!!
        assertEquals(3, target.queue.size)
        assertEquals(2, target.startIndex)
        assertEquals(third.id, target.queue[target.startIndex].id)
    }

    @Test fun `a missing row is not playable at all`() {
        val rows = PlaylistPresentation.rowsOf(
            listOf(resolved(1, 0, song()), unresolved(2, 1)),
        )
        assertNull(PlaylistPresentation.playTarget(rows, index = 1))
    }

    @Test fun `an out of range tap plays nothing rather than throwing`() {
        val rows = PlaylistPresentation.rowsOf(listOf(resolved(1, 0, song())))
        assertNull(PlaylistPresentation.playTarget(rows, index = 9))
        assertNull(PlaylistPresentation.playTarget(emptyList(), index = 0))
    }

    // --------------------------------------------------------------------------- the tab's list

    @Test fun `a card counts every entry and says how many are missing`() {
        val card = PlaylistPresentation.cardOf(
            PlaylistEntity(id = 7, name = "Mix", createdAt = 1, updatedAt = 1),
            listOf(resolved(1, 0, song()), unresolved(2, 1), unresolved(3, 2)),
        )
        assertEquals(3, card.trackCount)
        assertEquals(2, card.missingCount)
        assertEquals("Mix", card.name)
    }

    @Test fun `the caption names the missing tracks rather than quietly shrinking the count`() {
        assertEquals("12 tracks · 2 missing", PlaylistPresentation.caption(12, 2))
        assertEquals("12 tracks", PlaylistPresentation.caption(12, 0))
        assertEquals("1 track", PlaylistPresentation.caption(1, 0))
        assertEquals("Empty", PlaylistPresentation.caption(0, 0))
    }

    /** Covers come from resolved tracks only — a missing entry has no artwork to offer. */
    @Test fun `covers skip unresolved entries`() {
        val covers = PlaylistPresentation.coversOf(
            listOf(unresolved(1, 0), resolved(2, 1, song(album = "A"))),
        )
        assertEquals(1, covers.size)
        assertEquals("A", covers.first().album)
    }

    /**
     * A playlist that opens with a whole record must not hand the mosaic four copies of one cover
     * and call itself done: the second record on the list is what makes the header interesting.
     */
    @Test fun `covers are one per album, in playlist order`() {
        val tracks = (0 until 8).map { resolved(it + 1L, it, song(album = "First", artist = "X")) } +
            resolved(9, 8, song(album = "Second", artist = "X")) +
            resolved(10, 9, song(album = "Third", artist = "X"))
        val covers = PlaylistPresentation.coversOf(tracks)
        assertEquals(listOf("First", "Second", "Third"), covers.map { it.album })
    }

    /** Two records genuinely called "Greatest Hits" are two covers — the key is compound. */
    @Test fun `same-titled albums by different artists are two covers`() {
        val covers = PlaylistPresentation.coversOf(
            listOf(
                resolved(1, 0, song(album = "Greatest Hits", artist = "Queen")),
                resolved(2, 1, song(album = "Greatest Hits", artist = "ABBA")),
            ),
        )
        assertEquals(2, covers.size)
    }

    /**
     * FOUR, the literal, not `COVER_LIMIT`.
     *
     * Asserting the constant against the function that applies it passed for every value from 4
     * to 20 — a reviewer mutated it to 6 and the whole suite stayed green. Spec §3.3 says the
     * mosaic is "the first four covers", so four is a spec'd number, and it is about to be
     * consumed by Task 7's slot layout, which has a 4-up case.
     */
    @Test fun `covers are capped at the four the mosaic slots`() {
        assertEquals(4, PlaylistPresentation.COVER_LIMIT)
        val tracks = (0 until 20).map { resolved(it + 1L, it, song(album = "Album $it")) }
        val covers = PlaylistPresentation.coversOf(tracks)
        assertEquals(4, covers.size)
        // And it is the FIRST four, in playlist order — a cap that took the last four, or an
        // arbitrary four, would also have size 4.
        assertEquals(listOf("Album 0", "Album 1", "Album 2", "Album 3"), covers.map { it.album })
    }

    /** Loading is the flow's initial value; an answered-but-empty library is a different screen. */
    @Test fun `an answered empty list is Empty, never Loading`() {
        assertEquals(PlaylistsUiState.Empty, PlaylistPresentation.listStateOf(emptyList()))
    }

    @Test fun `a non-empty list is Ready`() {
        val card = PlaylistCard(1, "Mix", 3, 0, emptyList())
        assertEquals(PlaylistsUiState.Ready(listOf(card)), PlaylistPresentation.listStateOf(listOf(card)))
    }

    // ------------------------------------------------------------------- empty versus deleted

    /**
     * The defect class this phase keeps producing: two distinct inputs collapsed into one output.
     * A playlist that exists and holds nothing is NOT a playlist that was deleted while you were
     * looking at it, and they must not render the same screen.
     */
    @Test fun `an empty playlist is Ready with no rows, not Missing`() {
        val state = PlaylistPresentation.detailStateOf(
            PlaylistEntity(id = 7, name = "Mix", createdAt = 1, updatedAt = 1),
            emptyList(),
        )
        assertEquals(PlaylistDetailUiState.Ready("Mix", emptyList(), emptyList()), state)
    }

    @Test fun `a playlist that is gone is Missing`() {
        assertEquals(
            PlaylistDetailUiState.Missing,
            PlaylistPresentation.detailStateOf(null, emptyList()),
        )
    }

    @Test fun `the detail state carries every row, resolved or not`() {
        val state = PlaylistPresentation.detailStateOf(
            PlaylistEntity(id = 7, name = "Mix", createdAt = 1, updatedAt = 1),
            listOf(resolved(1, 0, song()), unresolved(2, 1)),
        ) as PlaylistDetailUiState.Ready
        assertEquals(2, state.rows.size)
    }

    // ---------------------------------------------------------------- the import cannot crash

    /**
     * The confirmed crash. `PlaylistImporter.import` throws [SecurityException] when the SAF grant
     * has lapsed — after process death, to a user who did nothing wrong — and nothing in its chain
     * catches it.
     *
     * Asserted on the FAILURE CAUSE, not merely on "no exception escaped": the catch-all arm would
     * satisfy the weaker assertion by itself, so a regression that dropped the SecurityException
     * arm would go unnoticed.
     */
    @Test fun `a lapsed permission is reported, not thrown`() = runTest {
        val outcome = PlaylistPresentation.importOutcome { throw SecurityException("grant lapsed") }
        assertEquals(ImportOutcome.Failed(ImportFailure.PERMISSION_LAPSED), outcome)
    }

    @Test fun `a missing document is reported as missing, not as unreadable`() = runTest {
        val outcome = PlaylistPresentation.importOutcome { throw FileNotFoundException("gone") }
        assertEquals(ImportOutcome.Failed(ImportFailure.MISSING_FILE), outcome)
    }

    @Test fun `an unreadable or oversized document is reported`() = runTest {
        val outcome = PlaylistPresentation.importOutcome { throw IOException("too big") }
        assertEquals(ImportOutcome.Failed(ImportFailure.UNREADABLE), outcome)
    }

    @Test fun `anything else a provider throws is still reported rather than crashing`() = runTest {
        val outcome = PlaylistPresentation.importOutcome { throw IllegalStateException("weird") }
        assertEquals(ImportOutcome.Failed(ImportFailure.UNEXPECTED), outcome)
    }

    /** Swallowing cancellation would leave a cancelled import reporting a failure it never had. */
    @Test fun `cancellation is not swallowed`() = runTest {
        val thrown = runCatching {
            PlaylistPresentation.importOutcome { throw CancellationException("cancelled") }
        }.exceptionOrNull()
        assertTrue("expected a CancellationException, got $thrown", thrown is CancellationException)
    }

    @Test fun `a successful import is carried through intact`() = runTest {
        val result = ImportResult(playlistId = 7, total = 3, resolved = 3, unresolved = emptyList())
        assertEquals(ImportOutcome.Done(result), PlaylistPresentation.importOutcome { result })
    }

    // ------------------------------------------------------------------- the import is reported

    @Test fun `the headline names both numbers when tracks went unmatched`() {
        assertEquals(
            "Imported 43 of 47 tracks",
            PlaylistImportReport.headline(result(total = 47, resolved = 43, missing = 4)),
        )
    }

    @Test fun `a complete import does not invent a second number`() {
        assertEquals(
            "Imported 47 tracks",
            PlaylistImportReport.headline(result(total = 47, resolved = 47, missing = 0)),
        )
    }

    @Test fun `a file with no tracks says so instead of claiming an import`() {
        assertEquals(
            "That file listed no tracks",
            PlaylistImportReport.headline(result(total = 0, resolved = 0, missing = 0)),
        )
    }

    @Test fun `the detail counts the unmatched tracks and says they were kept`() {
        val detail = PlaylistImportReport.detail(result(total = 47, resolved = 43, missing = 4))
        assertTrue("expected the count in '$detail'", detail.contains("4 tracks"))
        assertTrue("expected the reassurance in '$detail'", detail.contains("stay in the playlist"))
    }

    @Test fun `one unmatched track reads as one, not as one tracks`() {
        val detail = PlaylistImportReport.detail(result(total = 47, resolved = 46, missing = 1))
        assertTrue("expected singular copy in '$detail'", detail.startsWith("One track isn't"))
    }

    @Test fun `an unmatched track is named by EXTINF when the file said so`() {
        val entry = M3uEntry("/x/Music/Beck/Lost Cause.mp3", 220, "Lost Cause", "Beck")
        assertEquals("Lost Cause", PlaylistImportReport.unresolvedLabel(entry))
        assertEquals("Beck", PlaylistImportReport.unresolvedDetail(entry))
    }

    /** A path reads as a stack trace; a filename reads as a track. */
    @Test fun `an unmatched track with no EXTINF is named by its filename`() {
        val entry = M3uEntry("/storage/emulated/0/Music/Beck/Lost Cause.mp3", null, null, null)
        assertEquals("Lost Cause.mp3", PlaylistImportReport.unresolvedLabel(entry))
        assertEquals(entry.path, PlaylistImportReport.unresolvedDetail(entry))
    }

    @Test fun `a windows separator is a separator too`() {
        val entry = M3uEntry("D:\\Music\\Beck\\Lost Cause.mp3", null, null, null)
        assertEquals("Lost Cause.mp3", PlaylistImportReport.unresolvedLabel(entry))
    }

    /**
     * Four causes, four messages. A copy-paste that collapsed two of them into one string would
     * leave a user told to "pick the file again" about a file that is simply too big.
     */
    @Test fun `every failure cause has its own wording`() {
        // The count is a literal too. `entries.size` against `entries.map{}.toSet().size` is the
        // distinctness claim and is worth making — but on its own it would also pass for an enum
        // that lost a case, which is how a cause silently stops being distinguishable.
        assertEquals(5, ImportFailure.entries.size)
        val headlines = ImportFailure.entries.map(PlaylistImportReport::failureHeadline)
        val details = ImportFailure.entries.map(PlaylistImportReport::failureDetail)
        assertEquals(5, headlines.toSet().size)
        assertEquals(5, details.toSet().size)
        assertTrue(headlines.none { it.isBlank() })
        assertTrue(details.none { it.isBlank() })
    }

    /**
     * The one failure with no remedy. Offering "Pick a file" when there is no picker relaunches
     * the thing that just threw and lands the user back on this dialog, forever — and the copy
     * must not talk about "that file", because none was ever chosen.
     */
    @Test fun `a missing picker is its own cause, is not retryable, and blames no file`() {
        assertFalse(PlaylistImportReport.retryable(ImportFailure.PICKER_UNAVAILABLE))
        val headline = PlaylistImportReport.failureHeadline(ImportFailure.PICKER_UNAVAILABLE)
        val detail = PlaylistImportReport.failureDetail(ImportFailure.PICKER_UNAVAILABLE)
        assertFalse("'$headline' must not blame a file", headline.contains("file", ignoreCase = true) &&
            headline.contains("that file", ignoreCase = true))
        assertTrue("'$detail' should say what would fix it", detail.contains("Files or Documents"))
    }

    @Test fun `every other cause is worth retrying`() {
        ImportFailure.entries
            .filter { it != ImportFailure.PICKER_UNAVAILABLE }
            .forEach { assertTrue("$it should be retryable", PlaylistImportReport.retryable(it)) }
    }

    /** The one remedy that actually works for a lapsed grant is picking the file again. */
    @Test fun `a lapsed grant tells the user to pick the file again`() {
        val detail = PlaylistImportReport.failureDetail(ImportFailure.PERMISSION_LAPSED)
        assertTrue("expected the remedy in '$detail'", detail.contains("Pick the playlist again"))
    }

    private fun result(total: Int, resolved: Int, missing: Int) = ImportResult(
        playlistId = 7,
        total = total,
        resolved = resolved,
        unresolved = (0 until missing).map { M3uEntry("/x/$it.mp3", null, "Track $it", null) },
    )

    // -------------------------------------------------------------------------------- naming

    @Test fun `the provider's display name wins and loses its extension`() {
        assertEquals("Road Trip", PlaylistNaming.of("Road Trip.m3u8", "content://x/document/42"))
    }

    @Test fun `a provider that gives no name falls back to the uri`() {
        val uri = "content://com.android.externalstorage.documents/document/" +
            "primary%3AMusic%2FRoad%20Trip.m3u"
        assertEquals("Road Trip", PlaylistNaming.of(null, uri))
        assertEquals("Road Trip", PlaylistNaming.of("   ", uri))
    }

    /**
     * The document id is percent-encoded, so `%2F` is a real separator INSIDE it. Splitting before
     * decoding would name the playlist "Music/Road Trip".
     */
    @Test fun `an encoded separator is decoded before the name is taken`() {
        val uri = "content://com.android.externalstorage.documents/document/" +
            "primary%3AMusic%2FSets%2FRoad%20Trip.m3u"
        assertEquals("Road Trip", PlaylistNaming.fromDocumentUri(uri))
    }

    @Test fun `a file uri is named by its last segment`() {
        assertEquals(
            "Road Trip",
            PlaylistNaming.fromDocumentUri("file:///storage/emulated/0/Music/Road%20Trip.m3u"),
        )
    }

    /**
     * A volume prefix is dropped; a colon that is part of the name is not. Splitting on the LAST
     * colon would turn "Mix: Volume Two" into " Volume Two".
     */
    @Test fun `a colon in the name survives the volume prefix being dropped`() {
        val uri = "content://com.android.externalstorage.documents/document/" +
            "primary%3AMix%3A%20Volume%20Two.m3u"
        assertEquals("Mix: Volume Two", PlaylistNaming.fromDocumentUri(uri))
    }

    @Test fun `a plus sign is a plus sign, not a space`() {
        assertEquals(
            "Rock + Roll",
            PlaylistNaming.fromDocumentUri("file:///storage/Music/Rock%20+%20Roll.m3u"),
        )
    }

    @Test fun `an unnameable document still gets a name`() {
        // The literal, for the same reason as COVER_LIMIT and MISSING_NOTE: comparing FALLBACK
        // against the function that returns FALLBACK holds for every value it could ever have,
        // including the empty string a nameless row cannot survive.
        assertEquals("Imported playlist", PlaylistNaming.FALLBACK)
        assertEquals(
            "Imported playlist",
            PlaylistNaming.fromDocumentUri("content://com.example.provider/document/"),
        )
    }

    @Test fun `a query string is not part of the name`() {
        assertEquals(
            "Road Trip",
            PlaylistNaming.fromDocumentUri("content://x/document/Road%20Trip.m3u?v=2"),
        )
    }

    @Test fun `a blank rename is refused rather than silently renaming to a fallback`() {
        assertNull(PlaylistNaming.sanitize("   "))
        assertEquals("Mix", PlaylistNaming.sanitize("  Mix  "))
    }

    // ------------------------------------------------------------------------------- reorder

    /**
     * Headers are items too — an off-by-two here measures the wrong row mid-drag.
     *
     * Asserted against LITERALS, not against `HEADER_ITEM_COUNT`. `lazyIndexOf(0) ==
     * HEADER_ITEM_COUNT` is true for every value the constant could possibly hold, so that form
     * pins nothing: a mutation setting it to 0 passed it. `PlaylistDetailScreen` draws exactly two
     * items before its first track — the parallax spacer and the title block — and 2 is what this
     * has to say.
     */
    @Test fun `a track's lazy index accounts for the two header items`() {
        assertEquals(2, PlaylistReorder.HEADER_ITEM_COUNT)
        assertEquals(2, PlaylistReorder.lazyIndexOf(0))
        assertEquals(7, PlaylistReorder.lazyIndexOf(5))
    }

    @Test fun `a drag lands on the row it has travelled over`() {
        assertEquals(3, PlaylistReorder.targetIndex(from = 0, dragPx = 300f, rowHeightPx = 100f, count = 10))
        assertEquals(0, PlaylistReorder.targetIndex(from = 3, dragPx = -300f, rowHeightPx = 100f, count = 10))
    }

    /** More than half a row taken over is a swap — otherwise every reorder lands one row late. */
    @Test fun `a drag past halfway takes the next row`() {
        assertEquals(1, PlaylistReorder.targetIndex(from = 0, dragPx = 51f, rowHeightPx = 100f, count = 10))
        assertEquals(0, PlaylistReorder.targetIndex(from = 0, dragPx = 49f, rowHeightPx = 100f, count = 10))
    }

    @Test fun `dragging off the end parks at the end`() {
        assertEquals(9, PlaylistReorder.targetIndex(from = 5, dragPx = 9_000f, rowHeightPx = 100f, count = 10))
        assertEquals(0, PlaylistReorder.targetIndex(from = 5, dragPx = -9_000f, rowHeightPx = 100f, count = 10))
    }

    @Test fun `an unmeasured row height cannot move anything`() {
        assertEquals(5, PlaylistReorder.targetIndex(from = 5, dragPx = 900f, rowHeightPx = 0f, count = 10))
    }

    @Test fun `rows between the origin and the target shift by one, and nothing else moves`() {
        // Dragging row 1 down to row 4: rows 2..4 rise by one, 0 and 5 stay put.
        assertEquals(0, PlaylistReorder.displacement(index = 0, from = 1, to = 4))
        assertEquals(0, PlaylistReorder.displacement(index = 1, from = 1, to = 4))
        assertEquals(-1, PlaylistReorder.displacement(index = 2, from = 1, to = 4))
        assertEquals(-1, PlaylistReorder.displacement(index = 4, from = 1, to = 4))
        assertEquals(0, PlaylistReorder.displacement(index = 5, from = 1, to = 4))
    }

    @Test fun `dragging upward pushes the rows it passes down`() {
        assertEquals(1, PlaylistReorder.displacement(index = 2, from = 4, to = 2))
        assertEquals(1, PlaylistReorder.displacement(index = 3, from = 4, to = 2))
        assertEquals(0, PlaylistReorder.displacement(index = 1, from = 4, to = 2))
        assertEquals(0, PlaylistReorder.displacement(index = 5, from = 4, to = 2))
    }

    @Test fun `a drag that has not moved displaces nothing`() {
        (0..5).forEach { assertEquals(0, PlaylistReorder.displacement(it, from = 3, to = 3)) }
    }

    /**
     * The projected position must follow the FINGER, not the accumulated drag. During an
     * auto-scroll the two diverge by exactly the scrolled distance, and using the accumulated
     * value would walk the projection off the screen and hold the scroll on at full speed.
     */
    @Test fun `the dragged row's screen position ignores distance the list scrolled`() {
        // Row 240px tall, starting 500px down the viewport, finger has moved 100px.
        assertEquals(
            720f,
            PlaylistReorder.draggedRowCenterY(anchorTopPx = 500f, fingerDeltaPx = 100f, rowHeightPx = 240f),
            0.001f,
        )
        // A finger that has not moved leaves the row where it started, whatever the list did.
        assertEquals(
            620f,
            PlaylistReorder.draggedRowCenterY(anchorTopPx = 500f, fingerDeltaPx = 0f, rowHeightPx = 240f),
            0.001f,
        )
    }

    @Test fun `the middle of the viewport does not auto-scroll`() {
        assertEquals(
            0f,
            PlaylistReorder.autoScrollPx(500f, viewportHeightPx = 1000f, edgePx = 120f, maxSpeedPx = 20f),
            0f,
        )
    }

    @Test fun `the top edge scrolls up and the bottom edge scrolls down`() {
        assertTrue(
            PlaylistReorder.autoScrollPx(10f, 1000f, edgePx = 120f, maxSpeedPx = 20f) < 0f,
        )
        assertTrue(
            PlaylistReorder.autoScrollPx(995f, 1000f, edgePx = 120f, maxSpeedPx = 20f) > 0f,
        )
    }

    @Test fun `auto-scroll never exceeds its top speed`() {
        val hard = PlaylistReorder.autoScrollPx(-500f, 1000f, edgePx = 120f, maxSpeedPx = 20f)
        assertEquals(-20f, hard, 0.001f)
    }
}
