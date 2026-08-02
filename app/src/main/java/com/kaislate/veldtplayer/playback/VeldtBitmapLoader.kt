// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.kaislate.veldtplayer.data.art.AlbumArtFetcher
import com.kaislate.veldtplayer.data.art.ArtDecode
import com.kaislate.veldtplayer.data.art.SongArt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Media3's artwork loader for Veldt's session: the notification, the lock screen, Android
 * Auto and the built-in pill all get their cover through this.
 *
 * It resolves nothing itself. A [VeldtArtUri] decodes back to the same [SongArt] the browse
 * list and the now-playing screen use, and [AlbumArtFetcher] walks the same source ladder
 * (MediaStore thumbnail, then the embedded picture frame) it walks for them. There is one
 * art pipeline in this app and this is a second entry point to it, not a second copy.
 *
 * **Every future completes.** Media3 blocks notification rendering on the returned future,
 * so a pending one is not a missing cover — it is a missing notification. "This track has
 * no art", "the file was unreadable" and "the loader was released mid-flight" therefore all
 * complete *exceptionally* rather than resolving to null or being left hanging; a
 * `ListenableFuture<Bitmap>` has no way to say "successfully nothing".
 */
class VeldtBitmapLoader(
    private val loadArt: suspend (SongArt) -> Bitmap?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BitmapLoader {

    /**
     * The production wiring. [ArtDecode.FULL] because this art lands on the lock screen at
     * something close to full width — no divisor is applied to it at all.
     *
     * The ceiling is a different thing from the divisor and is not a quality decision: this
     * bitmap is retained for the whole track by `CacheBitmapLoader` AND by
     * `MediaSessionBus.albumArt`, and `MediaSessionBus.showsSamePicture` walks every pixel of
     * it on each push. Uncapped, a 3000x3000 embedded cover is ~34 MB of ARGB_8888 — ~18% of
     * the heap cap on the 2 GB device this app targets, with two of them in flight across a
     * track change. [AlbumArtFetcher.SESSION_MAX_PX] is the same size the thumbnail rung is
     * already bounded to, so this only closes the gap between the two rungs.
     */
    constructor(context: Context) : this(
        loadArt = { art ->
            AlbumArtFetcher(art, context, ArtDecode.FULL, AlbumArtFetcher.SESSION_MAX_PX)
                .fetchBitmap()
        }
    )

    /**
     * False for audio, and that is the whole point of the type.
     *
     * A loader that claimed audio would be inviting Media3 to hand it the track itself to
     * decode as a picture — the exact failure the private [VeldtArtUri] scheme exists to
     * prevent, arriving through the other door. Images are claimed because [decodeBitmap]
     * genuinely decodes image bytes.
     */
    override fun supportsMimeType(mimeType: String): Boolean = MimeTypes.isImage(mimeType)

    /**
     * For artwork that arrived as raw bytes rather than a uri. Veldt never sets
     * `artworkData` itself, but a MediaItem reaching the session from a `MediaController`
     * can, and `loadBitmapFromMetadata` prefers bytes over the uri when both are present.
     */
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = submit {
        BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    /**
     * Anything that is not a [VeldtArtUri] fails immediately rather than being attempted:
     * this loader can only resolve Veldt's own library rows, and pretending otherwise would
     * park a future that never completes.
     */
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val art = VeldtArtUri.parse(uri)
            ?: return failed(IllegalArgumentException("Not a Veldt artwork uri: $uri"))
        return submit { loadArt(art) }
    }

    /**
     * Drops in-flight work. Cancelling [scope] does NOT leave the futures pending — the
     * completion handler below turns the cancellation into an exceptional completion, so a
     * listener registered by Media3 still runs.
     */
    fun release() {
        scope.cancel()
    }

    private fun submit(block: suspend () -> Bitmap?): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        val job = scope.launch {
            try {
                val bitmap = block()
                if (bitmap != null) future.set(bitmap) else future.setException(NoArtwork())
            } catch (cancelled: CancellationException) {
                // Cooperative cancellation must stay cancellation; invokeOnCompletion below
                // is what finishes the future for this path.
                throw cancelled
            } catch (t: Throwable) {
                // A throw here would otherwise reach the scope's (absent) exception handler
                // and take the process down, while the future stayed pending forever.
                future.setException(t)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) future.setException(cause)
        }
        // Media3 cancels the future when the notification it was loading for is gone; without
        // this the ladder would keep parsing a file nobody is waiting for.
        future.addListener(
            { if (future.isCancelled) job.cancel() },
            MoreExecutors.directExecutor(),
        )
        return future
    }

    private fun failed(cause: Throwable): ListenableFuture<Bitmap> =
        SettableFuture.create<Bitmap>().apply { setException(cause) }

    /** "This track has no cover", as the only thing a `Future<Bitmap>` can say it with. */
    class NoArtwork : Exception("No artwork for this track")
}
