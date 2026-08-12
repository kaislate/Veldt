// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import com.kaislate.veldtplayer.data.art.SongArt
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE app palette source: one track's artwork in, the colours every surface themes itself
 * from out. Results are memoised by song id, so revisiting a track is free.
 *
 * The key is the Room surrogate `songs.id` (see `SongArt`), which is correct for the same reason
 * it is correct there: this is an in-memory `LruCache` that dies with the process, so uniqueness
 * among live rows is the entire requirement, and AUTOINCREMENT never reissues a freed id.
 *
 * **It loads the bitmap itself, and that is the point.** The cache is keyed on song id with
 * no decode-size component, so as long as callers handed bitmaps in, nothing structural
 * stopped the now-playing backdrop's deliberately tiny decode (`ArtDecode`, currently 1/32 —
 * roughly a 32px image) being extracted and then cached as the whole app's palette for that
 * track, for the rest of the session. Taking a [SongArt] instead of a [Bitmap] makes
 * "the palette comes from the full-size artwork" an invariant of this class rather than a
 * discipline every call site has to keep: there is no longer an argument through which a
 * sampled bitmap can arrive.
 *
 * Extraction walks every pixel, so it is dispatched off the main thread here — callers only
 * have to be in a coroutine.
 */
@Singleton
class PaletteCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val cache = LruCache<Long, DominantColors>(CACHE_SIZE)

    /** The palette for [art]'s track, or the neutral fallback when there is no artwork. */
    suspend fun paletteFor(art: SongArt?): DominantColors {
        if (art == null) return ColorExtractor.extract(null)
        cache.get(art.songId)?.let { return it }
        val bitmap = loadFullSize(art)
        val extracted = withContext(Dispatchers.Default) { ColorExtractor.extract(bitmap) }
        // Only cache real extractions; a null bitmap may just mean art hasn't loaded yet.
        if (bitmap != null) cache.put(art.songId, extracted)
        return extracted
    }

    /**
     * The artwork at its natural size — deliberately WITHOUT `artDecodeSample`, so this
     * shares the one full-size Coil entry every other surface draws from and can never pick
     * up the backdrop's sampled one.
     *
     * `allowHardware(false)` because `Palette` reads pixels on the CPU and a
     * `Config.HARDWARE` bitmap's pixels live in graphics memory. It is necessary but NOT
     * sufficient: the flag governs Coil's own decoder, and `AlbumArtFetcher` returns a
     * fully-formed `DrawableResult` that bypasses the decoder — and Coil's memory-cache key
     * does not include the flag, so a hardware bitmap cached by another surface can still be
     * handed back here. `ColorExtractor.toReadable` is the actual guarantee; this flag just
     * stops the common path allocating a second copy to satisfy it.
     */
    private suspend fun loadFullSize(art: SongArt): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context)
            .data(art)
            .allowHardware(false)
            .build()
        context.imageLoader.execute(request).drawable?.toBitmap()
    }.getOrNull()

    private companion object {
        const val CACHE_SIZE = 16
    }
}
