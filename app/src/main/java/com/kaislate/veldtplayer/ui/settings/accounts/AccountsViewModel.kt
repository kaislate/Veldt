// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaislate.veldtplayer.data.account.Account
import com.kaislate.veldtplayer.data.account.AccountRepository
import com.kaislate.veldtplayer.data.net.ConnectionOutcome
import com.kaislate.veldtplayer.data.net.SubsonicClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the "Test connection" button has to say. */
sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val description: String) : TestState
    data class BadCredentials(val message: String) : TestState
    data class Unreachable(val message: String) : TestState
}

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repo: AccountRepository,
    private val client: SubsonicClient,
) : ViewModel() {

    val accounts: StateFlow<List<Account>> = repo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()

    fun resetTest() { _test.value = TestState.Idle }

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

    fun add(displayName: String, url: String, username: String, password: String) {
        val base = baseUrlOf(url) ?: return
        val name = displayName.ifBlank { AccountForm.defaultName(url) }
        viewModelScope.launch { repo.add(name, base, username, password) }
    }

    fun update(sourceId: String, url: String, username: String, password: String) {
        val base = baseUrlOf(url) ?: return
        viewModelScope.launch {
            repo.updateCredentials(sourceId, base, username, password.ifEmpty { null })
        }
    }

    fun delete(sourceId: String) {
        viewModelScope.launch { repo.delete(sourceId) }
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
