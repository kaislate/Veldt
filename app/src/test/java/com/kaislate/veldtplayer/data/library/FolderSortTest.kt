// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering, which is where the folder view is SUPPOSED to disagree with the tag views.
 *
 * `String.CASE_INSENSITIVE_ORDER` — which `LibraryDerivations.ALBUM_ORDER` uses — puts `Disc 10`
 * before `Disc 2`, and it gets that wrong on exactly the directory names this feature exists to
 * display. The comparator here must be numeric-aware AND total: fold case for the primary
 * comparison, then fall back byte-exact, so two names differing only in case stay adjacent and
 * DISTINCT rather than collapsing.
 */
class FolderSortTest {

    private var nextId = 1L
    private fun song(
        fileName: String,
        title: String = fileName,
        track: Int? = null,
        disc: Int? = null,
        modified: Long = 0L,
    ) = Song(
        id = nextId++, sourceId = "test", externalId = "e${nextId}", uri = "content://x",
        filePath = null, relativeKey = "external_primary:Music/$fileName",
        title = title, artist = "a", album = "b", albumArtist = null,
        trackNumber = track, discNumber = disc, year = null,
        durationMs = 0L, dateModifiedSec = modified, hasEmbeddedArt = false,
    )

    private fun folder(name: String) = FolderNode(
        key = "external_primary:Music/$name", volume = "external_primary",
        segments = listOf("Music", name), name = name,
        children = emptyList(), songs = emptyList(),
        deepSongCount = 1, deepDurationMs = 0L, deepFolderCount = 0,
    )

    @Test fun `natural order puts Disc 2 before Disc 10`() {
        val sorted = FolderSort.folders(listOf(folder("Disc 10"), folder("Disc 2"), folder("Disc 1")))
        assertEquals(listOf("Disc 1", "Disc 2", "Disc 10"), sorted.map { it.name })
    }

    @Test fun `natural order handles CD1 through CD10`() {
        val sorted = FolderSort.folders(
            listOf(folder("CD10"), folder("CD1"), folder("CD9"), folder("CD2"))
        )
        assertEquals(listOf("CD1", "CD2", "CD9", "CD10"), sorted.map { it.name })
    }

    @Test fun `the comparator is TOTAL — two names differing only in case both survive`() {
        // Input is [beck, Beck] and the expected output is [Beck, beck], so this asserts ORDER and
        // not merely membership. That matters: `sortedWith` is stable and never drops elements, so
        // a size check and a Set check are unfalsifiable by ANY comparator change — the earlier
        // version of this test asserted exactly those two things and stayed green when the
        // byte-exact tiebreak was deleted. The ordered list is what makes the tiebreak load-bearing.
        val sorted = FolderSort.folders(listOf(folder("beck"), folder("Beck")))
        assertEquals(
            "a case-only difference collapsed — both folders must survive, adjacent and distinct",
            2, sorted.size,
        )
        assertEquals(
            "the byte-exact tiebreak is gone — a case-only difference no longer orders",
            listOf("Beck", "beck"), sorted.map { it.name },
        )
        assertNotEquals(
            "NATURAL.compare must never return 0 for two DIFFERENT strings",
            0, FolderSort.NATURAL.compare("beck", "Beck"),
        )
        // Case is FOLDED for the primary comparison, not merely broken by the byte-exact tiebreak,
        // and that needs its own assertion because NOTHING above can see it. Drop the fold and this
        // test still passes: 'B' 0x42 precedes 'b' 0x62, so [Beck, beck] comes out unchanged and
        // compare("beck","Beck") is still non-zero. The expected value is reachable by the very
        // mutation the test's own name claims to cover.
        //
        // Unfolded, every capitalised name sorts before every lowercase one — `Zebra` ahead of
        // `apple` in every folder list in the app.
        assertTrue(
            "case must be FOLDED for ordering — unfolded, Zebra sorts before apple",
            FolderSort.NATURAL.compare("apple", "Zebra") < 0,
        )
    }

    @Test fun `NATURAL is transitive — a non-ASCII digit must not sort as a number AND as a letter`() {
        // U+0665 ARABIC-INDIC DIGIT FIVE. `Char.isDigit()` is true for it, but a digit run is
        // compared by code unit with only ASCII '0' stripped, so before the branch was restricted
        // to ASCII this character compared as a NUMBER against a digit run and as an ordinary high
        // code unit against a letter. The executed cycle was:
        //     compare("٥", "10") < 0   digit branch, run length 1 < 2
        //     compare("10", "a")      < 0   char branch, '1' 0x31 < 'a' 0x61
        //     compare("a", "٥")  < 0   char branch, 'a' 0x61 < 0x0665
        // i.e. "٥" < "10" < "a" < "٥". TimSort detects such a cycle at >= 32 elements and
        // throws "Comparison method violates its general contract!"; below 32 it silently returns an
        // arbitrary order. Asserting that a sort merely COMPLETES would not catch the silent case,
        // so this asserts the pairwise comparisons are mutually consistent instead.
        val arabicFive = "٥"
        val names = listOf(arabicFive, "10", "a", "2", "٢", "b", "01", "Disc ٥")

        for (x in names) {
            for (y in names) {
                assertEquals(
                    "antisymmetry broken: compare($x,$y) and compare($y,$x) must be opposite signs",
                    Integer.signum(FolderSort.NATURAL.compare(x, y)),
                    -Integer.signum(FolderSort.NATURAL.compare(y, x)),
                )
            }
        }
        for (x in names) {
            for (y in names) {
                for (z in names) {
                    val xy = FolderSort.NATURAL.compare(x, y)
                    val yz = FolderSort.NATURAL.compare(y, z)
                    val xz = FolderSort.NATURAL.compare(x, z)
                    if (xy < 0 && yz < 0) {
                        assertTrue("not transitive: $x < $y < $z, yet compare($x,$z) = $xz", xz < 0)
                    }
                    if (xy > 0 && yz > 0) {
                        assertTrue("not transitive: $x > $y > $z, yet compare($x,$z) = $xz", xz > 0)
                    }
                }
            }
        }
    }

    @Test fun `filename order uses the numbers on the files, not the tags`() {
        // The whole premise: tags say otherwise and the filenames are right.
        //
        // Every key that could decide this ordering is set to CONTRADICT the file names, so the
        // FILENAME branch cannot be satisfied by any of them:
        //   - titles     Aaa/Bbb/Ccc ascend as the file names descend
        //   - trackNumber 1/2/3       ascends as the file names descend
        //   - dateModified 300/200/100 makes the newest file the one that must sort LAST
        // The mtimes are load-bearing and were added deliberately: with them all left at the 0L
        // default the date key was constant, DATE_MODIFIED's comparator fell through to its own
        // secondary file-name key, and substituting it for the FILENAME branch left this test
        // green. Do not drop them back to the default.
        val sorted = FolderSort.tracks(
            listOf(
                song("10 - j.mp3", title = "Aaa", track = 1, modified = 300L),
                song("02 - b.mp3", title = "Bbb", track = 2, modified = 200L),
                song("01 - a.mp3", title = "Ccc", track = 3, modified = 100L),
            ),
            TrackSort.FILENAME, descending = false,
        )
        assertEquals(listOf("01 - a.mp3", "02 - b.mp3", "10 - j.mp3"), sorted.map { it.fileNameOrEmpty() })
    }

    @Test fun `track-number order is available and uses disc then track`() {
        // The fixture is arranged AGAINST file-name order on purpose, and must stay that way.
        //
        //   file     disc  track
        //   a.mp3      2     1
        //   c.mp3      1     2
        //   d.mp3      1     1
        //   e.mp3      1   null     <- tagged disc, UNTAGGED track
        //   10.mp3   null  null     <- untagged, and supplied BEFORE 2.mp3
        //   2.mp3    null  null     <- untagged
        //
        // Correct (disc, then track, then file name) is [d, c, e, a, 2, 10]. Every degenerate
        // alternative differs, which is what makes the single assertion below able to fail:
        //   - file name only    -> [2, 10, a, c, d, e]   (TRACK_NUMBER collapsed into FILENAME)
        //   - disc only         -> [c, d, e, a, 2, 10]   (the track key deleted)
        //   - track only        -> [a, d, c, e, 2, 10]   (the disc key deleted)
        //   - no tiebreak       -> [d, c, e, a, 10, 2]   (the file-name key deleted)
        //   - plain compareTo   -> [d, c, e, a, 10, 2]   (tiebreak not NATURAL)
        //   - track ?: 0        -> [e, d, c, a, 2, 10]   (untagged track sorts FIRST, not last)
        //   - input order       -> [a, c, d, e, 10, 2]   (no sort at all)
        //
        // Three fixture properties are load-bearing:
        //
        // 1. 10.mp3 and 2.mp3 are UNTAGGED — both keys null, both falling to Int.MAX_VALUE — so
        //    they tie and the file-name tiebreak is the only key left. That is the situation in a
        //    folder of untagged files, where EVERY song ties and the sort would otherwise degrade
        //    to `sortedWith` stability, i.e. MediaStore's own order, which is what the user chose
        //    this sort to escape. Supplied 10-before-2 so stability alone gives the wrong answer.
        // 2. They are named 10 and 2 rather than x and y so the tiebreak's COMPARATOR is pinned as
        //    well as its existence: under plain `compareTo` "10.mp3" precedes "2.mp3", which is the
        //    exact failure this whole feature exists to repair. One pair now kills deletion,
        //    plain-compareTo and input-order at once.
        // 3. e.mp3 has a disc but NO track, which is the only song here exercising the track key's
        //    null default. Without it, `trackNumber ?: Int.MAX_VALUE` could be changed to `?: 0`
        //    and stay green — an untagged track would jump to the FRONT of its disc instead of the
        //    back — even though the identical mutation on discNumber was already caught.
        //
        // TWO separate holes were closed here and neither may be reopened. The brief's original
        // fixture had c.mp3 on disc 2/track 1 and a.mp3 on disc 1/track 2 expecting [a, c] — which
        // was also plain file-name order, so the whole TRACK_NUMBER branch could be deleted and
        // this stayed green. Reversing that fixed the file-name hole but left both songs on
        // DIFFERENT discs, so the disc key alone decided and the track key was never consulted —
        // deleting `.thenBy { trackNumber }` also stayed green. d.mp3 exists to share disc 1 with
        // c.mp3 and force the track key to break the tie. Keep at least two songs on one disc.
        val sorted = FolderSort.tracks(
            listOf(
                song("a.mp3", track = 1, disc = 2),
                song("c.mp3", track = 2, disc = 1),
                song("d.mp3", track = 1, disc = 1),
                song("e.mp3", disc = 1),
                song("10.mp3"),
                song("2.mp3"),
            ),
            TrackSort.TRACK_NUMBER, descending = false,
        )
        assertEquals(
            listOf("d.mp3", "c.mp3", "e.mp3", "a.mp3", "2.mp3", "10.mp3"),
            sorted.map { it.fileNameOrEmpty() },
        )
    }

    @Test fun `descending reverses every sort`() {
        val songs = listOf(song("01 - a.mp3"), song("02 - b.mp3"))
        assertEquals(
            listOf("02 - b.mp3", "01 - a.mp3"),
            FolderSort.tracks(songs, TrackSort.FILENAME, descending = true).map { it.fileNameOrEmpty() },
        )
    }

    @Test fun `an ASCII digit run STOPS at a non-ASCII digit — scanners match the branch`() {
        // The companion to the transitivity test, and it exists because that test CANNOT see this.
        //
        // `compareNatural` uses the ASCII-digit predicate in three places: the branch condition and
        // the two run scanners. Narrowing only the condition — leaving the scanners on
        // `Char.isDigit()` — still enters the numeric branch on the leading ASCII '1', then lets
        // the scanner absorb the trailing U+0665 into the run. "1٥" is then compared as a single
        // two-character "number" against "12": equal lengths, so it falls to a code-unit compare of
        // '٥' 0x0665 against '2' 0x32 and returns POSITIVE, where the run must stop at the '1' and
        // return negative on the shorter run.
        //
        // That variant is transitive — it sweeps clean — so nothing in the transitivity test moves.
        // This is a consistency defect, and it needs its own assertion or the scanners are
        // unpinned.
        assertTrue(
            "an ASCII digit run must stop at a non-ASCII digit, not absorb it into the number",
            FolderSort.NATURAL.compare("1٥", "12") < 0,
        )
        // Same property, second pair: the run is the single digit 1, so it sorts before the run 2.
        // With the scanners widened the run becomes "1٥", which is LONGER than "2" and so sorts
        // after it — sign flipped again.
        assertTrue(
            "the run is 1, which sorts before 2 — the trailing U+0665 must not lengthen it",
            FolderSort.NATURAL.compare("1٥", "2") < 0,
        )
    }

    @Test fun `zero padding is stripped, and a leftover remainder still orders`() {
        // Two survivors inside compareNatural that no other assertion in this class claims.
        //
        // 1. `trimStart('0')`. Zero-padded and unpadded numbers must compare by VALUE, so "01"
        //    and "1" are the same number and "01" precedes "2". Delete the trim and the runs are
        //    compared by raw length instead: "01" is two characters against "2"'s one, so the
        //    padded track sorts AFTER — `01 - a.mp3` filed behind `2 - b.mp3` in a folder mixing
        //    padded and unpadded names, which is a mix real libraries have.
        assertTrue(
            "leading zeros must be stripped — 01 is the number 1 and precedes 2",
            FolderSort.NATURAL.compare("01", "2") < 0,
        )
        // 2. The trailing `(a.length - i) - (b.length - j)`. After two runs compare equal, whatever
        //    is left over decides: "01x" and "1" agree on the number, and "01x" has characters
        //    remaining, so it sorts after. Replace that return with 0 and the byte-exact tiebreak
        //    decides instead — '0' before '1' — flipping the pair.
        assertTrue(
            "equal numeric runs, then the string with characters left over sorts after",
            FolderSort.NATURAL.compare("01x", "1") > 0,
        )
    }

    @Test fun `title order uses the tag title, not the file name`() {
        // The file names and the titles are deliberately in OPPOSITE orders, so a TITLE branch
        // that fell through to FILENAME would produce the exact reverse and fail loudly.
        val sorted = FolderSort.tracks(
            listOf(
                song("01 - zebra.mp3", title = "Zebra"),
                song("02 - apple.mp3", title = "Apple"),
                song("03 - mango.mp3", title = "Mango"),
            ),
            TrackSort.TITLE, descending = false,
        )
        assertEquals(listOf("Apple", "Mango", "Zebra"), sorted.map { it.title })
        assertEquals(
            listOf("02 - apple.mp3", "03 - mango.mp3", "01 - zebra.mp3"),
            sorted.map { it.fileNameOrEmpty() },
        )
    }

    @Test fun `date-modified order is NEWEST FIRST — the "what did I just add" default`() {
        // 10.mp3 and 2.mp3 SHARE an mtime, and are supplied 10-before-2 so that stability alone
        // yields the wrong order. That is not a contrived case: `dateModifiedSec` is
        // second-granularity and a bulk copy or unzip stamps a whole album identically, so the
        // file-name tiebreak is the common path rather than the rare one. With three distinct
        // mtimes the tiebreak was never consulted and could be deleted with the suite green.
        //
        // The names are 10 and 2 rather than tie-a and tie-b so that the tiebreak's COMPARATOR is
        // pinned too: `.thenBy(NATURAL)` weakened to a plain `.thenBy` puts "10.mp3" before
        // "2.mp3", and with neutral names that mutation survived green.
        val sorted = FolderSort.tracks(
            listOf(
                song("old.mp3", modified = 100L),
                song("new.mp3", modified = 300L),
                song("10.mp3", modified = 200L),
                song("2.mp3", modified = 200L),
            ),
            TrackSort.DATE_MODIFIED, descending = false,
        )
        assertEquals(
            listOf("new.mp3", "2.mp3", "10.mp3", "old.mp3"),
            sorted.map { it.fileNameOrEmpty() },
        )
    }

    @Test fun `date-modified descending means OLDEST first — the double negative is deliberate`() {
        // DATE_MODIFIED is built with compareByDescending and `descending` then reverses the whole
        // list, so the two negatives cancel. Both directions are pinned here on purpose: a later
        // reader "simplifying" compareByDescending to compareBy would silently invert the default
        // for every user, and nothing else in this class would notice.
        val songs = listOf(
            song("old.mp3", modified = 100L),
            song("new.mp3", modified = 300L),
            song("mid.mp3", modified = 200L),
        )
        assertEquals(
            "default must be newest first",
            listOf("new.mp3", "mid.mp3", "old.mp3"),
            FolderSort.tracks(songs, TrackSort.DATE_MODIFIED, descending = false)
                .map { it.fileNameOrEmpty() },
        )
        assertEquals(
            "descending must flip it to oldest first",
            listOf("old.mp3", "mid.mp3", "new.mp3"),
            FolderSort.tracks(songs, TrackSort.DATE_MODIFIED, descending = true)
                .map { it.fileNameOrEmpty() },
        )
    }

    @Test fun `the deep flatten is DEPTH-FIRST PRE-ORDER — discs never interleave`() {
        // The case that motivates the whole feature.
        //
        //   Album/                 00 - intro.mp3, zz - outro.mp3
        //     Disc 2/              d2a.mp3, d2b.mp3
        //     Disc 1/              d1a.mp3, d1b.mp3
        //       Bonus/             b1.mp3, b2.mp3
        //
        // TWO fixture properties are deliberate and the test is unfalsifiable without them:
        //
        // 1. THREE levels. `Bonus/` is nested inside `Disc 1/` rather than beside it. On a
        //    two-level tree breadth-first and depth-first pre-order emit the SAME sequence, so a
        //    BFS mutation could not fail. `Bonus/`'s songs must appear between `Disc 1/`'s and
        //    `Disc 2/`'s, which is exactly what BFS gets wrong — it defers them to the end.
        //
        // 2. `zz - outro.mp3` sorts AFTER every descendant's name. A globally-flat sort of all
        //    eight names would drag it to the end and pull b1/b2 to the front; keeping it pinned
        //    to second position, directly under its own folder's other song, is what a flat sort
        //    cannot reproduce. Without it every name happened to sort into tree order anyway and
        //    a flat sort passed — the alternative this test's own name calls the exact failure
        //    the folder view exists to repair.
        //
        // Do not flatten the nesting and do not rename `zz - outro.mp3` to sort earlier.
        val album = FolderNode(
            key = "external_primary:Music/Album", volume = "external_primary",
            segments = listOf("Music", "Album"), name = "Album",
            songs = listOf(song("00 - intro.mp3"), song("zz - outro.mp3")),
            children = listOf(
                FolderNode(
                    key = "external_primary:Music/Album/Disc 2", volume = "external_primary",
                    segments = listOf("Music", "Album", "Disc 2"), name = "Disc 2",
                    children = emptyList(),
                    songs = listOf(song("d2a.mp3"), song("d2b.mp3")),
                    deepSongCount = 2, deepDurationMs = 0L, deepFolderCount = 0,
                ),
                FolderNode(
                    key = "external_primary:Music/Album/Disc 1", volume = "external_primary",
                    segments = listOf("Music", "Album", "Disc 1"), name = "Disc 1",
                    children = listOf(
                        FolderNode(
                            key = "external_primary:Music/Album/Disc 1/Bonus",
                            volume = "external_primary",
                            segments = listOf("Music", "Album", "Disc 1", "Bonus"), name = "Bonus",
                            children = emptyList(),
                            songs = listOf(song("b1.mp3"), song("b2.mp3")),
                            deepSongCount = 2, deepDurationMs = 0L, deepFolderCount = 0,
                        ),
                    ),
                    songs = listOf(song("d1a.mp3"), song("d1b.mp3")),
                    deepSongCount = 4, deepDurationMs = 0L, deepFolderCount = 1,
                ),
            ),
            deepSongCount = 8, deepDurationMs = 0L, deepFolderCount = 3,
        )
        assertEquals(
            listOf(
                "00 - intro.mp3", "zz - outro.mp3",   // Album's own, before any descendant
                "d1a.mp3", "d1b.mp3",                 // Disc 1 (sorted before Disc 2)
                "b1.mp3", "b2.mp3",                   // Disc 1/Bonus — BFS would defer these
                "d2a.mp3", "d2b.mp3",                 // Disc 2
            ),
            FolderSort.deepFlatten(album, TrackSort.FILENAME, descending = false)
                .map { it.fileNameOrEmpty() },
        )
    }

    private fun Song.fileNameOrEmpty(): String = location()?.fileName.orEmpty()
}
