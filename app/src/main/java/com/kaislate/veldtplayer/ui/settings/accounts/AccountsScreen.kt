// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.ui.browse.SIDE_MARGIN
import com.kaislate.veldtplayer.ui.browse.SectionLabel

/**
 * One server account: add it, edit it, remove it.
 *
 * **UI for exactly one account, schema for many** — owner decision 2026-08-14. The table and
 * `sourceId` already carry an N-account shape; this screen deliberately does not, so P2 ships
 * without answering how two servers' libraries merge.
 *
 * Draws its own header, like every non-tab destination — see `VeldtNavHost.TAB_ROUTES`.
 */
@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    vm: AccountsViewModel = hiltViewModel(),
) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val direction = LocalLayoutDirection.current
    val existing = accounts.firstOrNull()

    var url by remember(existing?.sourceId) { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var username by remember(existing?.sourceId) { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember(existing?.sourceId) { mutableStateOf("") }
    var displayName by remember(existing?.sourceId) { mutableStateOf(existing?.displayName.orEmpty()) }

    val verdict = AccountForm.judge(url)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = contentPadding.calculateStartPadding(direction),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(direction),
            )
            .verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding()),
    ) {
        AccountsHeader(onBack = onBack)

        SectionLabel(if (existing == null) "Add a server" else "Server")

        Column(Modifier.padding(horizontal = SIDE_MARGIN)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; vm.resetTest() },
                label = { Text("Server address") },
                placeholder = { Text("192.168.1.10:4533") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    // THE cleartext enforcement. Cleartext is permitted process-wide because
                    // Android cannot whitelist a runtime-entered host, so this line is the
                    // only per-account signal that exists — which is why it renders as the
                    // url is typed rather than on a later confirmation.
                    when (verdict) {
                        is UrlVerdict.Cleartext -> Text(
                            "Not encrypted. Anyone on this network can read your password. " +
                                "Fine on a home LAN or over Tailscale; avoid on public Wi-Fi.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        is UrlVerdict.Secure -> Text("Encrypted with HTTPS.")
                        UrlVerdict.Invalid ->
                            if (url.isNotBlank()) Text("That does not look like a server address.")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; vm.resetTest() },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; vm.resetTest() },
                label = { Text(if (existing == null) "Password" else "New password (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Name (optional)") },
                placeholder = { Text(AccountForm.defaultName(url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            TestConnectionRow(
                state = test,
                enabled = AccountForm.canSubmit(url, username, password),
                onTest = { vm.testConnection(url, username, password) },
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (existing == null) {
                    Button(
                        onClick = { vm.add(displayName, url, username, password); password = "" },
                        enabled = AccountForm.canSubmit(url, username, password),
                    ) { Text("Add server") }
                } else {
                    Button(
                        onClick = { vm.update(existing.sourceId, url, username, password); password = "" },
                        enabled = AccountForm.canSubmitEdit(url, username, password),
                    ) { Text("Save") }
                    // width, not height — this Spacer is inside a Row. A height Spacer here
                    // renders as a zero-width gap and the two buttons touch.
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { vm.delete(existing.sourceId) }) { Text("Remove") }
                }
            }

            if (existing != null && !existing.hasSecret) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "This account's stored password can no longer be read — usually after a " +
                        "system update. Enter it again to reconnect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun TestConnectionRow(
    state: TestState,
    enabled: Boolean,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        TextButton(onClick = onTest, enabled = enabled && state != TestState.Running) {
            Text(if (state == TestState.Running) "Testing…" else "Test connection")
        }
        when (state) {
            is TestState.Ok -> Text(
                "Connected to ${state.description}.",
                style = MaterialTheme.typography.bodyMedium,
            )
            is TestState.BadCredentials -> Text(
                state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            is TestState.Unreachable -> Text(
                state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TestState.Idle, TestState.Running -> Unit
        }
    }
}

@Composable
private fun AccountsHeader(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = SIDE_MARGIN, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text("Servers", style = MaterialTheme.typography.titleLarge)
    }
}
