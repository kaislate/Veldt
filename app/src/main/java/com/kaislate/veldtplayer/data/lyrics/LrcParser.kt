// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

/**
 * Reads the `.lrc` lyrics format into [Lyrics]. Pure and framework-free: it takes text that has
 * already been decoded, so it holds no opinion about encodings, files or providers.
 *
 * [parse] is total — it never throws — and reduces to two questions:
 *
 *  1. **Does the file contain at least one timestamp tag `[mm:ss]`, `[mm:ss.x]` (tenths),
 *     `[mm:ss.xx]` (hundredths) or `[mm:ss.xxx]` (milliseconds)?** Minutes may exceed 59.
 *     Several leading tags on one line (`[00:10.00][00:20.00]chorus`) each emit their own
 *     [LyricLine] with the same text. `[offset:±N]` (ms) shifts every timestamp — positive
 *     shifts earlier, per LRC convention — and the shifted result is clamped at 0, never
 *     negative. The lines are returned sorted by time, stably, so tied timestamps keep the
 *     order they had in the file.
 *
 *     **If the file has any timestamp tag, it is treated as synced, full stop**: every line
 *     that is not itself a timestamp, metadata or comment line is *ignored*, not folded into
 *     the output, even if every timed line turns out to have empty text. An empty-text timed
 *     line (`[00:03.00]`, an instrumental gap) is still a real line and is kept — but if *every*
 *     timed line in the file is empty, there is nothing to show and the result is `null` rather
 *     than a list of blanks.
 *
 *  2. **Otherwise** — no timestamp tag anywhere — the file is unsynced: metadata and comment
 *     lines are dropped and what remains is joined back with `\n` into [Lyrics.Plain]; if
 *     nothing remains, the result is `null`.
 *
 * Along the way: a leading BOM is stripped, `\r\n` and bare `\r` line endings are normalised to
 * `\n`, every line is trimmed, metadata tags (`[ar:]`, `[ti:]`, `[al:]`, `[by:]`, `[length:]`,
 * `[re:]`, `[ve:]` and any other `[letters:...]` line) and `#` comment lines are dropped, and
 * enhanced word-timing tags (`<mm:ss.xx>`) are stripped out of a timed line's text. A line whose
 * leading bracket looks like a timestamp but isn't (`[aa:bb.cc]x` — the "minutes" aren't
 * digits) is not a timestamp at all: it is ordinary text, subject to the same synced/plain
 * split as everything else.
 */
object LrcParser {

    /** `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]` or `[mm:ss.xxx]` (`:` also accepted before the fraction). */
    private val TIME_TAG = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** A whole line consisting of exactly one `[offset:±N]` tag. */
    private val OFFSET_TAG = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)

    /** A whole line consisting of exactly one `[letters:...]` metadata tag. */
    private val METADATA_LINE = Regex("""^\[[a-zA-Z]+:.*]$""")

    /** An enhanced word-timing tag, stripped from a timed line's text wherever it appears. */
    private val WORD_TAG = Regex("""<\d+:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(text: String): Lyrics? {
        val normalized = text.removePrefix("﻿").replace("\r\n", "\n").replace("\r", "\n")

        var offsetMs = 0L
        var sawTimedLine = false
        val timedLines = mutableListOf<LyricLine>()
        val plainTextLines = mutableListOf<String>()

        for (rawLine in normalized.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val offsetMatch = OFFSET_TAG.matchEntire(line)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLong()
                continue
            }

            if (METADATA_LINE.matches(line)) continue

            val times = mutableListOf<Long>()
            var rest = line
            while (true) {
                val match = TIME_TAG.find(rest)
                if (match == null || match.range.first != 0) break
                times += parseTimeMs(match)
                rest = rest.substring(match.range.last + 1)
            }

            if (times.isEmpty()) {
                plainTextLines += line
                continue
            }

            sawTimedLine = true
            val lineText = rest.replace(WORD_TAG, "").trim()
            for (timeMs in times) {
                timedLines += LyricLine(timeMs, lineText)
            }
        }

        if (sawTimedLine) {
            if (timedLines.none { it.text.isNotEmpty() }) return null
            val shifted = timedLines.map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0)) }
            return Lyrics.Synced(shifted.sortedBy { it.timeMs })
        }

        val joined = plainTextLines.joinToString("\n")
        return joined.takeIf { it.isNotEmpty() }?.let { Lyrics.Plain(it) }
    }

    /** Minutes/seconds/fraction from a [TIME_TAG] match, in milliseconds. Fraction digits scale: 1 = tenths, 2 = hundredths, 3 = ms. */
    private fun parseTimeMs(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3]
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.toLong()
        }
        return minutes * 60_000 + seconds * 1_000 + fractionMs
    }
}
