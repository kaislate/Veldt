// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `stableKey` vs `resolvePlayableUri` — two different questions, and the whole reason playlist
 * entries survive a rescan.
 *
 * Neither function touches MediaStore, so these run against a real [LocalSource] without any
 * provider setup; Robolectric is here only to supply the Context the constructor takes.
 *
 * This is the guard the playlist tests structurally cannot provide: they use a fake source, so a
 * regression in *this* implementation would be invisible to them.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin the SDK so this starts under targetSdk 36.
@Config(sdk = [34])
class LocalSourceKeysTest {

    private lateinit var source: LocalSource

    @Before fun setUp() {
        source = LocalSource(ApplicationProvider.getApplicationContext())
    }

    private fun song(id: Long, filePath: String?, relativeKey: String? = null) = Song(
        id = id,
        // Named "test-source", NOT LocalSource's own "local": nothing in `stableKey` may read the
        // source dimension, and a fixture that agreed with the production literal would hide it if
        // something started to.
        sourceId = "test-source",
        externalId = "ms-${id + 9000}",
        uri = "content://media/external/audio/media/$id",
        filePath = filePath,
        relativeKey = relativeKey,
        title = "Alpha",
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = 1000L,
        dateModifiedSec = 0L,
        hasEmbeddedArt = false,
    )

    /**
     * THE property. A rescan deletes and reinserts the row under a new MediaStore `_ID` — same
     * file, same path. The stable key must not notice. Keyed on the uri it would change, and the
     * playlist entry pointing at it would go permanently blank.
     */
    @Test fun `the same file under a different MediaStore id yields the same stable key`() {
        val before = song(id = 3, filePath = "/storage/emulated/0/Music/a.mp3")
        val after = song(id = 7, filePath = "/storage/emulated/0/Music/a.mp3")
        assertEquals(source.stableKey(before), source.stableKey(after))
        // ...whereas the thing we deliberately did NOT key on does change.
        assertNotEquals(source.resolvePlayableUri(before), source.resolvePlayableUri(after))
    }

    // ---- the three-rung ladder --------------------------------------------------------------

    /** Rung 1 wins outright: it is the guaranteed-present, non-deprecated one. */
    @Test fun `stableKey prefers the relative key over the DATA path`() {
        val s = song(3, "/storage/emulated/0/Music/a.mp3", relativeKey = "external_primary:Music/a.mp3")
        assertEquals("external_primary:Music/a.mp3", source.stableKey(s))
    }

    /** Rung 2: DATA is fully qualified when present, and is what the tag reader already uses. */
    @Test fun `stableKey falls back to the DATA path when there is no relative key`() {
        assertEquals(
            "/storage/emulated/0/Music/a.mp3",
            source.stableKey(song(3, "/storage/emulated/0/Music/a.mp3", relativeKey = null)),
        )
    }

    /**
     * Rung 1 present, rung 2 absent — the case that used to be silent. Before this round a row
     * with no DATA fell straight to the uri and carried a key that could not survive a rescan,
     * with nothing anywhere to surface it.
     */
    @Test fun `stableKey works when DATA is withheld but the relative key is present`() {
        val s = song(3, filePath = null, relativeKey = "external_primary:Music/a.mp3")
        assertEquals("external_primary:Music/a.mp3", source.stableKey(s))
        assertNotEquals(source.resolvePlayableUri(s), source.stableKey(s))
    }

    /** The same file, no DATA at all, before and after a rescan reissues its id. */
    @Test fun `a file with no DATA path still keys stably across an id reissue`() {
        val before = song(3, filePath = null, relativeKey = "external_primary:Music/a.mp3")
        val after = song(7, filePath = null, relativeKey = "external_primary:Music/a.mp3")
        assertEquals(source.stableKey(before), source.stableKey(after))
        assertNotEquals(source.resolvePlayableUri(before), source.resolvePlayableUri(after))
    }

    @Test fun `stableKey and resolvePlayableUri are not the same string`() {
        val s = song(3, "/storage/emulated/0/Music/a.mp3")
        assertNotEquals(source.stableKey(s), source.resolvePlayableUri(s))
    }

    /**
     * Rung 3, and only when both location columns are gone. This entry does NOT survive a rescan
     * — see R3-C1 — but falling back beats throwing or keying on null, and with rung 1 available
     * from minSdk 29 it should be unreachable in practice.
     */
    @Test fun `stableKey falls back to the uri only when both location rungs are absent`() {
        val s = song(3, filePath = null, relativeKey = null)
        assertEquals("content://media/external/audio/media/3", source.stableKey(s))
    }

    @Test fun `resolvePlayableUri is unchanged — it is still the content uri`() {
        val s = song(3, "/storage/emulated/0/Music/a.mp3")
        assertEquals("content://media/external/audio/media/3", source.resolvePlayableUri(s))
    }

    // ---- composing the relative key from the three MediaStore columns ------------------------

    /**
     * `RELATIVE_PATH` conventionally carries a trailing separator. If the composer did not
     * normalise it, the same file would key differently depending on what the provider returned,
     * and a playlist would stop resolving after an OS update changed its mind about the slash.
     */
    /** Only the separator is normalised — never whitespace inside the path or the name. */
    @Test fun `composeRelativeKey inserts exactly one separator whatever the input`() {
        val expected = "external_primary:Music/Beck/Lost Cause.mp3"
        assertEquals(expected, key("external_primary", "Music/Beck/", "Lost Cause.mp3"))
        assertEquals(expected, key("external_primary", "Music/Beck", "Lost Cause.mp3"))
        assertEquals(expected, key("external_primary", "/Music/Beck/", "Lost Cause.mp3"))
        // the volume IS whitespace-trimmed: it is a MediaStore identifier, not a name on disk
        assertEquals(expected, key("  external_primary  ", "Music/Beck/", "Lost Cause.mp3"))
    }

    /**
     * A partial key is deliberately NOT a key — the caller falls through to the `DATA` path, which
     * is absolute and therefore already volume-qualified. Emitting a partial key would defeat the
     * point: an unqualified key is exactly the thing that collides, so it must never be the
     * fallback for a missing qualifier.
     */
    @Test fun `composeRelativeKey returns null unless all three parts are present`() {
        assertNull(key(null, "Music/Beck/", "Lost.mp3"))
        assertNull(key("", "Music/Beck/", "Lost.mp3"))
        assertNull(key("  ", "Music/Beck/", "Lost.mp3")) // volume is trimmed, so this IS empty
        assertNull(key("external_primary", null, "Lost.mp3"))
        assertNull(key("external_primary", "", "Lost.mp3"))
        assertNull(key("external_primary", "/", "Lost.mp3"))
        assertNull(key("external_primary", "Music/Beck/", null))
        assertNull(key("external_primary", "Music/Beck/", ""))
        assertNull(key(null, null, null))
    }

    /**
     * The knock-on of narrowing the trim in round 4, asserted rather than narrated.
     *
     * A whitespace-ONLY directory or filename is now ACCEPTED as a key, where the broad trim used
     * to reject it. That is the correct direction — such names are legal on ext4/f2fs, the composer
     * runs symmetrically on the add and resolve sides, and over-acceptance costs an unresolved
     * (blank) entry rather than a WRONG match.
     *
     * Pinned because the alternative is silent: a later reader re-adding a defensive
     * `if (dir.isBlank() || name.isBlank()) return null` would reinstate the round-4 collision for
     * padded-only names, and every other case in this class uses "", "/" or a non-blank padded
     * name — so the whole suite would still pass.
     */
    @Test fun `a whitespace-only directory or filename is a key, not a rejection`() {
        assertNotNull(key("external_primary", "   ", "Lost.mp3"))
        assertNotNull(key("external_primary", "Music/", "  "))
        // And they stay DISTINCT from their non-blank neighbours.
        assertNotEquals(
            key("external_primary", "   ", "Lost.mp3"),
            key("external_primary", "Music", "Lost.mp3"),
        )
    }

    /**
     * THE round-4 defect, and the fourth instance of this task's recurring bug class.
     *
     * Leading and trailing spaces in filenames are LEGAL on ext4/f2fs, which is what backs internal
     * storage — they are illegal only on FAT/exFAT, i.e. the SD card. `DISPLAY_NAME` is the literal
     * name on disk and MediaStore does not pad it, so trimming it bought nothing and cost
     * uniqueness: `" a.mp3"` and `"a.mp3"` could sit in one folder and shared a key.
     *
     * A collision here is the dangerous direction — it returns the WRONG track and writes the wrong
     * `songId` back — not the merely-blank direction.
     */
    @Test fun `composeRelativeKey does not merge a space-padded filename with its sibling`() {
        val padded = key("external_primary", "Music/", " a.mp3")
        val plain = key("external_primary", "Music/", "a.mp3")
        val trailing = key("external_primary", "Music/", "a.mp3 ")
        assertNotNull(padded)
        assertNotNull(trailing)
        assertNotEquals("a leading space is part of the filename on ext4", plain, padded)
        assertNotEquals("a trailing space is part of the filename on ext4", plain, trailing)
        assertNotEquals(padded, trailing)
        assertEquals("external_primary:Music/ a.mp3", padded)
    }

    /** Same defect on the directory half: `" Music"` and `"Music"` are different folders. */
    @Test fun `composeRelativeKey does not merge a space-padded directory with its sibling`() {
        val padded = key("external_primary", " Music/", "a.mp3")
        val plain = key("external_primary", "Music/", "a.mp3")
        assertNotNull(padded)
        assertNotEquals("a leading space is part of the directory name on ext4", plain, padded)
        assertEquals("external_primary: Music/a.mp3", padded)
    }

    /**
     * Structural, not documentary. The KDoc's claim that `VOLUME_NAME` never contains the
     * separator is true of the reachable set, but round 3's failure mode was exactly "governing
     * fact written in a KDoc, applied one level only". A volume name we cannot encode
     * unambiguously falls through to the absolute `DATA` path instead.
     */
    @Test fun `composeRelativeKey refuses a volume name containing the separator`() {
        assertNull(key("bad:volume", "Music/", "a.mp3"))
        assertNull(key(":", "Music/", "a.mp3"))
    }

    /**
     * The twin of the test above, for the directory boundary rather than the volume boundary.
     *
     * The injectivity proof holds only while a filename contains no `/` — without the guard,
     * `("Music/Beck", "Lost.mp3")` and `("Music", "Beck/Lost.mp3")` name two genuinely
     * different files and produce ONE key. Asserted as the pair rather than as two nulls, so
     * that if the guard is ever removed the failure message is the collapse itself.
     */
    @Test fun `a slash in the filename cannot forge a directory boundary`() {
        assertNull(key("external_primary", "Music/Beck/", "Lost.mp3/evil"))
        assertNotEquals(
            key("external_primary", "Music/Beck/", "Lost.mp3"),
            key("external_primary", "Music/", "Beck/Lost.mp3"),
        )
    }

    /** Two different files in the same folder must not collapse onto one key. */
    @Test fun `composeRelativeKey distinguishes files within a folder and folders across files`() {
        assertNotEquals(
            key("external_primary", "Music/Beck/", "a.mp3"),
            key("external_primary", "Music/Beck/", "b.mp3"),
        )
        assertNotEquals(
            key("external_primary", "Music/Beck/", "a.mp3"),
            key("external_primary", "Music/Nick/", "a.mp3"),
        )
    }

    /**
     * THE gap this round closes. `RELATIVE_PATH` is *volume-relative*, but `listSongs` queries
     * `EXTERNAL_CONTENT_URI` = `VOLUME_EXTERNAL`, which spans primary storage AND removable SD on
     * API 29+. A user with `Music/a.mp3` on internal and a copy at `Music/a.mp3` on an SD card
     * produces two distinct rows at the same relative path.
     *
     * Without the volume qualifier those two rows share one key, `resolve`'s
     * `associateBy { stableKey(it) }` silently keeps the last, and rung 1 returns the wrong file —
     * then writes the wrong `songId` back. `PlaylistRepositoryTest` shows that damage end to end;
     * this pins the key-level cause.
     */
    @Test fun `composeRelativeKey distinguishes the same path on different volumes`() {
        val internal = key("external_primary", "Music/", "a.mp3")
        val sdCard = key("1234-5678", "Music/", "a.mp3")
        assertNotNull(internal)
        assertNotNull(sdCard)
        assertNotEquals(
            "the same relative path on two volumes must not share a key",
            internal,
            sdCard,
        )
    }

    /** The volume qualifier is actually in the emitted string, not merely influencing it. */
    @Test fun `composeRelativeKey is qualified by the volume name`() {
        assertEquals("1234-5678:Music/a.mp3", key("1234-5678", "Music/", "a.mp3"))
    }

    private fun key(volume: String?, relativePath: String?, displayName: String?) =
        LocalSource.composeRelativeKey(volume, relativePath, displayName)
}
