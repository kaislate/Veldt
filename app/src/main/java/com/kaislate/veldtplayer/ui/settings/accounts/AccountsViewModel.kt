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

    fun testConnection(url: String, username: String, password: String) {
        val base = baseUrlOf(url) ?: run {
            _test.value = TestState.Unreachable("That does not look like a server address.")
            return
        }
        _test.value = TestState.Running
        viewModelScope.launch {
            _test.value = when (val outcome = client.probe(base, username, password)) {
                is ConnectionOutcome.Reachable -> TestState.Ok(
                    listOfNotNull(outcome.serverType, outcome.serverVersion).joinToString(" ")
                        .ifBlank { "Connected" }
                )
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
     * Only a URL change or a new password justifies a re-sync here (owner decision — spec §10:
     * sync runs on add, on a credential/URL change, and on the Refresh button, never merely
     * because the screen was saved). The previous url is read with a fresh [AccountRepository
     * .observe] call, deliberately NOT from [accounts] — that `StateFlow` is
     * `SharingStarted.WhileSubscribed`, so its cached value can still be the construction-time
     * default until something actually collects it, and this decision must not depend on whether
     * anything has.
     */
    fun update(sourceId: String, url: String, username: String, password: String) {
        val base = baseUrlOf(url) ?: run {
            _save.value = SaveState.InvalidUrl
            return
        }
        val passwordChanged = password.isNotEmpty()
        viewModelScope.launch {
            val previousUrl = repo.observe().first().firstOrNull { it.sourceId == sourceId }?.baseUrl
            val urlChanged = previousUrl != base
            val result = repo.updateCredentials(sourceId, base, username, password.ifEmpty { null })
            _save.value = saveStateOf(result)
            if (result is AccountWriteResult.Saved && (urlChanged || passwordChanged)) {
                sync.request(sourceId)
            }
        }
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
