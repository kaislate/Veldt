// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.util.lerp

/*
 * The geometry of the now-playing cover while a FINGER holds the sheet — pure, so every rule the
 * owner's "the album art should … stay under the finger" asks for is pinned by
 * `CoverFlightTest` rather than by feel.
 *
 * All rects and points are in the sheet host's coordinates (the box the app and the sheet both
 * fill). "Pin" is a point of the cover in the cover's OWN unit square: (0, 0) its top-left,
 * (1, 1) its bottom-right.
 *
 * The idea in one line: at touch-down the finger grabs a point of the cover; for the rest of the
 * drag the cover is drawn at its fraction-driven SIZE, scaled about that point, with that point
 * under the finger — so it follows the thumb in x and y instead of sliding down a fixed path
 * towards the bottom-left while the thumb runs away from it.
 */

/**
 * Past this fraction a held cover is blended onto its fixed path (which ends exactly on the
 * mini-player's thumbnail), reaching the path at 1. Without it the finger could hold the cover
 * anywhere at the collapsed end and the swap to the real thumbnail would jump; with it the last
 * 15% of the travel seats the cover as the finger arrives.
 */
internal const val SEAT_BLEND_START = 0.85f

/**
 * How much of the travel it takes for a grab that began OFF the cover (on the transport, on the
 * mini-player's title) to close the gap and bring the cover under the finger. A fraction of the
 * travel rather than a time, so the cover closes in as the hand moves — the gap never closes on
 * its own while the finger is still.
 */
internal const val GAP_CLOSE_FRACTION = 0.15f

/** The cover's size at sheet [fraction]: the full art slot at 0, the thumbnail at 1. */
internal fun coverSize(full: Size, thumb: Size, fraction: Float): Size = Size(
    lerp(full.width, thumb.width, fraction),
    lerp(full.height, thumb.height, fraction),
)

/**
 * The point of [cover] the finger at [point] grabs, in the cover's unit square — clamped to the
 * cover, so a touch that began off it grabs the NEAREST point of it.
 *
 * Clamped, not extrapolated, on purpose. The literal "keep the finger's relative offset" means a
 * grab on the mini-player's title, three thumbnails to the right of a 48 dp cover, becomes a pin
 * at x = 4; scaled up to a 300 dp cover that puts the cover a whole screen to the left of the
 * thumb. The nearest point of the cover is always somewhere the cover can actually be. What is
 * left of the offset is the [pinnedGap], which the drag closes (see [gapRemaining]).
 *
 * A degenerate cover pins its centre.
 */
internal fun grabPin(point: Offset, cover: Rect): Offset {
    if (cover.width <= 0f || cover.height <= 0f) return Offset(0.5f, 0.5f)
    return Offset(
        ((point.x - cover.left) / cover.width).coerceIn(0f, 1f),
        ((point.y - cover.top) / cover.height).coerceIn(0f, 1f),
    )
}

/** Where [pin] (unit square) lies on [cover], in host coordinates. */
internal fun pinPoint(cover: Rect, pin: Offset): Offset =
    Offset(cover.left + pin.x * cover.width, cover.top + pin.y * cover.height)

/**
 * The finger's distance from the point it grabbed, at the moment the drag began: zero for a
 * touch on the cover, the way to the nearest edge for one off it, plus the touch slop the finger
 * had already moved before the drag was recognised. Folding the slop in here is what keeps the
 * cover from jumping 8 dp on the first frame of every drag.
 */
internal fun pinnedGap(fingerAtStart: Offset, cover: Rect, pin: Offset): Offset =
    fingerAtStart - pinPoint(cover, pin)

/**
 * How much of the starting gap is still open, 1 → 0 as the sheet travels [GAP_CLOSE_FRACTION]
 * away from [startFraction] in either direction.
 */
internal fun gapRemaining(fraction: Float, startFraction: Float): Float =
    (1f - kotlin.math.abs(fraction - startFraction) / GAP_CLOSE_FRACTION).coerceIn(0f, 1f)

/** A rect of [size] placed so its [pin] (unit square) lies on [point] — "scaled about the point". */
internal fun rectAboutPoint(point: Offset, pin: Offset, size: Size): Rect =
    Rect(
        left = point.x - pin.x * size.width,
        top = point.y - pin.y * size.height,
        right = point.x - pin.x * size.width + size.width,
        bottom = point.y - pin.y * size.height + size.height,
    )

/**
 * [rect] moved (never resized) so it lies inside [bounds] — the cover cannot be dragged off the
 * screen. A rect bigger than the bounds on an axis is aligned to the bounds' start on that axis.
 */
internal fun clampInto(rect: Rect, bounds: Rect): Rect {
    fun axis(start: Float, length: Float, min: Float, max: Float): Float =
        if (length >= max - min) min else start.coerceIn(min, max - length)
    val left = axis(rect.left, rect.width, bounds.left, bounds.right)
    val top = axis(rect.top, rect.height, bounds.top, bounds.bottom)
    return Rect(left, top, left + rect.width, top + rect.height)
}

/**
 * How far a held cover is pulled onto its path at [fraction]: 0 until [SEAT_BLEND_START], rising
 * smoothly (smoothstep, so the pull neither kicks in nor lands abruptly) to 1 at the collapsed end.
 */
internal fun seatWeight(fraction: Float): Float {
    val t = ((fraction - SEAT_BLEND_START) / (1f - SEAT_BLEND_START)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Where a held cover is drawn.
 *
 * The cover's [pin] point sits on the finger, less whatever of the starting [gap] is still open
 * ([remaining]); it is [size]d by the fraction and scaled about that point; it is kept inside
 * [bounds]; and near the collapsed end it is blended onto [path] (its fixed-path rect at this
 * fraction) by [seatWeight].
 *
 * The seat blend is scaled by how much of the gap has CLOSED. At the start of a drag that begins
 * on the mini-player the fraction is 1 — full seat weight — yet the cover must stay exactly where
 * the thumbnail is until the finger has moved; weighting by the closed gap makes the first frame
 * of every drag reproduce the rect the cover already had, so a grab never jumps, and by the time
 * a downward drag reaches the seating zone the gap has long closed and the full weight applies.
 */
internal fun heldCoverRect(
    finger: Offset,
    pin: Offset,
    gap: Offset,
    remaining: Float,
    size: Size,
    bounds: Rect,
    path: Rect,
    fraction: Float,
): Rect {
    val anchor = finger - gap * remaining
    val held = clampInto(rectAboutPoint(anchor, pin, size), bounds)
    val seat = seatWeight(fraction) * (1f - remaining)
    return if (seat <= 0f) held else lerp(held, path, seat)
}
