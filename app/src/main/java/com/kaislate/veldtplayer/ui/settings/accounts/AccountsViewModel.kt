// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaislate.veldtplayer.data.account.Account
import com.kaislate.veldtplayer.data.account.AccountRepository
import com.kaislate.veldtplayer.data.account.AccountWriteResult
import com.kaislate.veldtplayer.data.library.sync.SubsonicSync
import com.kaislate.veldtplayer.data.library.sync.SyncStatus
import com.kaislate.veldtplayer.data.net.ConnectionOutcome
import com.kaislate.veldtplayer.data.net.SubsonicClient
import com.kaislate.veldtplayer.data.scrobble.ScrobbleFlushScheduler
import com.kaislate.veldtplayer.data.scrobble.ScrobbleQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** What the "Test connection" button has to say. */
sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val description: String) : TestState
    data class BadCredentials(val message: String) : TestState
    data class Unreachable(val message: String) : TestState
}

/**
 * What the last "Add server" / "Save" did.
 *
 * Kept separate from [TestState] because the two answer different questions and a save must not
 * overwrite the result of a connection test the user is still reading. [SecretUnavailable] is the
 * reason this type exists at all — see
 * [com.kaislate.veldtplayer.data.account.AccountWriteResult.SecretUnavailable].
 */
sealed interface SaveState {
    data object Idle : SaveState
    data object Saved : SaveState

    /** The repository refused the address. The field-level hint already says so; this is the
     *  case where the UI thought the url was fine and the storage boundary disagreed. */
    data object InvalidUrl : SaveState

    /** Saved, but the password could not be stored on this device. NOT a credential error. */
    data object SecretUnavailable : SaveState

    /** The account was removed underneath the edit. */
    data object Gone : SaveState
}

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repo: AccountRepository,
    private val client: SubsonicClient,
    private val sync: SubsonicSync,
    private val scrobbleQueue: ScrobbleQueue,
    private val flushScheduler: ScrobbleFlushScheduler,
) : ViewModel() {

    val accounts: StateFlow<List<Account>> = repo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One shared [SyncStatus] [StateFlow] per account, built lazily. Never a fresh `stateIn` per
     *  call: [AccountsScreen] reads [syncStatus] on every recomposition, and a fresh upstream
     *  collection each time would mean a fresh `WorkManager` flow subscription each time too.
     *  [syncStatus] uses `computeIfAbsent`, not the `getOrPut` extension (fix round 1, Minor) —
     *  `getOrPut` on a `ConcurrentHashMap` is a plain get-then-put, not one atomic operation, so
     *  two callers racing on the same never-yet-seen [sourceId] could each build and subscribe
     *  their own `stateIn`, silently doubling the `WorkManager` flow subscriptions for it. */
    private val syncStatuses = ConcurrentHashMap<String, StateFlow<SyncStatus>>()

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()

    private val _save = MutableStateFlow<SaveState>(SaveState.Idle)
    val save: StateFlow<SaveState> = _save.asStateFlow()

    fun resetTest() {
        _test.value = TestState.Idle
        _save.value = SaveState.Idle
    }

    /**
     * [sourceId] is the account being edited, or null on the "Add server" form. When the server
     * accepts EXACTLY that account's saved credentials — same address, same username, same
     * password — the test was a successful authenticated request to that source, so its scrobble
     * auth-block is lifted and its queue flushed (finding 21). A test of credentials the user has
     * typed but not saved proves nothing about the saved ones, so it lifts nothing; saving them
     * does that, in [update].
     */
    fun testConnection(url: String, username: String, password: String, sourceId: String? = null) {
        val base = baseUrlOf(url) ?: run {
            _test.value = TestState.Unreachable("That does not look like a server address.")
            return
        }
        _test.value = TestState.Running
        viewModelScope.launch {
            _test.value = when (val outcome = client.probe(base, username, password)) {
                is ConnectionOutcome.Reachable -> {
                    if (sourceId != null && isSaved(sourceId, base, username, password)) unblockAndFlush(sourceId)
                    TestState.Ok(
                        listOfNotNull(outcome.serverType, outcome.serverVersion).joinToString(" ")
                            .ifBlank { "Connected" }
                    )
                }
                is ConnectionOutcome.Rejected ->
                    // Keyed on the classification, not on `code == 40`: absent credentials
                    // come back as 10, and a client that only knows 40 sits silently on it.
                    if (outcome.error.meansCredentialsWontWork) {
                        TestState.BadCredentials("The server rejected that username or password.")
                    } else {
                        TestState.Unreachable("The server refused: ${outcome.message} (${outcome.code})")
                    }
                is ConnectionOutcome.Unreachable -> TestState.Unreachable(outcome.reason)
            }
        }
    }

    /**
     * The url is still normalised here — the screen needs a base URL for `testConnection` and
     * for [AccountForm.defaultName] anyway — but the repository normalises again and may still
     * answer [AccountWriteResult.InvalidUrl]. That duplication is deliberate: the storage
     * boundary owns the invariant that no `user:password@` is ever written, and a boundary that
     * trusts its caller is not a boundary.
     */
    fun add(displayName: String, url: String, username: String, password: String) {
        val base = baseUrlOf(url) ?: run {
            _save.value = SaveState.InvalidUrl
            return
        }
        val name = displayName.ifBlank { AccountForm.defaultName(url) }
        viewModelScope.launch {
            val result = repo.add(name, base, username, password)
            _save.value = saveStateOf(result)
            // A brand new account has never synced; always worth requesting (N2 Task 3, spec
            // §5.4). SecretUnavailable also lands here with no sourceId to sync — nothing to do.
            if (result is AccountWriteResult.Saved) sync.request(result.sourceId)
        }
    }

    /**
     * Only a credentials change — the URL, the username, or a new password — justifies a re-sync
     * here (owner decision — spec §10: sync runs on add, on a credential/URL change, and on the
     * Refresh button, never merely because the screen was saved). The previous url and username
     * are read with a fresh [AccountRepository.observe] call, deliberately NOT from [accounts] —
     * that `StateFlow` is `SharingStarted.WhileSubscribed`, so its cached value can still be the
     * construction-time default until something actually collects it, and this decision must not
     * depend on whether anything has.
     *
     * The same credentials change also clears [sourceId]'s scrobble auth-block (N3 design spec §5:
     * "credentials change for an account ... clears its auth-block"). Until finding 21 only a NEW
     * PASSWORD counted, so restoring a wrong USERNAME left a queued play stranded; any of the three
     * now counts, because a block is the server rejecting the whole triple. This method is the one
     * place that already observes a credential change without teaching [AccountRepository]
     * anything about scrobbling. Any entries already queued for this source get one flush attempt
     * via [flushScheduler] — rather than waiting for this source's next unrelated successful
     * contact to piggyback on.
     */
    fun update(sourceId: String, url: String, username: String, password: String) {
        val base = baseUrlOf(url) ?: run {
            _save.value = SaveState.InvalidUrl
            return
        }
        val passwordChanged = password.isNotEmpty()
        viewModelScope.launch {
            val previous = repo.observe().first().firstOrNull { it.sourceId == sourceId }
            val credentialsChanged = passwordChanged ||
                previous?.baseUrl != base ||
                previous?.username != username
            val result = repo.updateCredentials(sourceId, base, username, password.ifEmpty { null })
            if (result is AccountWriteResult.Saved && credentialsChanged) {
                unblockAndFlush(sourceId)
                sync.request(sourceId)
            }
            // Last: "Saved" is announced once everything the save implies has been done, so
            // nothing observing it can see the account saved but still blocked.
            _save.value = saveStateOf(result)
        }
    }

    /** Lifts [sourceId]'s scrobble auth-block and, if anything is waiting, re-arms the flush job. */
    private suspend fun unblockAndFlush(sourceId: String) {
        scrobbleQueue.setAuthBlocked(sourceId, false)
        if (scrobbleQueue.forSource(sourceId).isNotEmpty()) flushScheduler.enqueue()
    }

    /** Whether ([base], [username], [password]) is exactly what [sourceId] has saved. */
    private suspend fun isSaved(sourceId: String, base: String, username: String, password: String): Boolean {
        val saved = repo.observe().first().firstOrNull { it.sourceId == sourceId } ?: return false
        return saved.baseUrl == base && saved.username == username && repo.password(sourceId) == password
    }

    /**
     * The three outcomes the caller has to tell apart, rendered as one.
     *
     * [AccountWriteResult.SecretUnavailable] must never become a credential message: the
     * password reaching this method has usually just been probed successfully against the real
     * server, so "wrong password" would send the user into a retry that fails identically.
     */
    private fun saveStateOf(result: AccountWriteResult): SaveState = when (result) {
        is AccountWriteResult.Saved -> SaveState.Saved
        AccountWriteResult.InvalidUrl -> SaveState.InvalidUrl
        is AccountWriteResult.SecretUnavailable -> SaveState.SecretUnavailable
        AccountWriteResult.NoSuchAccount -> SaveState.Gone
    }

    /**
     * The exact order matters (fix round 1, Important finding): [SubsonicSync.cancel] first — a
     * best-effort, cooperative stop that does NOT wait for a `doWork()` already past its network
     * call — THEN [AccountRepository.delete], THEN [SubsonicSync.purge]. `SongDao
     * .replaceSourceIfPresent` checks the account row inside its own write transaction, so once
     * the account row is gone (the middle step), any sync transaction still in flight writes
     * nothing; [purge]'s own delete only needs to clean up whatever committed BEFORE that middle
     * step. Purging before deleting the account row would race a sync that is still mid-write and
     * could leave its rows behind forever, for an id nothing will ever sync again — see
     * [SubsonicSync]'s KDoc.
     */
    fun delete(sourceId: String) {
        viewModelScope.launch {
            sync.cancel(sourceId)
            repo.delete(sourceId)
            sync.purge(sourceId)
        }
    }

    /** The Servers screen's Refresh button. */
    fun refresh(sourceId: String) = sync.request(sourceId)

    fun syncStatus(sourceId: String): StateFlow<SyncStatus> = syncStatuses.computeIfAbsent(sourceId) {
        sync.status(sourceId).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SyncStatus(running = false, lastSuccessMs = null, songCount = null, lastError = null),
        )
    }

    /**
     * What to store and to talk to, or null if the address is unusable.
     *
     * Cleartext and TLS both yield a base URL — the difference between them is a warning the
     * screen renders as the url is typed, never a refusal here. See [UrlVerdict.Cleartext].
     */
    private fun baseUrlOf(url: String): String? = when (val verdict = AccountForm.judge(url)) {
        is UrlVerdict.Secure -> verdict.normalized
        is UrlVerdict.Cleartext -> verdict.normalized
        UrlVerdict.Invalid -> null
    }
}
