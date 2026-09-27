// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real check. One line on purpose: [Settings.canDrawOverlays] is a static platform call
 * with nothing to configure, so there is nothing here worth a device-only KDoc warning the way
 * [com.kaislate.veldtplayer.data.account.KeystoreKeyProvider] carries one for the Keystore.
 * What is worth testing — how the rest of the pill *reacts* to this being true or false — is
 * tested against [OverlayPermission] fakes, not this wrapper.
 */
@Singleton
class SystemOverlayPermission @Inject constructor(
    @ApplicationContext private val context: Context,
) : OverlayPermission {
    override fun isGranted(): Boolean = Settings.canDrawOverlays(context)
}
