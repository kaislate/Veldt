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
 */
class AlbumArtFetcher(
    private val data: SongArt,
    private val context: Context,
    private val sample: Int,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        for (source in ArtSourcePlan.plan(data)) {
            val bitmap = when (source) {
                is ArtSource.Thumbnail -> loadThumbnail(source.uri)
                is ArtSource.Embedded -> loadEmbedded(source.filePath)
            }
            if (bitmap != null) {
                return@withContext DrawableResult(
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
        }
        null // -> Coil reports error state -> ArtImage renders the themed placeholder
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
        // inSampleSize 1 is BitmapFactory's default, so FULL decodes exactly as before.
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    class Factory(private val context: Context) : Fetcher.Factory<SongArt> {
        override fun create(data: SongArt, options: Options, imageLoader: ImageLoader): Fetcher =
            AlbumArtFetcher(data, context, options.artDecodeSample())
    }

    private companion object {
        /** Large enough for the full-screen now-playing art on a 1080p phone. */
        const val THUMB_PX = 1024

        /** Floor for a sampled request, so a huge divisor still yields a usable bitmap. */
        const val MIN_PX = 16
    }
}
