// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist.m3u

/**
 * Reads the `.m3u`/`.m3u8` playlist format into [M3uEntry] rows.
 *
 * Pure and framework-free: it takes text that has already been decoded, so it holds no opinion
 * about encodings or files, and it neither resolves nor matches the paths it hands back.
 *
 * The format has no specification, only conventions, so the parser is deliberately forgiving: a
 * line it cannot make sense of costs that line and nothing else. It never throws. Someone
 * importing a playlist of 300 tracks should not lose all 300 to one bad `#EXTINF`.
 *
 * Every normalisation it does perform is narrow on purpose, because a parser's real failure mode is
 * merging two inputs that were genuinely different:
 *  - a trailing `\r` is dropped, because it is a CRLF line terminator, not content — but only the
 *    trailing one, so a `\r` inside a filename survives;
 *  - blank lines are skipped, which can only ever discard a "path" made of nothing but whitespace;
 *  - paths are otherwise passed through verbatim, spaces and all, since ` a.mp3` and `a.mp3` are
 *    two different files on every filesystem Android runs on;
 *  - `#EXTINF` metadata *is* trimmed, because it is text for humans to read and no artist is
 *    distinguished from another by the padding around it.
 */
object M3uParser {

    private const val EXTINF_PREFIX = "#EXTINF:"

    /**
     * The separator between artist and title in an `#EXTINF` comment. It is space-dash-space, not a
     * bare dash, so that `Jay-Z - Big Pimpin'` splits at the right dash instead of at the one
     * inside the artist's name. Only the *first* occurrence separates, which leaves any remaining
     * dashes to the title: `Alice - Bob - Carol` is Alice performing "Bob - Carol".
     */
    private const val ARTIST_TITLE_SEPARATOR = " - "

    /** The metadata of an `#EXTINF` line still waiting for the path it describes. */
    private data class Header(val durationSec: Int?, val title: String?, val artist: String?)

    /**
     * Parses [text] — the whole playlist, already decoded — into its entries, in file order.
     *
     * `#EXTM3U` is not required. Plenty of real playlists are a bare list of paths and demanding
     * the header would reject them.
     */
    fun parse(text: String): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        var pending: Header? = null

        for (rawLine in text.split('\n')) {
            // Only the terminator, so that a `\r` inside a filename is left where it is.
            val line = rawLine.removeSuffix("\r")
            when {
                line.isBlank() -> Unit

                line.startsWith(EXTINF_PREFIX, ignoreCase = true) ->
                    pending = parseExtInf(line.substring(EXTINF_PREFIX.length))

                // Any other directive — `#EXTM3U`, `#PLAYLIST:`, a comment, VLC's `#EXTVLCOPT` —
                // is not ours to interpret. Crucially it does not clear `pending`: VLC writes its
                // options *between* the `#EXTINF` and the path that `#EXTINF` describes, and
                // resetting here would throw that metadata away.
                line.startsWith("#") -> Unit

                else -> {
                    entries += M3uEntry(
                        path = line,
                        durationSec = pending?.durationSec,
                        title = pending?.title,
                        artist = pending?.artist,
                    )
                    // Consumed. A header applies to exactly one path, and one left unconsumed at
                    // the end of the file simply never becomes an entry.
                    pending = null
                }
            }
        }
        return entries
    }

    /** Parses the `<seconds>,<artist> - <title>` tail of an `#EXTINF:` line. */
    private fun parseExtInf(tail: String): Header {
        val comma = tail.indexOf(',')
        val durationText = if (comma < 0) tail else tail.substring(0, comma)
        val info = if (comma < 0) "" else tail.substring(comma + 1)

        // Anything that is not a number becomes null rather than an exception, and so does the
        // conventional `-1` for "unknown": a negative count of seconds is not a duration, and a
        // caller that has to special-case it gains nothing from the nullable type.
        val durationSec = durationText.trim().toIntOrNull()?.takeIf { it >= 0 }

        val separator = info.indexOf(ARTIST_TITLE_SEPARATOR)
        val artist = if (separator < 0) null else info.substring(0, separator).cleaned()
        val title = if (separator < 0) {
            info.cleaned()
        } else {
            info.substring(separator + ARTIST_TITLE_SEPARATOR.length).cleaned()
        }
        return Header(durationSec = durationSec, title = title, artist = artist)
    }

    /** Display metadata with its padding removed; null when nothing is left. */
    private fun String.cleaned(): String? = trim().takeIf { it.isNotEmpty() }
}
