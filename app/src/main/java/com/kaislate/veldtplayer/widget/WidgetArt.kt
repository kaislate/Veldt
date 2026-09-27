// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.kaislate.veldtplayer.ui.theme.ArtSeed
import com.kaislate.veldtplayer.ui.theme.ColorExtractor

/**
 * The current cover as the widget can carry it: a small, square, rounded copy, plus the tones
 * derived from the full-size original.
 *
 * **Small because the cover crosses a process boundary.** A `RemoteViews` bitmap is parcelled
 * to the launcher on every update, once per size variant on API 31+, and the platform caps a
 * widget's total bitmap memory; the bus holds a full-resolution decode. [SIZE_PX] is enough for
 * the 4×2 art slot at xxxhdpi and a fraction of a full cover's bytes.
 *
 * **Rounded here because `RemoteViews` cannot clip.** An `ImageView` in a widget has no remotable
 * outline or clip on API 29–30, so the corners are baked into the pixels.
 *
 * **Software pixels because the source may be `Config.HARDWARE`.** A hardware bitmap can neither
 * be drawn into a software canvas nor sampled by Palette; [ColorExtractor.toReadable] is the
 * copy the rest of the app already makes for exactly that.
 */
class WidgetArt(val bitmap: Bitmap?, val tones: WidgetTonePair) {

    companion object {
        const val SIZE_PX = 256

        /** No cover: the neutral tones every art-less surface in the app uses. */
        val NONE = WidgetArt(null, WidgetTonePair.of(ArtSeed.NEUTRAL))

        /** Walks every pixel (Palette) and draws a copy: never on the main thread. */
        fun prepare(source: Bitmap?): WidgetArt {
            if (source == null) return NONE
            val tones = WidgetTonePair.of(ColorExtractor.seedOf(source))
            val readable = ColorExtractor.toReadable(source) ?: return WidgetArt(null, tones)
            return WidgetArt(roundedSquare(readable), tones)
        }

        /** Centre-crops [src] to a square and scales it to [SIZE_PX], with rounded corners. */
        private fun roundedSquare(src: Bitmap): Bitmap? = try {
            val out = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            val side = minOf(src.width, src.height).toFloat()
            val scale = SIZE_PX / side
            val matrix = Matrix().apply {
                setTranslate(-(src.width - side) / 2f, -(src.height - side) / 2f)
                postScale(scale, scale)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
            }
            val radius = SIZE_PX * CORNER_FRACTION
            Canvas(out).drawRoundRect(RectF(0f, 0f, SIZE_PX.toFloat(), SIZE_PX.toFloat()), radius, radius, paint)
            out
        } catch (t: Throwable) {
            // An allocation failure costs the widget its picture, never the update: the text,
            // the tones and the buttons still go out.
            null
        }

        private const val CORNER_FRACTION = 0.12f
    }
}
