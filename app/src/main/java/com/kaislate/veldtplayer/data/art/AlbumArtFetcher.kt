// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import ealvatag.audio.AudioFileIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves a [SongArt] to a bitmap by walking [ArtSourcePlan.plan] and returning the
 * first source that produces one.
 *
 * EVERY source is wrapped in runCatching: `loadThumbnail` throws IOException for
 * art-less or deleted files, and eAlvaTag throws freely on malformed containers
 * (P1.2 hit exactly one bad .m4a on the test device). A missing cover must never
 * propagate as a crash — it degrades to the themed placeholder.
 *
 * @param sample linear decode divisor; see [ArtDecode].
 * @param maxPx ceiling on the longest edge of an EMBEDDED decode, or null for the picture
 *   frame's own size. It exists because the two rungs are bounded differently by nature: the
 *   thumbnail rung asks MediaStore for [THUMB_PX] and so is bounded already, while the
 *   embedded rung decodes whatever the file's picture frame happens to hold — a 3000x3000
 *   cover is ~34 MB of ARGB_8888. Null (the Coil/UI path) keeps the natural size, because
 *   the now-playing art and the palette extractor legitimately want every pixel the file
 *   has; the media session passes [SESSION_MAX_PX], because its bitmap is retained for the
 *   whole track by both `CacheBitmapLoader` and `MediaSessionBus`, and 34 MB is ~18% of the
 *   heap cap on the 2 GB device this app targets.
 */
class AlbumArtFetcher(
    private val data: SongArt,
    private val context: Context,
    private val sample: Int,
    private val maxPx: Int? = null,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        // null -> Coil reports error state -> ArtImage renders the themed placeholder
        val bitmap = fetchBitmap() ?: return null
        return DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            // Always false, including for a sampled decode, and that is deliberate.
            //
            // The flag exists for ONE consumer: `MemoryCacheService.isSizeValid`
            // rejects a cached bitmap when `multiplier > 1.0 && isSampled` — i.e.
            // when a request needs a bigger image than the cache holds. Compose
            // requests are `Precision.INEXACT`, so that gate is the only one that
            // runs. A sampled entry is ~32px against a full-screen request, so
            // flagging it truthfully makes EVERY lookup of it miss: the entry is
            // written and never read, and the backdrop re-decodes on every visit —
            // a MediaStore IPC, or a full AudioFileIO parse of the music file.
            //
            // The protection the flag would give is already total: a sampled decode
            // has its own base key AND its own MemoryCache.Key extras, so no
            // full-size request can reach this entry to be under-served by it.
            // Within a sampled key namespace every entry has the same sample, so
            // the check has nothing left to protect against. See [ArtDecode].
            isSampled = false,
            dataSource = DataSource.DISK,
        )
    }

    /**
     * The ladder itself, without Coil's wrapper types.
     *
     * Split out so the media-session artwork path
     * ([com.kaislate.veldtplayer.playback.VeldtBitmapLoader]) walks THIS ladder rather than
     * growing a second one. Coil wants a [FetchResult]; a `BitmapLoader` wants a [Bitmap];
     * both must agree on which source wins, or the notification and the app can disagree
     * about what a track's cover is.
     */
    suspend fun fetchBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        for (source in ArtSourcePlan.plan(data)) {
            val bitmap = when (source) {
                is ArtSource.Thumbnail -> loadThumbnail(source.uri)
                is ArtSource.Embedded -> loadEmbedded(source.filePath)
            }
            if (bitmap != null) return@withContext bitmap
        }
        null
    }

    /**
     * [ArtDecode.FULL] reproduces the previous fixed request exactly; a larger divisor asks
     * MediaStore for a proportionally smaller thumbnail, floored so a pathological divisor
     * cannot ask for a zero-sized image.
     */
    private fun loadThumbnail(uri: String): Bitmap? = runCatching {
        val side = (THUMB_PX / sample).coerceAtLeast(MIN_PX)
        context.contentResolver.loadThumbnail(Uri.parse(uri), Size(side, side), null)
    }.getOrNull()

    private fun loadEmbedded(filePath: String): Bitmap? = runCatching {
        val file = File(filePath)
        // Scoped storage (API 29+): many MediaStore _DATA paths are not readable as a
        // File. Same discipline as EAlvaTagReader — degrade rather than throw.
        if (!file.canRead()) return@runCatching null
        // getTag() returns Guava Optional<Tag>; orNull() -> Tag?.
        val tag = AudioFileIO.read(file).tag.orNull() ?: return@runCatching null
        val bytes = tag.artworkList.firstOrNull()?.binaryData ?: return@runCatching null
        decodeEmbedded(bytes)
    }.getOrNull()

    /**
     * The embedded rung's decode, split out because it is the only part of that rung a unit
     * test can reach: the rest needs a real file on disk with a real tag in it.
     */
    internal fun decodeEmbedded(bytes: ByteArray): Bitmap? {
        // inSampleSize 1 is BitmapFactory's default, so an uncapped FULL decodes exactly as
        // it did before the ceiling existed.
        val options = BitmapFactory.Options().apply { inSampleSize = embeddedSample(bytes) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * The requested divisor, raised until the decode fits under [maxPx].
     *
     * Raised in powers of two because that is what `inSampleSize` actually honours — it
     * rounds down to one — so a computed 3 would decode at 2 and land ABOVE the ceiling the
     * computation was for. The ceiling is a real bound, not a target: a 3000px cover under a
     * 1024px ceiling decodes at divisor 4 (750px), not 2 (1500px).
     */
    private fun embeddedSample(bytes: ByteArray): Int {
        // Null cap: the UI path, decoding exactly what it asked for.
        val cap = (maxPx ?: return sample).coerceAtLeast(MIN_PX)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        // -1 for bytes BitmapFactory cannot read as an image. Nothing to bound; let the real
        // pass return its own null rather than inventing a divisor for a size we don't know.
        if (longest <= 0) return sample
        var divisor = 1
        // `divisor > longest` is the exit for a pathological requested divisor: past it there
        // is nothing left to halve, and doubling on would overflow rather than converge.
        while ((longest / divisor > cap || divisor < sample) && divisor <= longest) divisor *= 2
        return divisor
    }

    class Factory(private val context: Context) : Fetcher.Factory<SongArt> {
        override fun create(data: SongArt, options: Options, imageLoader: ImageLoader): Fetcher =
            AlbumArtFetcher(data, context, options.artDecodeSample())
    }

    companion object {
        /** Large enough for the full-screen now-playing art on a 1080p phone. */
        private const val THUMB_PX = 1024

        /**
         * The media session's ceiling for [maxPx]. Deliberately the same number as
         * [THUMB_PX]: the notification's cover should be the same size whichever rung of the
         * ladder produced it, and 1024px is already the size the thumbnail rung hands the
         * full-screen art.
         */
        const val SESSION_MAX_PX = THUMB_PX

        /** Floor for a sampled request, so a huge divisor still yields a usable bitmap. */
        private const val MIN_PX = 16
    }
}
