package com.kaislate.veldtplayer.data.library.tag

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ealvatag.tag.TagOptionSingleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof that [EAlvaTagReader] ACTUALLY parses embedded tags with eAlvaTag
 * and does NOT silently fall back. Strategy: read a bundled fixture whose tags are
 * known, passing an ALL-NULL fallback. If the result carries the fixture's embedded
 * ALBUM_ARTIST / DISC / TRACK (fields the null fallback could never supply), the
 * parse genuinely happened. `assets/probe_tagged.mp3` is a self-authored silent MP3
 * tagged via eAlvaTag: TITLE/ARTIST/ALBUM/ALBUM_ARTIST="VeldtProbe AlbumArtist"/
 * TRACK=7/DISC_NO=2/YEAR=2011.
 */
@RunWith(AndroidJUnit4::class)
class EAlvaTagReaderInstrumentedTest {

    private val blankFallback = TrackTags(
        title = null, artist = null, album = null, albumArtist = null,
        trackNumber = null, discNumber = null, year = null, hasEmbeddedArt = false,
    )

    @Before fun setUp() {
        // Idempotent; the real VeldtApp also sets this. Guarantees the Android path.
        TagOptionSingleton.getInstance().isAndroid = true
    }

    private fun copyAssetToCache(name: String): File {
        val instr = InstrumentationRegistry.getInstrumentation()
        // Assets are bundled in the TEST apk → read from the instrumentation context.
        val assetCtx = instr.context
        // Write to the app-under-test cacheDir, which is guaranteed to exist.
        val outDir = instr.targetContext.cacheDir.apply { mkdirs() }
        val out = File(outDir, name)
        assetCtx.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    @Test fun parsesEmbeddedTags_notFallback() {
        val file = copyAssetToCache("probe_tagged.mp3")
        assertTrue("fixture must be readable", file.canRead())

        val result = EAlvaTagReader().read(file.absolutePath, blankFallback)

        // Fallback was all-null; any non-null value here can ONLY have come from eAlvaTag.
        assertEquals("Probe Title", result.title)
        assertEquals("Probe Artist", result.artist)
        assertEquals("Probe Album", result.album)
        assertEquals("VeldtProbe AlbumArtist", result.albumArtist) // MediaStore doesn't expose this
        assertEquals(7, result.trackNumber)
        assertEquals(2, result.discNumber)
        assertEquals(2011, result.year)
    }

    @Test fun unreadablePath_returnsFallbackUnchanged() {
        val result = EAlvaTagReader().read("/does/not/exist.mp3", blankFallback)
        assertEquals(blankFallback, result)
    }
}
