// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.lyrics

import androidx.lifecycle.Lifecycle
import com.kaislate.veldtplayer.data.lyrics.LyricsSource
import com.kaislate.veldtplayer.ui.components.scrimAtFraction
import com.kaislate.veldtplayer.ui.components.scrimAtText

/**
 * How long auto-follow stays off after the user lets go of the lyrics list (spec §7: "a user
 * scroll suspends auto-follow for 4 s"). Long enough to read the line they scrolled to, short
 * enough that the list is back on the singer before they wonder why it stopped.
 */
const val FOLLOW_SUSPEND_MS = 4_000L

/**
 * The suspension deadline while a finger is still on the list: never expires on its own. The
 * real deadline is set when the drag ENDS ([suspendFollowAfterDrag]), so a slow read-while-
 * holding longer than [FOLLOW_SUSPEND_MS] is never yanked back to the active line mid-gesture.
 */
const val FOLLOW_SUSPENDED_WHILE_DRAGGING = Long.MAX_VALUE

/** The deadline a drag that ended at [nowMs] leaves behind. */
fun suspendFollowAfterDrag(nowMs: Long): Long = nowMs + FOLLOW_SUSPEND_MS

/**
 * Whether the synced list may scroll itself to the active line at [nowMs]. [suspendedUntilMs]
 * is `0` when nothing has suspended it, [FOLLOW_SUSPENDED_WHILE_DRAGGING] during a drag, and
 * [suspendFollowAfterDrag] once the drag ends. The deadline itself counts as expired, so a
 * re-centre scheduled for exactly the deadline runs.
 */
fun shouldAutoFollow(nowMs: Long, suspendedUntilMs: Long): Boolean = nowMs >= suspendedUntilMs

/** The footer line naming where the lyrics came from (spec §7). */
fun attribution(source: LyricsSource): String = when (source) {
    LyricsSource.SIDECAR -> "Lyrics from the .lrc file"
    LyricsSource.EMBEDDED -> "Lyrics from the file's tags"
    LyricsSource.SERVER -> "Lyrics from your server"
    LyricsSource.LRCLIB -> "Lyrics from LRCLIB"
}

/**
 * Whether a lyrics surface may hold its viewer claim (spec §6: resolution only while lyrics are
 * SHOWN). [wanted] is the surface's own "lyrics are up" answer; [lifecycle] is its host's state.
 * Below STARTED the surface is not on screen at all — the app is backgrounded, or its back-stack
 * entry is covered — and now-playing stays COMPOSED in both cases, so without this every
 * automatic track change in the background would run a server/LRCLIB lookup nobody sees.
 */
fun lyricsClaimActive(wanted: Boolean, lifecycle: Lifecycle.State): Boolean =
    wanted && lifecycle.isAtLeast(Lifecycle.State.STARTED)

/**
 * Where a lyrics region's top edge falls on the backdrop, as a fraction of the backdrop's height
 * — the input to `scrimAtFraction`. Unmeasured ([backdropHeightPx] `<= 0`) reads as `0`, the
 * top of the gradient, i.e. the weakest scrim: the safe direction for a contrast floor.
 */
fun regionTopFraction(regionTopPx: Float, backdropHeightPx: Float): Float =
    if (backdropHeightPx <= 0f) 0f else (regionTopPx / backdropHeightPx).coerceIn(0f, 1f)

/**
 * Two `bg` layers stacked with plain SrcOver — the backdrop's own scrim at [below], then a
 * second fill at [above] — composite to one `bg` layer at this alpha. Same colour in both, so
 * the ground under them is the art lerped toward `bg` by exactly this much.
 */
fun compositeScrim(below: Float, above: Float): Float = 1f - (1f - below) * (1f - above)

/**
 * The alpha of the `bg` fill a lyrics region draws over the backdrop so that, at its TOP edge
 * (where the backdrop's own scrim is weakest, [aTop]), the composite reaches [target]:
 * `compositeScrim(aTop, lyricsScrimFloor(aTop, target)) == target`. `0` when the backdrop
 * already supplies [target] there — the floor only ever adds, never replaces.
 */
fun lyricsScrimFloor(aTop: Float, target: Float): Float =
    if (aTop >= target) 0f else maxOf(0f, 1f - (1f - target) / (1f - aTop))

/**
 * The floor a lyrics region whose top edge is at [topFraction] of the backdrop draws, in this
 * theme: enough to lift the composite to [scrimAtText] — the title band's alpha, whose two text
 * tones are unit-tested (`BackdropTextTest`) and device-measured (finding 14). Lyric tones are
 * then solved at [scrimAtText] exactly like the title, and inherit its guarantee. Lines lower in
 * the region sit under a stronger backdrop scrim plus the same fill, i.e. above the floor.
 */
fun lyricsFloorAlpha(isLight: Boolean, topFraction: Float): Float =
    lyricsScrimFloor(scrimAtFraction(isLight, topFraction), scrimAtText(isLight))

/** What a synced line with no text (an instrumental gap — a real line, see `LyricLine`) shows. */
const val INSTRUMENTAL_GAP = "♪"

/** The text a synced line renders: its own, or [INSTRUMENTAL_GAP] when it is blank. */
fun lineLabel(text: String): String = text.ifBlank { INSTRUMENTAL_GAP }
