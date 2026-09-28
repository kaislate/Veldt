// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import com.kaislate.veldtplayer.data.account.ServerAccount
import com.kaislate.veldtplayer.data.library.sync.SubsonicSyncWorker
import com.kaislate.veldtplayer.data.library.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server tab's pull-to-refresh decisions (round 5), under virtual time with a fake
 * connectivity check and hand-driven status flows — no WorkManager, no ConnectivityManager.
 * "The indicator" below is the life of [ServerRefresh.refresh]: the view model shows it for
 * exactly that long.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerRefreshTest {

    private val idle = SyncStatus(running = false, lastSuccessMs = 100L, songCount = 10, lastError = null)
    private val home = ServerAccount("home", "Home")
    private val office = ServerAccount("office", "Office")

    private val statuses = mutableMapOf<String, MutableStateFlow<SyncStatus>>()
    private val requested = mutableListOf<String>()
    private var online = true

    private fun statusOf(id: String) = statuses.getOrPut(id) { MutableStateFlow(idle) }

    private fun refresher() = ServerRefresh(
        isOnline = { online },
        request = { requested += it },
        status = { statusOf(it) },
    )

    private val byLabel: (ServerAccount) -> String = { "Navidrome" }

    @Test fun `offline stops at once with the offline message, and still requests the sync`() = runTest {
        online = false
        statusOf("home").value = idle.copy(running = true) // ENQUEUED behind its network constraint

        val message = refresher().refresh(listOf(home), byLabel)

        assertEquals(ServerRefresh.OFFLINE, message)
        assertEquals(listOf("home"), requested)
        assertEquals("no waiting at all", 0L, currentTime)
    }

    @Test fun `online, the indicator lasts until the sync finishes, and a success says nothing`() = runTest {
        val result = async { refresher().refresh(listOf(home), byLabel) }
        runCurrent()
        statusOf("home").value = idle.copy(running = true)
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse("still syncing: the indicator stays", result.isCompleted)

        statusOf("home").value = idle.copy(running = false, lastSuccessMs = 200L)
        runCurrent()

        assertTrue(result.isCompleted)
        assertEquals(null, result.await())
    }

    /** A finish the flow never showed as running (too quick to observe) still ends the wait. */
    @Test fun `a new result without a running emission still counts as finished`() = runTest {
        val result = async { refresher().refresh(listOf(home), byLabel) }
        runCurrent()
        statusOf("home").value = idle.copy(lastSuccessMs = 300L)
        runCurrent()
        assertEquals(null, result.await())
    }

    /** The state a previous sync left behind is not this sync finishing. */
    @Test fun `the pre-pull idle state does not end the wait`() = runTest {
        statusOf("home").value = idle.copy(lastError = SubsonicSyncWorker.MESSAGE_UNREACHABLE)
        val result = async { refresher().refresh(listOf(home), byLabel) }
        runCurrent()
        assertFalse(result.isCompleted)
        result.cancel()
    }

    @Test fun `after 30 seconds the indicator stops with the still-syncing message`() = runTest {
        val result = async { refresher().refresh(listOf(home), byLabel) }
        runCurrent()
        statusOf("home").value = idle.copy(running = true)
        advanceTimeBy(ServerRefresh.TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(result.isCompleted)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(ServerRefresh.STILL_SYNCING, result.await())
    }

    @Test fun `an unreachable failure names the server`() = runTest {
        val result = async { refresher().refresh(listOf(home), byLabel) }
        runCurrent()
        statusOf("home").value = idle.copy(running = true)
        runCurrent()
        statusOf("home").value = idle.copy(running = false, lastError = SubsonicSyncWorker.MESSAGE_UNREACHABLE)
        runCurrent()
        assertEquals("Couldn't reach Navidrome.", result.await())
    }

    /** With several accounts, the one that failed names itself; the indicator waits for both. */
    @Test fun `with several accounts it waits for all and names the one that failed`() = runTest {
        val result = async { refresher().refresh(listOf(home, office)) { it.displayName } }
        runCurrent()
        statusOf("home").value = idle.copy(running = true)
        statusOf("office").value = idle.copy(running = true)
        runCurrent()
        statusOf("home").value = idle.copy(lastSuccessMs = 500L)
        runCurrent()
        assertFalse("office is still running", result.isCompleted)

        statusOf("office").value = idle.copy(lastError = SubsonicSyncWorker.MESSAGE_BAD_CREDENTIALS)
        runCurrent()
        assertEquals("Office rejected the saved password.", result.await())
        assertEquals(listOf("home", "office"), requested)
    }

    @Test fun `failure messages by recorded reason`() {
        fun msg(error: String?) = ServerRefresh.failureMessage(idle.copy(lastError = error), "Home")
        assertEquals(
            listOf(null, "Couldn't reach Home.", "Home rejected the saved password.", "Couldn't sync Home."),
            listOf(
                msg(null),
                msg(SubsonicSyncWorker.MESSAGE_UNREACHABLE),
                msg(SubsonicSyncWorker.MESSAGE_BAD_CREDENTIALS),
                msg("something new"),
            ),
        )
    }

    @Test fun `no accounts does nothing`() = runTest {
        assertEquals(null, refresher().refresh(emptyList(), byLabel))
        assertEquals(emptyList<String>(), requested)
    }
}
