// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.kaislate.veldtplayer.playback.browse.BrowseIds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * On-device proof of spec §6: a real `MediaBrowser` binds to the real [PlaybackService] (through
 * the manifest's `MediaLibraryService` intent filter) and walks the browse tree root → children,
 * the way a head unit does. `BrowseTreeTest` pins what the tree CONTAINS on the JVM; this pins that
 * the session actually serves it across a process boundary.
 *
 * Library-agnostic on purpose: the device's library may be empty, so it asserts the fixed
 * structure (the five root children) and that every category answers successfully, and only
 * walks into a leaf when "Songs" has one.
 *
 * `MediaBrowser` enforces its application thread; every call is made on the main looper via
 * [onMain], while the test thread blocks on the returned futures.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceBrowseInstrumentedTest {

    private lateinit var browser: MediaBrowser
    private val main = Handler(Looper.getMainLooper())

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        main.post(task)
        return task.get(TIMEOUT_S, TimeUnit.SECONDS)
    }

    private fun <T> ListenableFuture<LibraryResult<T>>.await(): LibraryResult<T> = get(TIMEOUT_S, TimeUnit.SECONDS)

    @Before fun connect() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = onMain {
            MediaBrowser.Builder(context, token).setApplicationLooper(Looper.getMainLooper()).buildAsync()
        }
        browser = future.get(TIMEOUT_S, TimeUnit.SECONDS)
    }

    @After fun release() {
        onMain { browser.release() }
    }

    @Test fun walksRootToChildren() {
        val root = onMain { browser.getLibraryRoot(null) }.await()
        assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
        assertEquals(BrowseIds.ROOT, root.value!!.mediaId)

        val children = onMain { browser.getChildren(BrowseIds.ROOT, 0, 50, null) }.await()
        assertEquals(LibraryResult.RESULT_SUCCESS, children.resultCode)
        val ids = children.value!!.map { it.mediaId }
        assertEquals(
            listOf(BrowseIds.RECENT, BrowseIds.PLAYLISTS, BrowseIds.ALBUMS, BrowseIds.ARTISTS, BrowseIds.SONGS),
            ids,
        )
        assertTrue(children.value!!.all { it.mediaMetadata.isBrowsable == true })

        for (id in ids) {
            val page = onMain { browser.getChildren(id, 0, 20, null) }.await()
            assertEquals("children of $id", LibraryResult.RESULT_SUCCESS, page.resultCode)
            assertTrue("page size honoured for $id", page.value!!.size <= 20)
        }

        val songs = onMain { browser.getChildren(BrowseIds.SONGS, 0, 1, null) }.await().value!!
        songs.firstOrNull()?.let { leaf ->
            assertEquals(true, leaf.mediaMetadata.isPlayable)
            val item = onMain { browser.getItem(leaf.mediaId) }.await()
            assertEquals(LibraryResult.RESULT_SUCCESS, item.resultCode)
            assertEquals(leaf.mediaId, item.value!!.mediaId)
        }
    }

    private companion object {
        const val TIMEOUT_S = 10L
    }
}
