// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist.m3u

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The resolution ladder, rung by rung.
 *
 * Two shapes are used deliberately throughout, because this phase has now produced five defects of
 * one class — logic that was locally correct but collapsed two genuinely distinct real-world inputs
 * onto one value:
 *
 *  1. **Uniqueness is stated as non-collapse of a PAIR**, never as the preservation of one value.
 *     `assertEquals(listOf(a, b), out)` fails with `expected:<[a, b]> but was:<[b, b]>` — the
 *     failure message *is* the collapse. A test that asserted only `a` would stay green while `b`
 *     quietly became `a` too.
 *  2. **`step` is asserted alongside `song`.** The ladder's later rungs frequently agree with its
 *     earlier ones, so "the right song came back" does not establish that the rung under test is
 *     what produced it. Asserting the step is what makes each rung independently pinnable.
 *
 * [`every rung's key space is disjoint from every other`] is the structural guard: a table of
 * inputs asserted pairwise distinct, keyed by [MatchStep], so adding a rung to the enum without
 * adding a row to the table fails the suite. Three of Task 2's four defects were namespace
 * collisions that a table like this would have caught.
 */
class LocalEntryResolverTest {

    private var nextId = 1L

    /**
     * A library row. [path] is the MediaStore `DATA` path and [relativeKey] the
     * `VOLUME:RELATIVE_PATH/DISPLAY_NAME` triple; **both are nullable on purpose** — a provider
     * that withholds `DATA` is precisely the case Task 2's second defect was, and the resolver has
     * to key on whichever of the two it is given.
     */
    private fun song(
        path: String? = null,
        relativeKey: String? = null,
        title: String = "T",
        artist: String = "A",
        album: String = "Al",
        durationMs: Long = 1_000L,
        uri: String = "content://media/external/audio/media/${nextId}",
    ): Song = Song(
        id = nextId++,
        uri = uri,
        filePath = path,
        relativeKey = relativeKey,
        title = title,
        artist = artist,
        album = album,
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = durationMs,
        dateModifiedSec = 0L,
        hasEmbeddedArt = false,
    )

    private fun entry(path: String, title: String? = null, artist: String? = null, dur: Int? = null) =
        M3uEntry(path = path, durationSec = dur, title = title, artist = artist)

    private fun resolve(paths: List<String>, library: List<Song>, dir: String? = null) =
        LocalEntryResolver.resolve(paths.map { entry(it) }, library, dir)

    private fun one(path: String, library: List<Song>, dir: String? = null) =
        resolve(listOf(path), library, dir).single()

    // ---------------------------------------------------------------- the eight ladder cases

    @Test fun `an exact uri match wins at step one`() {
        val target = song(uri = "content://media/external/audio/media/42", path = "/x/a.mp3")
        val out = one("content://media/external/audio/media/42", listOf(song(path = "/y/b.mp3"), target))
        assertEquals(target, out.song)
        assertEquals(MatchStep.EXACT, out.step)
    }

    @Test fun `separators and case differences still match`() {
        val target = song(path = "/storage/emulated/0/music/beck/lost.mp3")
        val out = one("Music\\Beck\\Lost.mp3", listOf(target), dir = "/storage/emulated/0")
        assertEquals(target, out.song)
        assertEquals(MatchStep.NORMALISED, out.step)
    }

    @Test fun `a playlist rooted at another mount point matches on the trailing path`() {
        val target = song(path = "/storage/emulated/0/Music/Beck/Lost.mp3")
        val out = one("/mnt/sdcard/Music/Beck/Lost.mp3", listOf(target))
        assertEquals(target, out.song)
        assertEquals(MatchStep.SUFFIX, out.step)
    }

    @Test fun `a bare filename matches when the path cannot`() {
        val target = song(path = "/storage/emulated/0/Music/Beck/Lost Cause.mp3")
        val out = one("Lost Cause.mp3", listOf(target))
        assertEquals(target, out.song)
        assertEquals(MatchStep.FILENAME, out.step)
    }

    @Test fun `artist and title from EXTINF match when no path does`() {
        val target = song(path = "/storage/emulated/0/Music/01 track.mp3", artist = "Beck", title = "Lost Cause")
        val out = LocalEntryResolver.resolve(
            listOf(entry("D:\\OldPC\\rip17.mp3", title = "Lost Cause", artist = "Beck", dur = 213)),
            listOf(target),
            null,
        ).single()
        assertEquals(target, out.song)
        assertEquals(MatchStep.TAGS, out.step)
    }

    @Test fun `an entry matching nothing is reported unresolved, not dropped`() {
        val out = LocalEntryResolver.resolve(listOf(M3uEntry("ghost.mp3", null, null, null)), emptyList(), null)
        assertEquals(1, out.size)
        assertNull(out.single().song)
        assertEquals(MatchStep.UNRESOLVED, out.single().step)
    }

    @Test fun `two library songs sharing a filename do not both claim one entry`() {
        val out = one(
            "Lost.mp3",
            listOf(song(path = "/x/Beck/Lost.mp3"), song(path = "/x/Nick/Lost.mp3")),
        )
        assertNull(out.song)
        assertEquals(MatchStep.UNRESOLVED, out.step)
    }

    @Test fun `a relative path resolves against the playlist's own directory`() {
        val target = song(path = "/storage/emulated/0/Music/Beck/Lost.mp3")
        val out = one("Beck/Lost.mp3", listOf(target), dir = "/storage/emulated/0/Music")
        assertEquals(target, out.song)
        assertEquals(MatchStep.NORMALISED, out.step)
    }

    // ---------------------------------------------------------------- the namespace guard

    /**
     * The structural guard the Task 2 review asked for.
     *
     * Every rung contributes at least one row and all rows must be pairwise distinct. The rows are
     * chosen adversarially: several rungs produce the *same body* for the same input, so the only
     * thing separating them is the prefix. Flatten the key space (the pre-Task-4 state, where the
     * rungs merely happened not to overlap) or copy-paste one rung's prefix constant onto another
     * and this test names both colliding rows and the key they collided on.
     *
     * **Exactly what this can and cannot detect, because getting this wrong is how it failed once
     * already.** A wrong prefix is caught only when two rows share a *body*, so the table needs a
     * shared-body pair for every pair of rungs whose body spaces overlap — and "overlap" means what
     * is legal on ext4, not what looks plausible:
     *
     *  - r1 bodies are raw paths, i.e. arbitrary strings, so r1 pairs with everything;
     *  - r2, r3 and r4 bodies are all folded paths and overlap freely, *except* r3 vs r4: a suffix
     *    body always contains a `/` and a filename body never can, so that one pair needs no row;
     *  - r5 bodies contain colons and may contain slashes (`ac/dc`), so they overlap r1, r2, r3
     *    and r4 — this is the set that was missing, and the suite was 33/33 GREEN under
     *    `P_TAGS = "r4:"` until the colon-bearing rows below were added.
     *
     * A shared body needs a row on **both** sides to collide. That is not a pedantic point: adding
     * only the r3 row left `P_SUFFIX = "r5:"` *and* `P_TAGS = "r3:"` both green, which is why
     * `tags ac/dc + x` exists alongside `suffix 5:ac/dc:x` rather than instead of it.
     *
     * All twenty prefix transpositions were swept. Nineteen are now red here; the twentieth,
     * r3↔r4, is unreachable and needs no row — a suffix body always contains a `/` and a filename
     * body never can, so the two cannot alias even in principle.
     *
     * The [MatchStep] coverage assertion is why the table cannot rot: add a rung to the enum and
     * this fails until the table has a row for it. Note what that assertion does *not* do — it
     * cannot know which body spaces a new rung overlaps, so adding a row is necessary and the
     * argument above is what makes it sufficient.
     */
    @Test fun `every rung's key space is disjoint from every other`() {
        data class Row(val step: MatchStep, val label: String, val key: String)

        val rows = listOf(
            // EXACT keys a raw path verbatim, so its bodies are arbitrary strings — including
            // strings that spell another rung's body. All five below are legal paths on ext4.
            Row(MatchStep.EXACT, "exact Music/a.mp3", LocalEntryResolver.exactKey("Music/a.mp3")),
            Row(MatchStep.EXACT, "exact music/a.mp3", LocalEntryResolver.exactKey("music/a.mp3")),
            Row(MatchStep.EXACT, "exact /x/a.mp3", LocalEntryResolver.exactKey("/x/a.mp3")),
            Row(MatchStep.EXACT, "exact a.mp3", LocalEntryResolver.exactKey("a.mp3")),
            // A file may legally be named this; it is exactly the tags rung's body.
            Row(MatchStep.EXACT, "exact 4:beck:lost cause", LocalEntryResolver.exactKey("4:beck:lost cause")),

            Row(MatchStep.NORMALISED, "normalised Music/a.mp3", LocalEntryResolver.normalisedKey("Music/a.mp3")),
            Row(MatchStep.NORMALISED, "normalised /x/a.mp3", LocalEntryResolver.normalisedKey("/x/a.mp3")),
            Row(MatchStep.NORMALISED, "normalised a.mp3", LocalEntryResolver.normalisedKey("a.mp3")),
            // r2 vs r5, by the same argument as the r4 row below.
            Row(MatchStep.NORMALISED, "normalised 4:beck:lost cause", LocalEntryResolver.normalisedKey("4:beck:lost cause")),

            Row(MatchStep.SUFFIX, "suffix Music/a.mp3", req(LocalEntryResolver.suffixKey("Music/a.mp3", 2))),
            // A colon is legal in a path segment and a slash is legal in an artist name, so a
            // suffix body and a tags body genuinely occupy the same space: this one is character
            // for character `tagsKey("ac/dc", "x")`'s. Without it, transposing r3 and r5 is
            // invisible here. (r3 vs r4 needs no such row: a suffix body always contains a `/` and
            // a filename body never can.)
            Row(MatchStep.SUFFIX, "suffix 5:ac/dc:x", req(LocalEntryResolver.suffixKey("5:ac/dc:x", 2))),

            Row(MatchStep.FILENAME, "filename a.mp3", req(LocalEntryResolver.filenameKey("a.mp3"))),
            // Likewise for r4 vs r5 — this is `tagsKey("beck", "lost cause")`'s body, and a file
            // may legally be named it. The suite was 33/33 GREEN under `P_TAGS = "r4:"` until this
            // row existed; see the report's addendum.
            Row(MatchStep.FILENAME, "filename 4:beck:lost cause", req(LocalEntryResolver.filenameKey("4:beck:lost cause"))),

            Row(MatchStep.TAGS, "tags Beck|Lost Cause", req(LocalEntryResolver.tagsKey("Beck", "Lost Cause"))),
            // Artist and title are user text and may contain any separator one might pick, so the
            // tags key is length-prefixed rather than delimited. These two are a genuine pair of
            // distinct tag sets that a delimiter-only key would merge.
            Row(MatchStep.TAGS, "tags a|b + c", req(LocalEntryResolver.tagsKey("a|b", "c"))),
            Row(MatchStep.TAGS, "tags a + b|c", req(LocalEntryResolver.tagsKey("a", "b|c"))),
            // The other half of the r3 row above. A shared body only collides when BOTH rungs
            // contribute a row for it: with only the suffix side present, transposing r3 and r5
            // stayed green in both directions. AC/DC is spelt with a slash.
            Row(MatchStep.TAGS, "tags ac/dc + x", req(LocalEntryResolver.tagsKey("ac/dc", "x"))),
        )

        assertEquals(
            "every rung except UNRESOLVED needs a row in this table",
            MatchStep.values().toSet() - MatchStep.UNRESOLVED,
            rows.map { it.step }.toSet(),
        )

        val collisions = rows.groupBy { it.key }
            .filterValues { it.size > 1 }
            .map { (key, colliding) -> colliding.map { it.label } to key }
        assertEquals(emptyList<Pair<List<String>, String>>(), collisions)
    }

    private fun req(key: String?): String = requireNotNull(key)

    /**
     * The same collision at the level that would actually bite, with realistic rows.
     *
     * A song known only by its volume-relative key produces an *unrooted* NORMALISED body
     * (`music/a.mp3`); a song two directories deeper produces the same text as a SUFFIX body. In
     * one flat key space they share a bucket, the bucket looks ambiguous, and the entry that should
     * have resolved exactly goes unresolved instead. Prefixes are what keep them apart.
     */
    @Test fun `a normalised key and another song's suffix key do not share a bucket`() {
        val target = song(relativeKey = "external_primary:Music/a.mp3")
        val deeper = song(path = "/storage/emulated/0/Backup/Music/a.mp3")
        val out = one("Music/a.mp3", listOf(target, deeper))
        assertEquals(target, out.song)
        assertEquals(MatchStep.NORMALISED, out.step)
    }

    // ---------------------------------------------------------------- what must not collapse

    /**
     * ` a.mp3` and `a.mp3` are two different files in one directory on ext4/f2fs. The pair form is
     * load-bearing: under a `.trim()` in the path normalisation both entries land on one bucket and
     * the message reads `expected:<[/x/ a.mp3, /x/a.mp3]> but was:<[null, null]>`.
     */
    @Test fun `paths differing only in surrounding whitespace stay two different files`() {
        val padded = song(path = "/x/ a.mp3")
        val bare = song(path = "/x/a.mp3")
        val out = resolve(listOf(" a.mp3", "a.mp3"), listOf(padded, bare), dir = "/x")
        assertEquals(listOf("/x/ a.mp3", "/x/a.mp3"), out.map { it.song?.filePath })
        assertEquals(listOf(MatchStep.NORMALISED, MatchStep.NORMALISED), out.map { it.step })
    }

    /** The directory half of the same rule — `" Music"` and `"Music"` are two real folders. */
    @Test fun `directories differing only in a leading space stay two different directories`() {
        val padded = song(path = "/x/ Music/a.mp3")
        val bare = song(path = "/x/Music/a.mp3")
        val out = resolve(listOf(" Music/a.mp3", "Music/a.mp3"), listOf(padded, bare), dir = "/x")
        assertEquals(listOf("/x/ Music/a.mp3", "/x/Music/a.mp3"), out.map { it.song?.filePath })
    }

    /**
     * The EXACT rung is byte-for-byte, so two files differing only in case each match themselves.
     * This is the pair that would collapse if the case fold were pulled up into rung 1.
     */
    @Test fun `case differences are preserved by the exact rung`() {
        val upper = song(path = "/x/A.mp3")
        val lower = song(path = "/x/a.mp3")
        val out = resolve(listOf("/x/A.mp3", "/x/a.mp3"), listOf(upper, lower))
        assertEquals(listOf("/x/A.mp3", "/x/a.mp3"), out.map { it.song?.filePath })
        assertEquals(listOf(MatchStep.EXACT, MatchStep.EXACT), out.map { it.step })
    }

    /**
     * ...and where the exact rung cannot help, the case fold is not allowed to *guess*. Two files
     * differing only in case are a real possibility on ext4; the folded rungs see one key holding
     * two songs, and an ambiguous rung must not match.
     */
    @Test fun `two songs differing only in case are never guessed between`() {
        val out = one("a.mp3", listOf(song(path = "/x/A.mp3"), song(path = "/x/a.mp3")), dir = "/x")
        assertNull(out.song)
        assertEquals(MatchStep.UNRESOLVED, out.step)
    }

    /**
     * `\` is a legal character in a POSIX filename, so folding it to `/` — which a Windows-authored
     * playlist needs — can turn one file into a two-segment path. The EXACT rung runs first
     * precisely so the literal file wins where it exists, and the fold only ever applies to inputs
     * nothing matched verbatim.
     */
    @Test fun `a backslash in a real filename matches the file, not the directory it looks like`() {
        val literal = song(path = "/x/a\\b.mp3")
        val nested = song(path = "/x/a/b.mp3")
        val out = resolve(listOf("/x/a\\b.mp3", "/x/a/b.mp3"), listOf(literal, nested))
        assertEquals(listOf("/x/a\\b.mp3", "/x/a/b.mp3"), out.map { it.song?.filePath })
        assertEquals(listOf(MatchStep.EXACT, MatchStep.EXACT), out.map { it.step })
    }

    /** With no literal file to claim it, the fold does its intended job. */
    @Test fun `a windows separator falls back to the folded path`() {
        val nested = song(path = "/x/a/b.mp3")
        val out = one("/x/a\\b.mp3", listOf(nested))
        assertEquals(nested, out.song)
        assertEquals(MatchStep.NORMALISED, out.step)
    }

    // ---------------------------------------------------------------- ambiguity and fall-through

    /**
     * Ambiguity is a *fall-through*, not a stop: the filename rung cannot choose between two
     * `Lost.mp3`s, but the EXTINF tags name one of them unambiguously, so the ladder keeps going.
     * Asserting `TAGS` rather than merely "the right song" is what distinguishes this from the
     * filename rung having guessed correctly.
     */
    @Test fun `an ambiguous rung falls through to a later one that can decide`() {
        val beck = song(path = "/x/Beck/Lost.mp3", artist = "Beck", title = "Lost Cause")
        val nick = song(path = "/x/Nick/Lost.mp3", artist = "Nick Drake", title = "Fruit Tree")
        val out = LocalEntryResolver.resolve(
            listOf(entry("Lost.mp3", title = "Lost Cause", artist = "Beck")),
            listOf(beck, nick),
            null,
        ).single()
        assertEquals(beck, out.song)
        assertEquals(MatchStep.TAGS, out.step)
    }

    /** A studio take and a live take share artist and title. That is not a match, it is a coin toss. */
    @Test fun `two songs sharing artist and title are not guessed between`() {
        val out = LocalEntryResolver.resolve(
            listOf(entry("gone.mp3", title = "Lost Cause", artist = "Beck")),
            listOf(
                song(path = "/x/studio.mp3", artist = "Beck", title = "Lost Cause"),
                song(path = "/x/live.mp3", artist = "Beck", title = "Lost Cause"),
            ),
            null,
        ).single()
        assertNull(out.song)
        assertEquals(MatchStep.UNRESOLVED, out.step)
    }

    /**
     * The suffix rung takes the LONGEST trailing path that still identifies one file. Two albums
     * both containing `Live/Lost.mp3` are indistinguishable on two segments and distinct on three,
     * so a shortest-first walk would call this ambiguous and give up on both.
     */
    @Test fun `a suffix match uses the longest trailing path that identifies one file`() {
        val music = song(path = "/storage/emulated/0/Music/Live/Lost.mp3")
        val archive = song(path = "/storage/emulated/0/Archive/Live/Lost.mp3")
        val out = resolve(
            listOf("/mnt/sdcard/Music/Live/Lost.mp3", "/mnt/sdcard/Archive/Live/Lost.mp3"),
            listOf(music, archive),
        )
        assertEquals(
            listOf("/storage/emulated/0/Music/Live/Lost.mp3", "/storage/emulated/0/Archive/Live/Lost.mp3"),
            out.map { it.song?.filePath },
        )
        assertEquals(listOf(MatchStep.SUFFIX, MatchStep.SUFFIX), out.map { it.step })
    }

    /** A suffix is a path, not a bag of names: two files under different parents stay distinct. */
    @Test fun `a suffix match does not collapse two different parent directories`() {
        val beck = song(path = "/storage/emulated/0/Music/Beck/Lost.mp3")
        val nick = song(path = "/storage/emulated/0/Music/Nick/Lost.mp3")
        val out = resolve(
            listOf("/mnt/sdcard/Music/Beck/Lost.mp3", "/mnt/sdcard/Music/Nick/Lost.mp3"),
            listOf(beck, nick),
        )
        assertEquals(
            listOf("/storage/emulated/0/Music/Beck/Lost.mp3", "/storage/emulated/0/Music/Nick/Lost.mp3"),
            out.map { it.song?.filePath },
        )
        assertEquals(listOf(MatchStep.SUFFIX, MatchStep.SUFFIX), out.map { it.step })
    }

    // ---------------------------------------------------------------- EXTINF is a hint

    /**
     * Requirement 5, and the one the review will look hardest at: the tags rung uses `#EXTINF` to
     * *find* a candidate and never to describe it. The playlist here is lower-cased, mis-timed and
     * silent about the album — all three of which the library knows better.
     */
    @Test fun `the tags rung never overwrites what the library knows`() {
        val target = song(
            path = "/x/a.mp3", artist = "Beck", title = "Lost Cause",
            album = "Sea Change", durationMs = 213_000L,
        )
        val out = LocalEntryResolver.resolve(
            listOf(entry("gone.mp3", title = "lost cause", artist = "beck", dur = 999)),
            listOf(target),
            null,
        ).single()
        assertEquals(MatchStep.TAGS, out.step)
        assertEquals(
            listOf<Any?>("Lost Cause", "Beck", "Sea Change", 213_000L),
            listOf<Any?>(out.song?.title, out.song?.artist, out.song?.album, out.song?.durationMs),
        )
        // And the entry is handed back exactly as parsed — the resolver reports, it does not edit.
        assertEquals("lost cause", out.entry.title)
    }

    /** Half a tag set is not a tag set: matching on title alone would claim every cover version. */
    @Test fun `an EXTINF with only a title does not match on tags`() {
        val out = LocalEntryResolver.resolve(
            listOf(entry("gone.mp3", title = "Lost Cause")),
            listOf(song(path = "/x/a.mp3", artist = "Beck", title = "Lost Cause")),
            null,
        ).single()
        assertNull(out.song)
        assertEquals(MatchStep.UNRESOLVED, out.step)
    }

    /**
     * MediaStore leaves an untagged file with empty title and artist (`LocalSource.cleanTag`), and
     * a library can hold hundreds of them. They must not all pile into one tags bucket — nor, worse,
     * become a single-candidate match for an entry with equally empty hints.
     */
    @Test fun `untagged library rows do not become one giant tags bucket`() {
        val out = LocalEntryResolver.resolve(
            listOf(entry("gone.mp3", title = "", artist = "")),
            listOf(song(path = "/x/a.mp3", artist = "", title = "")),
            null,
        ).single()
        assertNull(out.song)
        assertEquals(MatchStep.UNRESOLVED, out.step)
    }

    // ---------------------------------------------------------------- paths without a DATA column

    /**
     * `DATA` is nullable from API 29 and Task 2 lost a round to assuming otherwise. A row known only
     * by its `VOLUME:RELATIVE_PATH/DISPLAY_NAME` triple still has to resolve — on the volume-relative
     * path, with the volume stripped, since no playlist ever writes `external_primary:`.
     */
    @Test fun `a song with no DATA path still resolves through its relative key`() {
        val target = song(relativeKey = "external_primary:Music/Beck/Lost.mp3")
        val out = one("/storage/emulated/0/Music/Beck/Lost.mp3", listOf(target))
        assertEquals(target, out.song)
        assertEquals(MatchStep.SUFFIX, out.step)
    }

    /**
     * The same file path on internal storage and on an SD card is TWO files, and an `.m3u` line
     * carries no volume to tell them apart. The volume is stripped when indexing — deliberately,
     * since no playlist ever writes `external_primary:` — so both rows land in one bucket.
     *
     * That is the same collision Task 2 spent two fix rounds on, but the failure direction is
     * inverted and that is the whole point of this test: there, `associateBy` silently kept the
     * last row and the wrong `songId` was written back; here the bucket holds two, the rung is
     * ambiguous, and the entry stays UNRESOLVED. A user with a copied library sees a greyed track
     * rather than the wrong one playing.
     */
    @Test fun `the same relative path on two volumes is not guessed between`() {
        val internal = song(relativeKey = "external_primary:Music/a.mp3", title = "Internal")
        val sdCard = song(relativeKey = "1234-5678:Music/a.mp3", title = "SD")
        val out = one("Music/a.mp3", listOf(internal, sdCard))
        assertNull(out.song)
        assertEquals(MatchStep.UNRESOLVED, out.step)
    }

    /**
     * A song carrying both keys is ONE candidate, not two. Counting it twice would make every
     * fully-populated row look ambiguous and the whole ladder would resolve nothing — the failure
     * mode that turns the safety rule into the bug.
     *
     * The rungs asserted here are chosen, not incidental. `DATA` is rooted and the relative key is
     * not, so their NORMALISED keys differ and that bucket only ever gets one insertion — a
     * NORMALISED-only test cannot see this at all (it did not; see control (g) in the report). The
     * SUFFIX and FILENAME keys are where the two locations genuinely coincide, so those are where
     * the dedupe has to hold.
     */
    @Test fun `a song indexed under both its keys is still a single candidate`() {
        val target = song(
            path = "/storage/emulated/0/Music/Beck/Lost.mp3",
            relativeKey = "external_primary:Music/Beck/Lost.mp3",
        )
        val out = resolve(
            listOf("Lost.mp3", "/mnt/sdcard/Music/Beck/Lost.mp3", "Music/Beck/Lost.mp3"),
            listOf(target),
        )
        assertEquals(listOf(target, target, target), out.map { it.song })
        assertEquals(
            listOf(MatchStep.FILENAME, MatchStep.SUFFIX, MatchStep.NORMALISED),
            out.map { it.step },
        )
    }

    // ---------------------------------------------------------------- path arithmetic

    @Test fun `a parent reference resolves against the playlist directory`() {
        val target = song(path = "/storage/emulated/0/Music/Beck/Lost.mp3")
        val out = one("../Music/Beck/Lost.mp3", listOf(target), dir = "/storage/emulated/0/Playlists")
        assertEquals(target, out.song)
        assertEquals(MatchStep.NORMALISED, out.step)
    }

    /**
     * A `..` with nothing to climb out of is kept, not dropped, and that is the difference between
     * "I could not resolve this" and a confident wrong answer.
     *
     * `../Music/a.mp3` with no playlist directory names a file one level *above* somewhere unknown.
     * Discarding the `..` would turn it into `Music/a.mp3`, which matches the volume-relative song
     * below exactly — a NORMALISED match, the ladder's second-strongest claim, invented out of a
     * segment that was thrown away. Kept, the entry has only weak trailing-path evidence, that
     * evidence fits two songs, and an ambiguous rung does not match.
     */
    @Test fun `an unresolvable parent reference does not become a confident match`() {
        val relative = song(relativeKey = "external_primary:Music/a.mp3")
        val deeper = song(path = "/storage/emulated/0/Backup/Music/a.mp3")
        val out = one("../Music/a.mp3", listOf(relative, deeper))
        assertNull(out.song)
        assertEquals(MatchStep.UNRESOLVED, out.step)
    }

    /**
     * The mirror of the case above: on an ABSOLUTE path an unpoppable `..` *is* swallowed, because
     * `/..` is `/` on POSIX. Nothing is being guessed at — the path is fully determined — so unlike
     * the relative case there is no weaker claim to fall back to.
     *
     * No real Android audio file lives at `/`, so this pins a boundary rather than a scenario. It
     * is here because my first report claimed the behaviour was unobservable, which was simply
     * false for a pure function: `step` tells the two apart (swallowed → NORMALISED, retained →
     * FILENAME), and "I could not construct one" is the kind of claim this phase has been burned by.
     */
    @Test fun `an absolute path may climb above the root, which is the root`() {
        val target = song(path = "/a.mp3")
        val out = one("/../a.mp3", listOf(target))
        assertEquals(target, out.song)
        assertEquals(MatchStep.NORMALISED, out.step)
    }

    /**
     * Only exactly `.` and `..` are navigation. `...` is an ordinary, legal name, and a startsWith
     * check instead of an equality check would delete a real directory from the path.
     */
    @Test fun `a segment of three dots is a directory name, not a parent reference`() {
        val dots = song(path = "/x/.../a.mp3")
        val plain = song(path = "/x/a.mp3")
        val out = resolve(listOf(".../a.mp3", "a.mp3"), listOf(dots, plain), dir = "/x")
        assertEquals(listOf("/x/.../a.mp3", "/x/a.mp3"), out.map { it.song?.filePath })
    }

    /** Repeated separators are one separator on every filesystem Android runs on. */
    @Test fun `a doubled separator is the same path as a single one`() {
        val target = song(path = "/x/Music/a.mp3")
        val out = one("/x//Music//a.mp3", listOf(target))
        assertEquals(target, out.song)
        assertEquals(MatchStep.NORMALISED, out.step)
    }

    // ---------------------------------------------------------------- file: URIs

    /**
     * The shape VLC, Rhythmbox and Windows Media Player write. Asserted with its step, because
     * `FILENAME` would also have found this song and would prove nothing about the scheme handling.
     */
    @Test fun `a file uri resolves to the path it names`() {
        val target = song(path = "/storage/emulated/0/Music/Beck/Lost.mp3")
        val out = one("file:///storage/emulated/0/Music/Beck/Lost.mp3", listOf(target))
        assertEquals(target, out.song)
        assertEquals(MatchStep.NORMALISED, out.step)
    }

    /**
     * **The load-bearing pair.** `%` is a perfectly ordinary character in a filename, so a bare
     * path containing `a%20b.mp3` names a file called exactly that — while the same text inside a
     * `file:` URI names `a b.mp3`. Decoding unconditionally merges the two.
     *
     * The second entry is deliberately *relative*, so it cannot be answered by the byte-exact rung:
     * against an absolute entry, `EXACT` would return the literal song first and the test would
     * stay green under a resolver that percent-decoded everything.
     */
    @Test fun `percent escapes are decoded in a file uri and left literal in a bare path`() {
        val spaced = song(path = "/x/Music/a b.mp3")
        val literal = song(path = "/x/Music/a%20b.mp3")
        val out = resolve(
            listOf("file:///x/Music/a%20b.mp3", "Music/a%20b.mp3"),
            listOf(spaced, literal),
        )
        assertEquals(listOf(spaced, literal), out.map { it.song })
        assertEquals(listOf(MatchStep.NORMALISED, MatchStep.SUFFIX), out.map { it.step })
    }

    /**
     * `+` is a space only in form encoding, which a URI path is not. `Sunn O)))+.mp3` and
     * `Sunn O))) .mp3` are two files, and `java.net.URLDecoder` would merge them — which is why
     * the decoder here is hand-rolled.
     */
    @Test fun `a plus sign in a file uri is a plus sign, not a space`() {
        val plus = song(path = "/x/a+b.mp3")
        val spaced = song(path = "/x/a b.mp3")
        val out = resolve(listOf("file:///x/a+b.mp3", "file:///x/a%20b.mp3"), listOf(plus, spaced))
        assertEquals(listOf(plus, spaced), out.map { it.song })
    }

    /**
     * An escaped separator must not become a separator, and an escaped `..` must not become a
     * parent reference. Both would hand back a confident `NORMALISED` match — the ladder's
     * second-strongest claim — for a path the URI's author explicitly said was something else.
     *
     * This is subtler than "decode after the split", which is what I implemented first and which is
     * **not sufficient**: the key functions join the segments back with `/`, so a decoded `a/b.mp3`
     * sitting inside one segment produces exactly the key of the real two-segment path. Both
     * entries are asserted together because the two escapes fail through different mechanisms and a
     * fix for one does not imply a fix for the other.
     *
     * **The steps are the assertion, not the songs.** The second entry reaches `/a.mp3` either way
     * — via `FILENAME`, the weakest path rung, which is what an unrecognised path is *supposed* to
     * fall to. Applying the escape would promote it to `NORMALISED`, a confident claim built out of
     * a segment the URI said was a name; nothing about the returned song can see the difference.
     */
    @Test fun `an escape that would manufacture path structure is not applied`() {
        val nested = song(path = "/x/a/b.mp3")
        val parent = song(path = "/a.mp3")
        val out = resolve(
            listOf("file:///x/a%2Fb.mp3", "file:///x/%2E%2E/a.mp3"),
            listOf(nested, parent),
        )
        assertEquals(listOf(null, parent), out.map { it.song })
        assertEquals(listOf(MatchStep.UNRESOLVED, MatchStep.FILENAME), out.map { it.step })
    }

    /**
     * Consecutive escapes are one UTF-8 run, not one character each. Paired with a lone Latin-1-era
     * `%F6`, which is *not* valid UTF-8 and must therefore be left unmatched rather than guessed:
     * the two entries name the same track and only one of them says so in the encoding the URI
     * spec requires.
     */
    @Test fun `multi byte escapes decode as one utf8 character, and a lone high byte is not guessed`() {
        val target = song(path = "/x/Björk.mp3")
        val out = resolve(
            listOf("file:///x/Bj%C3%B6rk.mp3", "file:///x/Bj%F6rk.mp3"),
            listOf(target),
        )
        assertEquals(listOf(target, null), out.map { it.song })
    }

    /** A `%` that does not begin a well-formed escape is a `%`, and both files are real. */
    @Test fun `a malformed escape stays literal while a well formed one decodes`() {
        val encoded = song(path = "/x/100%.mp3")
        val literal = song(path = "/x/100%zz.mp3")
        val out = resolve(
            listOf("file:///x/100%25.mp3", "file:///x/100%zz.mp3"),
            listOf(encoded, literal),
        )
        assertEquals(listOf(encoded, literal), out.map { it.song })
    }

    /**
     * Only an empty or `localhost` authority means "this device". `file://server/…` names a host
     * this app cannot read; it falls back to being read as a path — where the weaker rungs may
     * still find something — rather than being claimed as the local file, which is the ladder's
     * worst outcome. The three steps are the assertion: same song, three different strengths of
     * claim.
     */
    @Test fun `only an empty or localhost authority makes a line a local file uri`() {
        val target = song(path = "/x/a.mp3")
        val out = resolve(
            listOf("file:///x/a.mp3", "file://localhost/x/a.mp3", "file://server/x/a.mp3"),
            listOf(target),
        )
        assertEquals(listOf(target, target, target), out.map { it.song })
        assertEquals(
            listOf(MatchStep.NORMALISED, MatchStep.NORMALISED, MatchStep.SUFFIX),
            out.map { it.step },
        )
    }

    // ---------------------------------------------------------------- shape of the output

    /**
     * Size and order are the contract: the playlist screen renders unresolved entries greyed out
     * under their imported title, so dropping one silently shrinks the user's playlist.
     */
    @Test fun `output is the same size and order as the input, resolved or not`() {
        val target = song(path = "/x/b.mp3")
        val out = resolve(listOf("/x/a.mp3", "/x/b.mp3", "/x/c.mp3"), listOf(target))
        assertEquals(listOf("/x/a.mp3", "/x/b.mp3", "/x/c.mp3"), out.map { it.entry.path })
        assertEquals(listOf(null, target, null), out.map { it.song })
        assertEquals(
            listOf(MatchStep.UNRESOLVED, MatchStep.EXACT, MatchStep.UNRESOLVED),
            out.map { it.step },
        )
    }

    /** A playlist may legitimately contain the same track twice; neither entry consumes it. */
    @Test fun `two entries may resolve to the same song`() {
        val target = song(path = "/x/a.mp3")
        val out = resolve(listOf("/x/a.mp3", "/x/a.mp3"), listOf(target))
        assertEquals(listOf(target, target), out.map { it.song })
    }

    @Test fun `an empty library resolves nothing and drops nothing`() {
        val out = resolve(listOf("a.mp3", "b.mp3"), emptyList())
        assertEquals(listOf("a.mp3", "b.mp3"), out.map { it.entry.path })
        assertEquals(listOf(MatchStep.UNRESOLVED, MatchStep.UNRESOLVED), out.map { it.step })
    }

    @Test fun `no entries resolve to no resolutions`() {
        assertEquals(emptyList<Resolution>(), LocalEntryResolver.resolve(emptyList(), listOf(song(path = "/x/a.mp3")), null))
    }
}
