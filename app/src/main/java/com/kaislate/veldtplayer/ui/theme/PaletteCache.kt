package com.kaislate.veldtplayer.ui.theme

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Palette generation walks every pixel of the bitmap — never on the main thread.
 * Results are cached by songId so revisiting a track (or scrolling back to it)
 * is free.
 */
@Singleton
class PaletteCache @Inject constructor() {

    private val cache = LruCache<Long, DominantColors>(CACHE_SIZE)

    suspend fun paletteFor(songId: Long, bitmap: Bitmap?): DominantColors {
        cache.get(songId)?.let { return it }
        val extracted = withContext(Dispatchers.Default) { ColorExtractor.extract(bitmap) }
        // Only cache real extractions; a null bitmap may just mean art hasn't loaded yet.
        if (bitmap != null) cache.put(songId, extracted)
        return extracted
    }

    private companion object {
        const val CACHE_SIZE = 16
    }
}
