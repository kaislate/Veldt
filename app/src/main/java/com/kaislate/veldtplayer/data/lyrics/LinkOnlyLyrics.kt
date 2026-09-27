// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

/**
 * Finding 20 (spec 0.9.2 §1): a lyrics result whose only content is links is a release-group
 * watermark, not lyrics. The owner's server answered exactly `www.t.me/pmedia_music` for every
 * track of a whole album — a tag the rippers wrote into the files' `LYRICS` field — and Veldt
 * showed it as the lyric. The resolver's weak-result rule only demoted that line to a fallback,
 * so with nothing better anywhere it was still shown; this rule removes it from the chain
 * entirely.
 *
 * [isLinkOnly] is deliberately narrow: EVERY non-blank line, once any leading LRC timestamps are
 * stripped, must consist of nothing but link tokens. One line of anything else — a real lyric, or
 * even `Telegram: t.me/x` — keeps the whole result, URL line included, exactly as the source had
 * it: dropping a real lyric would be worse than showing a stray link beside it.
 *
 * Blank counts as link-only too (it has no other text), so a provider that ever hands back an
 * empty body is judged the same as one that hands back a watermark: nothing to show.
 */
object LinkOnlyLyrics {

    /** Leading `[mm:ss]` / `[mm:ss.xx]` tags, as [LrcParser] reads them — so a watermark that
     *  arrives as LRC text inside a [Lyrics.Plain] (`[00:00.00]www.example.com`) is judged by the
     *  text after its timestamps. */
    private val LEADING_TIME_TAGS = Regex("""^(?:\[\d+:\d{1,2}(?:[.:]\d{1,3})?]\s*)+""")

    /**
     * One link token: a scheme URL (`http://`, `https://`), a `www.` host, or a bare host with a
     * letters-only TLD of two or more characters and an optional path (`t.me/pmedia_music`). The
     * TLD rule is what keeps abbreviations like `U.S.A.` or `a.m.` from counting as links.
     */
    private val LINK_TOKEN = Regex(
        """(?i)(?:https?://\S+|www\.\S+|(?:[a-z0-9-]+\.)+[a-z]{2,}(?:[/?#]\S*)?)""",
    )

    fun isLinkOnly(text: String): Boolean =
        text.lines().all { line -> isLinkOnlyLine(line.replaceFirst(LEADING_TIME_TAGS, "")) }

    fun isLinkOnly(lyrics: Lyrics): Boolean = when (lyrics) {
        is Lyrics.Plain -> isLinkOnly(lyrics.text)
        is Lyrics.Synced -> lyrics.lines.all { isLinkOnlyLine(it.text) }
    }

    private fun isLinkOnlyLine(line: String): Boolean =
        line.split(WHITESPACE).all { token -> token.isEmpty() || LINK_TOKEN.matches(token) }

    private val WHITESPACE = Regex("""\s+""")
}
