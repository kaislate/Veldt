// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.lyrics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.Lifecycle
import com.kaislate.veldtplayer.data.lyrics.LyricsSource
import com.kaislate.veldtplayer.ui.components.scrimAtFraction
import com.kaislate.veldtplayer.ui.components.scrimAtText
import com.kaislate.veldtplayer.ui.theme.ArtSeed
import com.kaislate.veldtplayer.ui.theme.BackdropText
import com.kaislate.veldtplayer.ui.theme.backdropText

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
 * The minimum composited scrim under lyrics, per theme — HEADROOM over [scrimAtText], not a
 * recalibration of it.
 *
 * Every solve here models the ground as the cover's MEAN colour lerped toward `bg`. The rendered
 * ground is the blurred cover, which varies across the frame; the title band's 0.62 happens to
 * hold where the title sits, but on device (Task 6, S21 FE, light theme, Sleep Token "Take Me
 * Back To Eden") the ground behind the PANE was locally darker than the mean: inactive lines
 * measured 4.03–4.43:1 and the active line 6.64–6.95:1, while the title on the same frame read
 * 7.01:1 — the finding-14 class of defect, modelled ground ≠ rendered ground. The art's share of
 * the ground is `1 - alpha`, so its spatial variance is damped in direct proportion: at 0.85 a
 * local deviation of the blurred art moves the ground 0.15/0.38 ≈ 0.39x as far as it does at
 * 0.62. Dark gets less (0.70: 0.30/0.38 ≈ 0.79x) because it did not fail on device (measured
 * 8.0–14.0:1) and every extra point of scrim is artwork the user no longer sees.
 */
private const val LYRICS_MIN_SCRIM_LIGHT = 0.85f
private const val LYRICS_MIN_SCRIM_DARK = 0.70f

/**
 * The composited scrim alpha every lyric line sits under at least — what the floor DRAWS:
 * `max(scrimAtText, LYRICS_MIN_SCRIM)` for the theme. Deliberately NOT the alpha the lyric tones
 * are solved at; see [lyricsBackdropText] for why solving here spent the headroom.
 */
fun lyricsTargetScrim(isLight: Boolean): Float =
    maxOf(scrimAtText(isLight), if (isLight) LYRICS_MIN_SCRIM_LIGHT else LYRICS_MIN_SCRIM_DARK)

/**
 * The floor a lyrics region whose top edge is at [topFraction] of the backdrop draws, in this
 * theme: enough to lift the composite there to [lyricsTargetScrim]. Lines lower in the region sit
 * under a stronger backdrop scrim plus the same fill, i.e. above the target.
 */
fun lyricsFloorAlpha(isLight: Boolean, topFraction: Float): Float =
    lyricsScrimFloor(scrimAtFraction(isLight, topFraction), lyricsTargetScrim(isLight))

/**
 * The two lyric tones: the headroom is in the SOLVE, not only in the drawn ground.
 *
 * **What went wrong solving at the drawn alpha** (Task 6a device re-measure, light, Sleep Token
 * "Chokehold", pane): the floor did its job on the ground — a uniform ~238–240 grey, the blurred
 * art's variance gone — but tones solved AT [lyricsTargetScrim] (0.85) picked a lighter ink
 * (109,109,109) that met 4.5:1 against that model exactly, so the measured inactive lines sat at
 * 4.46–4.50:1. A solve that targets the drawn ground leaves no margin for the drawn ground being
 * a hair darker than modelled — finding 14's lesson again.
 *
 * **So the tones are solved against the modelled ground at [scrimAtText]** — the title band's
 * alpha, lower than what is drawn — and the floor is pure margin. Why that is a margin and not a
 * shortfall, per theme: the ground is `artMean` lerped toward `bg` by the alpha, per sRGB channel,
 * so its luminance moves MONOTONICALLY from the art's toward `bg`'s as the alpha rises.
 *
 * - **Light** (dark ink): raising the alpha moves the ground toward a light `bg`. Whenever the
 *   art's mean is darker than `bg` — every real cover but a near-white one — the drawn ground is
 *   LIGHTER than the modelled one, i.e. further from the ink, and contrast only grows.
 * - **Dark** (light ink): symmetrically, raising the alpha moves the ground toward a dark `bg`;
 *   whenever the art's mean is lighter than `bg` — every real cover but a near-black one — the
 *   drawn ground is DARKER, further from the ink, and contrast only grows.
 *
 * **The exception is a cover more extreme than `bg` itself** (near-white in light, near-black
 * in dark): there, raising the alpha pulls the ground back TOWARD the ink, so the modelled alpha
 * is not the worst case. The worst case over everything a lyric line can sit under — any alpha
 * from [scrimAtText] up to 1 — is then at an END of that range, because contrast of a fixed ink
 * is monotonic in a ground that stays on one side of it. So each tone is solved at BOTH ends,
 * [scrimAtText] and `1` (the ground is `bg` itself), and the more extreme of the two is kept —
 * darker in light, lighter in dark — which meets the target at both ends and so everywhere
 * between. For every ordinary cover that is simply the [scrimAtText] tone.
 */
fun ArtSeed.lyricsBackdropText(bg: Color, isLight: Boolean): BackdropText {
    val modelled = backdropText(bg, scrimAtText(isLight), isLight)
    val atBg = backdropText(bg, 1f, isLight)
    fun extreme(a: Color, b: Color): Color =
        if (isLight) (if (a.luminance() <= b.luminance()) a else b)
        else (if (a.luminance() >= b.luminance()) a else b)
    return BackdropText(
        primary = extreme(modelled.primary, atBg.primary),
        secondary = extreme(modelled.secondary, atBg.secondary),
    )
}

/**
 * The vertical content padding that lets ANY line of a synced list reach the middle of a
 * viewport [viewportPx] tall (spec §7, "the active line is centred"), including the first and
 * last: half the viewport, less half of a typical line [linePx]. Never negative.
 */
fun centringPadding(viewportPx: Float, linePx: Float): Float =
    maxOf(0f, viewportPx / 2f - linePx / 2f)

/**
 * The `scrollOffset` for `scrollToItem(index, offset)` that puts that item's centre at the
 * viewport's centre, given the list's top content padding [paddingTopPx]. `scrollToItem` places
 * the item's top at `paddingTopPx - offset`; wanted is `viewportPx/2 - itemPx/2`.
 */
fun centreScrollOffset(viewportPx: Int, paddingTopPx: Int, itemPx: Int): Int =
    paddingTopPx - (viewportPx / 2 - itemPx / 2)

/** What a synced line with no text (an instrumental gap — a real line, see `LyricLine`) shows. */
const val INSTRUMENTAL_GAP = "♪"

/** The text a synced line renders: its own, or [INSTRUMENTAL_GAP] when it is blank. */
fun lineLabel(text: String): String = text.ifBlank { INSTRUMENTAL_GAP }
