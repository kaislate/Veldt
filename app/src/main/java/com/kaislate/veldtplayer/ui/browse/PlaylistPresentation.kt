// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import com.kaislate.veldtplayer.data.library.DisplayNames
import com.kaislate.veldtplayer.data.library.LibraryKeys
import com.kaislate.veldtplayer.data.library.displayAlbum
import com.kaislate.veldtplayer.data.library.displayArtist
import com.kaislate.veldtplayer.data.library.displayTitle
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.playlist.PlaylistTrack
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntity
import com.kaislate.veldtplayer.data.playlist.m3u.ImportResult
import com.kaislate.veldtplayer.data.playlist.m3u.M3uEntry
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Every decision the playlist screens make, as pure functions.
 *
 * Compose composables are effectively untestable in this project's JVM suite, and P1.4 has already
 * shipped one defect — Task 9's unpinnable `setArtworkUri` — that existed only because the logic
 * sat somewhere no test could reach. So the composables in `PlaylistsScreen` and
 * `PlaylistDetailScreen` are deliberately thin: what a row says, whether it can be played, whether
 * a screen is loading or genuinely empty, how an import is worded and what a drag lands on are all
 * decided here, where `PlaylistPresentationTest` pins them.
 */

// ---------------------------------------------------------------------------- the playlists tab

/**
 * One playlist as the tab lists it.
 *
 * [missingCount] is surfaced rather than hidden for the same reason an unresolved row is: a
 * playlist that quietly reports "43 tracks" when it holds 47, four of which no longer resolve, is
 * indistinguishable from one that lost four. [trackCount] is therefore ALWAYS the row count.
 *
 * [covers] is the seam Task 7's `PlaylistMosaic` consumes. It is the artwork the mosaic gets to
 * slot, already de-duplicated by album so that a playlist holding eight tracks off one record and
 * one off another offers the mosaic two covers rather than one; the slot *layout* decision (0/1/3/4
 * up, never 2-with-a-hole) belongs to Task 7 and is deliberately not made here.
 */
data class PlaylistCard(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val missingCount: Int,
    val covers: List<Song>,
)

/** What the playlists tab is showing. Loading is a real third state — see [PlaylistPresentation.listStateOf]. */
sealed interface PlaylistsUiState {
    /** Room has not answered yet. NOT the same thing as [Empty], and never rendered as it. */
    data object Loading : PlaylistsUiState

    /** Room answered, and there are genuinely no playlists. */
    data object Empty : PlaylistsUiState

    data class Ready(val cards: List<PlaylistCard>) : PlaylistsUiState
}

// ------------------------------------------------------------------------- one playlist's tracks

/**
 * One row of a playlist's track list.
 *
 * [song] is null for an entry that does not currently resolve to anything in the library. Such a
 * row is rendered greyed, under the title and artist the import captured, with a one-line
 * explanation, and it is NOT playable — but it is never removed from the list. See
 * [PlaylistPresentation.rowsOf].
 */
data class PlaylistTrackRow(
    val entryId: Long,
    val title: String,
    val subtitle: String,
    val song: Song?,
) {
    val playable: Boolean get() = song != null
}

/** What the playlist detail screen is showing. */
sealed interface PlaylistDetailUiState {
    /** Room has not answered yet — the initial value, never produced by a mapping. */
    data object Loading : PlaylistDetailUiState

    /** Room answered and this playlist is not there: it was deleted while the screen was open. */
    data object Missing : PlaylistDetailUiState

    data class Ready(
        val name: String,
        val rows: List<PlaylistTrackRow>,
        val covers: List<Song>,
    ) : PlaylistDetailUiState
}

/** A queue and the position in it to start at — see [PlaylistPresentation.playTarget]. */
data class PlayTarget(val queue: List<Song>, val startIndex: Int)

// ------------------------------------------------------------------------------------ importing

/**
 * Why an import failed, as four distinguishable causes.
 *
 * They are an enum rather than four strings because the two that matter most are thrown by the same
 * call and are NOT the same thing to a user: a [PERMISSION_LAPSED] grant is fixed by picking the
 * file again, and an [UNREADABLE] file never will be. Collapsing them into one "import failed"
 * message is the defect class this phase keeps producing, so the wording is pinned per cause.
 */
enum class ImportFailure {
    PERMISSION_LAPSED,
    MISSING_FILE,
    UNREADABLE,

    /**
     * There is no document picker on this device, so no file was ever chosen.
     *
     * Its own cause rather than [UNEXPECTED], because it is the one failure with **no retry**:
     * offering "Pick a file" here re-launches the picker that just threw, forever, and the copy
     * would talk about "that file" when the user never got as far as choosing one.
     */
    PICKER_UNAVAILABLE,
    UNEXPECTED,
}

/** The result of an attempted import, success or failure, held until the user dismisses it. */
sealed interface ImportOutcome {
    data class Done(val result: ImportResult) : ImportOutcome
    data class Failed(val failure: ImportFailure) : ImportOutcome
}

object PlaylistPresentation {

    /** The one-line explanation an unresolved row carries. */
    const val MISSING_NOTE = "Not in your library"

    /** How many covers the Task 7 mosaic is offered. Its own slotting decides how many it uses. */
    const val COVER_LIMIT = 4

    // ------------------------------------------------------------------------ the playlists tab

    /**
     * Room answered: [cards] is the truth, empty or not.
     *
     * [PlaylistsUiState.Loading] is deliberately unreachable from here. It is the flow's INITIAL
     * value, so "we have not asked yet" and "there is nothing" can never be produced by the same
     * input — which is exactly how P1.3 came to render "No songs yet" over a library mid-scan.
     */
    fun listStateOf(cards: List<PlaylistCard>): PlaylistsUiState =
        if (cards.isEmpty()) PlaylistsUiState.Empty else PlaylistsUiState.Ready(cards)

    fun cardOf(playlist: PlaylistEntity, tracks: List<PlaylistTrack>): PlaylistCard = PlaylistCard(
        id = playlist.id,
        name = playlist.name,
        trackCount = tracks.size,
        missingCount = tracks.count { it.song == null },
        covers = coversOf(tracks),
    )

    /**
     * The artwork a playlist offers its mosaic: resolved tracks only, in playlist order, one per
     * album, capped at [COVER_LIMIT].
     *
     * De-duplicating by album key rather than by song is what stops a playlist that opens with a
     * whole record from handing the mosaic four copies of one cover. The key is
     * [LibraryKeys.albumKey], the compound one, so two different records both called "Greatest
     * Hits" stay two covers.
     */
    fun coversOf(tracks: List<PlaylistTrack>): List<Song> = tracks
        .mapNotNull { it.song }
        .distinctBy { LibraryKeys.albumKey(it) }
        .take(COVER_LIMIT)

    /**
     * "12 tracks", "12 tracks · 2 missing", "Empty".
     *
     * The missing count is part of the caption because the tab is the first place a user could
     * notice that a playlist stopped resolving — and because the count they see here has to agree
     * with the number of greyed rows they find inside.
     */
    fun caption(trackCount: Int, missingCount: Int): String = when {
        trackCount <= 0 -> "Empty"
        missingCount <= 0 -> countOf(trackCount, "track")
        else -> "${countOf(trackCount, "track")} · $missingCount missing"
    }

    // -------------------------------------------------------------------- one playlist's tracks

    /**
     * Every entry becomes a row. **Nothing is ever filtered.**
     *
     * `resolve()` returns unresolved entries on purpose; dropping them here would undo the entire
     * reason the schema keeps them, and would be invisible — the user has no way to tell a playlist
     * that lost four tracks from one that only ever had 43.
     */
    fun rowsOf(tracks: List<PlaylistTrack>): List<PlaylistTrackRow> = tracks.map(::rowOf)

    /**
     * One row.
     *
     * A **resolved** entry is described by the library, like every other list in the app. An
     * **unresolved** one keeps what the import captured — its own title and artist — and says
     * plainly why it cannot be played. The two are told apart by [PlaylistTrackRow.song], never by
     * inspecting the strings.
     */
    fun rowOf(track: PlaylistTrack): PlaylistTrackRow {
        val song = track.song
        if (song != null) {
            return PlaylistTrackRow(
                entryId = track.entry.id,
                title = song.displayTitle(),
                subtitle = "${song.displayArtist()} · ${song.displayAlbum()}",
                song = song,
            )
        }
        val artist = track.entry.sourceArtist.trim()
        return PlaylistTrackRow(
            entryId = track.entry.id,
            title = DisplayNames.title(track.entry.sourceTitle),
            // The explanation is part of the SUBTITLE rather than a third line so that every row
            // in the list is exactly one row tall. The drag-to-reorder maths measures one row and
            // applies it to all of them; a taller missing row would make every reorder past one
            // land in the wrong place.
            subtitle = if (artist.isEmpty()) MISSING_NOTE else "$artist · $MISSING_NOTE",
            song = null,
        )
    }

    /**
     * What tapping row [index] should play, or null when that row is not playable.
     *
     * The queue is the playlist's RESOLVED tracks and the start position is counted in that queue,
     * not in the displayed list. Passing the display index straight to the player would start
     * playback on the wrong track for every playlist with a missing entry above the tap — the two
     * indices only agree when nothing is missing, which is precisely the case that would not catch
     * it.
     */
    fun playTarget(rows: List<PlaylistTrackRow>, index: Int): PlayTarget? {
        val row = rows.getOrNull(index) ?: return null
        if (row.song == null) return null
        return PlayTarget(
            queue = rows.mapNotNull { it.song },
            startIndex = rows.take(index).count { it.song != null },
        )
    }

    /**
     * The detail screen's state for one observation.
     *
     * A null [playlist] here means DELETED, not "not loaded": this is only ever called on an
     * emission, and [PlaylistDetailUiState.Loading] is the flow's initial value. An existing
     * playlist with no entries is [PlaylistDetailUiState.Ready] with no rows — an empty playlist
     * and a deleted one are two different screens.
     */
    fun detailStateOf(
        playlist: PlaylistEntity?,
        tracks: List<PlaylistTrack>,
    ): PlaylistDetailUiState {
        if (playlist == null) return PlaylistDetailUiState.Missing
        return PlaylistDetailUiState.Ready(
            name = playlist.name,
            rows = rowsOf(tracks),
            covers = coversOf(tracks),
        )
    }

    // ------------------------------------------------------------------------------- importing

    /**
     * Run [block] and turn anything it throws into a reportable [ImportOutcome].
     *
     * **This is the crash guard.** `PlaylistImporter.import` throws [IOException] for a document it
     * cannot read or that exceeds its size cap, and [SecurityException] when the SAF grant has
     * lapsed — which happens for real, after process death, to a user who simply picked a file.
     * Nothing between the picker and here catches either, so without this the app dies on a
     * permission that expired. A failure is a message, never a stack trace.
     *
     * [CancellationException] is re-thrown before anything else: swallowing it would leave a
     * cancelled coroutine reporting a fake failure and would break structured concurrency.
     *
     * The trailing `Exception` arm is a last resort for a hostile document provider, not a
     * substitute for the named arms — each cause maps to its own [ImportFailure] and its own
     * message, and `PlaylistPresentationTest` asserts the mapping per cause rather than merely
     * asserting that nothing escaped. Assert-nothing-escaped alone would be satisfied by the
     * catch-all, which would make removing the [SecurityException] arm undetectable.
     */
    suspend fun importOutcome(block: suspend () -> ImportResult): ImportOutcome =
        try {
            ImportOutcome.Done(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            ImportOutcome.Failed(ImportFailure.PERMISSION_LAPSED)
        } catch (e: FileNotFoundException) {
            ImportOutcome.Failed(ImportFailure.MISSING_FILE)
        } catch (e: IOException) {
            ImportOutcome.Failed(ImportFailure.UNREADABLE)
        } catch (e: Exception) {
            ImportOutcome.Failed(ImportFailure.UNEXPECTED)
        }
}

/**
 * The words an import report is made of.
 *
 * Separate from [PlaylistPresentation] because it is one coherent piece of copy with one rule
 * behind it: **say the number, and name the ones that did not make it.** "Imported 43 of 47" with
 * the four listed is the whole point of `ImportResult`; a toast reading "Imported" would throw
 * away the only signal the user gets that a playlist arrived incomplete.
 */
object PlaylistImportReport {

    /** "Imported 43 of 47 tracks" — or the honest thing when nothing was dropped, or nothing read. */
    fun headline(result: ImportResult): String = when {
        result.total <= 0 -> "That file listed no tracks"
        result.unresolved.isEmpty() -> "Imported ${countOf(result.total, "track")}"
        else -> "Imported ${result.resolved} of ${countOf(result.total, "track")}"
    }

    /** The line under the headline: what the unmatched tracks mean and what happens to them. */
    fun detail(result: ImportResult): String {
        val missing = result.unresolved.size
        if (result.total <= 0) {
            return "Veldt read the file but found no track paths in it. It may not be a playlist."
        }
        if (missing == 0) return "Every track matched something in your library."
        val subject = if (missing == 1) "One track isn't" else "$missing tracks aren't"
        return "$subject in your library yet. They stay in the playlist, greyed out, and link " +
            "themselves up if the files come back."
    }

    /** What to call an unmatched track: what `#EXTINF` claimed, else the file's own name. */
    fun unresolvedLabel(entry: M3uEntry): String {
        val claimed = entry.title?.trim().orEmpty()
        if (claimed.isNotEmpty()) return claimed
        return fileNameOf(entry.path).ifEmpty { entry.path }
    }

    /**
     * The second line of an unmatched track: its artist if the file named one, otherwise the path
     * itself — which is the only other thing that could help the user work out which file it was.
     */
    fun unresolvedDetail(entry: M3uEntry): String {
        val artist = entry.artist?.trim().orEmpty()
        return if (artist.isNotEmpty()) artist else entry.path
    }

    /** One headline per cause — see [ImportFailure] for why these are not one string. */
    fun failureHeadline(failure: ImportFailure): String = when (failure) {
        ImportFailure.PERMISSION_LAPSED -> "Veldt lost access to that file"
        ImportFailure.MISSING_FILE -> "That file isn't there any more"
        ImportFailure.UNREADABLE -> "That file couldn't be read"
        ImportFailure.PICKER_UNAVAILABLE -> "This device has no file picker"
        ImportFailure.UNEXPECTED -> "Something went wrong importing that file"
    }

    /** One remedy per cause. A message the user can act on is the whole reason failures are caught. */
    fun failureDetail(failure: ImportFailure): String = when (failure) {
        ImportFailure.PERMISSION_LAPSED ->
            "The permission Android granted for it has expired. Pick the playlist again and " +
                "Veldt will import it straight away."
        ImportFailure.MISSING_FILE ->
            "It was moved or deleted after you picked it. Nothing was imported."
        ImportFailure.UNREADABLE ->
            "It may be far too large for a playlist, or the app it came from stopped responding. " +
                "Nothing was imported."
        ImportFailure.PICKER_UNAVAILABLE ->
            "Veldt asks Android to show a file picker, and this build of Android has none " +
                "installed. Installing a Files or Documents app will let Veldt import playlists."
        ImportFailure.UNEXPECTED ->
            "Nothing was imported. If it happens again with the same file, it is probably not a " +
                "playlist Veldt can read."
    }

    /**
     * Whether offering to pick a file again could possibly help.
     *
     * False for exactly one cause, and the distinction is not cosmetic: with no picker installed,
     * a "Pick a file" button re-launches the thing that just threw and lands the user back on this
     * same dialog, forever. A button that cannot work should not be drawn.
     */
    fun retryable(failure: ImportFailure): Boolean = failure != ImportFailure.PICKER_UNAVAILABLE
}

/**
 * What to call a playlist the user just picked, and what to accept when they rename one.
 *
 * Pure and framework-free — no `android.net.Uri` — because a name derived from a document id is
 * pure string surgery with several ways to get it subtly wrong, and those are worth pinning on the
 * JVM rather than under Robolectric.
 */
object PlaylistNaming {

    /** What an unnameable document becomes. Never blank: a nameless row is unusable. */
    const val FALLBACK = "Imported playlist"

    /**
     * The playlist's name: the provider's own `DISPLAY_NAME` when it gave one, else whatever the
     * uri can be made to yield.
     *
     * The display name is preferred because it is the only thing that is right for an opaque
     * provider — Downloads hands back a document id of `1000000042`, and a playlist called
     * "1000000042" is worse than no import at all.
     */
    fun of(displayName: String?, uri: String): String {
        val fromProvider = stripExtension(displayName.orEmpty().trim()).trim()
        if (fromProvider.isNotEmpty()) return fromProvider
        return fromDocumentUri(uri)
    }

    /**
     * A name derived from the uri alone.
     *
     * The last `/`-separated segment is the document id, still percent-encoded — so it is decoded
     * FIRST and only then split, because `%2F` is a real separator inside it and splitting before
     * decoding would keep the whole `Music/list.m3u` as one name.
     */
    fun fromDocumentUri(uri: String): String {
        val path = uri.substringBefore('?').substringBefore('#')
        val decoded = percentDecode(path.substringAfterLast('/'))
        val withoutVolume = stripVolumePrefix(decoded)
        val fileName = fileNameOf(withoutVolume)
        return stripExtension(fileName).trim().ifEmpty { FALLBACK }
    }

    /**
     * Trim a rename to something storable, or null if the user cleared the field.
     *
     * Null rather than a fallback: an empty rename is a mistake to ignore, not a request to call
     * the playlist "Imported playlist".
     */
    fun sanitize(input: String): String? = input.trim().ifEmpty { null }

    /**
     * Drop an external-storage document id's `volume:` prefix — `primary:Music/list.m3u`.
     *
     * Only when the prefix genuinely looks like a volume: no separator and no space before the
     * FIRST colon. A playlist honestly called `Mix: Volume Two.m3u` keeps its colon, and
     * `primary:Mix: Volume Two.m3u` loses only the volume — splitting on the last colon would eat
     * half of both names.
     */
    private fun stripVolumePrefix(value: String): String {
        val colon = value.indexOf(':')
        if (colon <= 0) return value
        val prefix = value.substring(0, colon)
        if (prefix.any { it == '/' || it == '\\' || it.isWhitespace() }) return value
        return value.substring(colon + 1)
    }

    private fun stripExtension(name: String): String = when {
        name.endsWith(".m3u8", ignoreCase = true) -> name.dropLast(".m3u8".length)
        name.endsWith(".m3u", ignoreCase = true) -> name.dropLast(".m3u".length)
        else -> name
    }

    /**
     * Decode `%XX` escapes as UTF-8.
     *
     * Hand-rolled rather than `URLDecoder`, which also turns `+` into a space — correct for a form
     * body and wrong for a uri, where a playlist called `Rock + Roll` would come back as
     * `Rock   Roll`. A malformed escape is left verbatim rather than throwing: a bad name is
     * recoverable, an exception in a picker callback is not.
     */
    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val hex = if (c == '%' && i + 3 <= value.length) {
                value.substring(i + 1, i + 3).toIntOrNull(16)
            } else {
                null
            }
            if (hex != null) {
                out.write(hex)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

/** The file's own name, whichever separator the platform that wrote the path used. */
internal fun fileNameOf(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\')

/**
 * The arithmetic behind drag-to-reorder, away from the gesture that drives it.
 *
 * A reorder is one of those places where the code is easy to write, easy to believe, and wrong by
 * one for every list with a header in it — so the two mappings that can be off (list index to lazy
 * index, and drag distance to landing index) are functions with tests rather than expressions
 * inside a `pointerInput` block nothing can reach.
 */
object PlaylistReorder {

    /**
     * How many items the detail list draws before its first track: the parallax spacer and the
     * title block. Shared with `PlaylistDetailScreen` so the two cannot drift — an off-by-two here
     * scrolls to the wrong row during a drag.
     */
    const val HEADER_ITEM_COUNT = 2

    /** Where track [entryIndex] sits in the lazy list, headers included. */
    fun lazyIndexOf(entryIndex: Int): Int = entryIndex + HEADER_ITEM_COUNT

    /**
     * Where a drag that started on [from] and has travelled [dragPx] should land.
     *
     * Rounded, not truncated: a row is taken over once the dragged row covers more than half of it,
     * which is what makes the swap happen where the finger is rather than one row late. Clamped to
     * the list, so dragging off either end parks at the end rather than reordering to nowhere.
     */
    fun targetIndex(from: Int, dragPx: Float, rowHeightPx: Float, count: Int): Int {
        if (count <= 0) return 0
        if (rowHeightPx <= 0f || !rowHeightPx.isFinite() || !dragPx.isFinite()) {
            return from.coerceIn(0, count - 1)
        }
        return (from + (dragPx / rowHeightPx).roundToInt()).coerceIn(0, count - 1)
    }

    /**
     * How many row heights the row at [index] shifts while a drag from [from] is hovering [to],
     * in rows: -1 up, +1 down, 0 for everything outside the span (and for the dragged row, which
     * follows the finger instead).
     */
    fun displacement(index: Int, from: Int, to: Int): Int = when {
        index == from -> 0
        from < to && index > from && index <= to -> -1
        from > to && index < from && index >= to -> 1
        else -> 0
    }

    /**
     * Where the dragged row is on SCREEN, vertically, in viewport pixels.
     *
     * The gesture reports its movement in the drag handle's own coordinates, which say nothing
     * about where in the viewport the finger is — and the auto-scroll edge test is a question
     * about the viewport. So the position is reconstructed instead: the row's offset when the drag
     * began, plus how far the FINGER has moved since, plus half a row to land on its middle.
     *
     * [fingerDeltaPx] is deliberately not the same accumulator the landing index uses. That one
     * also counts distance the LIST scrolled underneath, because scrolled distance really does
     * carry the row further through the order — but it does not move the finger, and adding it
     * here would walk the projected position off the bottom of the screen and hold the auto-scroll
     * on at full speed for the rest of the drag.
     */
    fun draggedRowCenterY(anchorTopPx: Float, fingerDeltaPx: Float, rowHeightPx: Float): Float =
        anchorTopPx + fingerDeltaPx + rowHeightPx / 2f

    /**
     * How far to scroll this frame while a drag is held near an edge of the viewport, in pixels —
     * negative for up. Zero in the middle, ramping to [maxSpeedPx] at the very edge, so a slow
     * approach creeps and a hard push runs.
     *
     * Without this a playlist longer than the screen can only be reordered within the rows that
     * happen to be visible, which for the lists this feature exists for is most of them.
     */
    fun autoScrollPx(
        pointerY: Float,
        viewportHeightPx: Float,
        edgePx: Float,
        maxSpeedPx: Float,
    ): Float {
        if (edgePx <= 0f || viewportHeightPx <= 0f) return 0f
        if (pointerY < edgePx) {
            return -maxSpeedPx * ((edgePx - pointerY) / edgePx).coerceIn(0f, 1f)
        }
        val bottomEdge = viewportHeightPx - edgePx
        if (pointerY > bottomEdge) {
            return maxSpeedPx * ((pointerY - bottomEdge) / edgePx).coerceIn(0f, 1f)
        }
        return 0f
    }
}
