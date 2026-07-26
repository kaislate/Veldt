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
                    isSampled = false,
                    dataSource = DataSource.DISK,
                )
            }
        }
        null // -> Coil reports error state -> ArtImage renders the themed placeholder
    }

    private fun loadThumbnail(uri: String): Bitmap? = runCatching {
        context.contentResolver.loadThumbnail(Uri.parse(uri), THUMB_SIZE, null)
    }.getOrNull()

    private fun loadEmbedded(filePath: String): Bitmap? = runCatching {
        val file = File(filePath)
        // Scoped storage (API 29+): many MediaStore _DATA paths are not readable as a
        // File. Same discipline as EAlvaTagReader — degrade rather than throw.
        if (!file.canRead()) return@runCatching null
        // getTag() returns Guava Optional<Tag>; orNull() -> Tag?.
        val tag = AudioFileIO.read(file).tag.orNull() ?: return@runCatching null
        val bytes = tag.artworkList.firstOrNull()?.binaryData ?: return@runCatching null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    class Factory(private val context: Context) : Fetcher.Factory<SongArt> {
        override fun create(data: SongArt, options: Options, imageLoader: ImageLoader): Fetcher =
            AlbumArtFetcher(data, context)
    }

    private companion object {
        /** Large enough for the full-screen now-playing art on a 1080p phone. */
        val THUMB_SIZE = Size(1024, 1024)
    }
}
