// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.browse

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kaislate.veldtplayer.data.art.AlbumArtFetcher
import com.kaislate.veldtplayer.data.art.ArtDecode
import com.kaislate.veldtplayer.data.art.RemoteArtLoader
import com.kaislate.veldtplayer.data.art.SongArt
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * The `content://` uri a browse item's artwork is served under (spec §6).
 *
 * Why not the `veldt-art://` uri the session's own items carry: that scheme exists precisely so
 * that nothing outside this process can open it (see `VeldtArtUri`), and a browse item's icon is
 * opened by the HEAD UNIT's process — Android Auto's, not ours. A `content://` uri from our own
 * provider is the one form another app can open, and only with the per-uri read grant the service
 * hands out alongside each item.
 *
 * The uri carries the whole [SongArt], as `VeldtArtUri` does, so the provider needs no database
 * read to know what to draw. That is safe only because the provider is NOT exported: a caller can
 * open a uri only by holding a grant for exactly that uri, and only the service mints grants — for
 * uris it built itself.
 */
object BrowseArtUri {

    private const val PATH = "art"
    private const val Q_SOURCE = "src"
    private const val Q_PATH = "path"
    private const val Q_EMBEDDED = "emb"

    fun authority(context: Context): String = context.packageName + ".browseart"

    fun of(authority: String, art: SongArt): Uri = Uri.Builder()
        .scheme("content")
        .authority(authority)
        .appendPath(PATH)
        .appendPath(art.songId.toString())
        .appendQueryParameter(Q_SOURCE, art.uri)
        .apply { art.filePath?.let { appendQueryParameter(Q_PATH, it) } }
        .appendQueryParameter(Q_EMBEDDED, if (art.hasEmbeddedArt) "1" else "0")
        .build()

    fun parse(uri: Uri): SongArt? {
        if (uri.scheme != "content") return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != PATH) return null
        val id = segments[1].toLongOrNull() ?: return null
        return SongArt(
            songId = id,
            uri = uri.getQueryParameter(Q_SOURCE).orEmpty(),
            filePath = uri.getQueryParameter(Q_PATH),
            hasEmbeddedArt = uri.getQueryParameter(Q_EMBEDDED) == "1",
        )
    }
}

/**
 * Read-only artwork for browse items (spec §6): opens a [BrowseArtUri] as a JPEG thumbnail.
 *
 * It resolves nothing itself. [AlbumArtFetcher] walks the same ladder — MediaStore thumbnail,
 * embedded picture, the server's cover for a streamed track — that every other surface uses, so a
 * track has the same cover in the car as on the phone, server tracks included. The result is
 * scaled to [THUMB_PX] and kept in an [ArtFileCache] under `cacheDir`, so a grid the head unit
 * scrolls back to is served from disk.
 *
 * Declared `exported="false"` with `grantUriPermissions="true"`: nothing can read it without a
 * grant, and the only grants are the per-uri ones `PlaybackService` gives the browsing controller.
 * Every write operation is refused.
 */
class BrowseArtProvider : ContentProvider() {

    /** The remote-art half of the ladder, from the app's Hilt graph. An entry point rather than
     *  injection because Hilt does not inject ContentProviders, and resolved lazily because a
     *  provider is created before `Application.onCreate` has built that graph. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun remoteArt(): RemoteArtLoader
    }

    private val cache: ArtFileCache by lazy {
        ArtFileCache(File(requireContext().cacheDir, CACHE_DIR))
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val art = BrowseArtUri.parse(uri) ?: throw FileNotFoundException("not an art uri")
        val context = requireContext()
        val file = cache.getOrPut(uri.toString()) { out ->
            // A binder thread, where blocking is the contract: the caller is waiting on this fd.
            val bitmap = runBlocking {
                val remote = EntryPointAccessors.fromApplication(context, Deps::class.java).remoteArt()
                AlbumArtFetcher(art, context, ArtDecode.FULL, THUMB_PX, remote).fetchBitmap()
            } ?: return@getOrPut false
            val scaled = scaledDown(bitmap)
            try {
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } ?: throw FileNotFoundException("no artwork")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun scaledDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= THUMB_PX) return bitmap
        val scale = THUMB_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    private companion object {
        const val MIME = "image/jpeg"
        const val CACHE_DIR = "browse-art"

        /** A car tile or list icon; a fraction of the 1024px the lock screen gets. */
        const val THUMB_PX = 512
        const val JPEG_QUALITY = 90
    }
}
