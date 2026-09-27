// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PatternMatcher
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real check: [PackageManager.getPackageInfo] for Wisp's package at construction, kept
 * current afterwards by a runtime receiver for `ACTION_PACKAGE_ADDED`/`ACTION_PACKAGE_REMOVED`
 * filtered to exactly that package name (spec §3) via [IntentFilter.addDataSchemeSpecificPart] —
 * not a bare `scheme="package"` filter, which would fire for every app install on the device.
 *
 * The manifest's `<queries><package android:name="com.kaislate.veldt"/></queries>` entry
 * (added in this task) is what makes [PackageManager.getPackageInfo] see Wisp at all on API
 * 30+: package visibility hides other apps from `getPackageInfo`/`getInstalledPackages` unless
 * the caller declares an interest, and without the `<queries>` entry this class would always
 * read "not installed" regardless of whether Wisp actually is.
 *
 * Registered with [ContextCompat.RECEIVER_NOT_EXPORTED]: this only ever needs to see the
 * system's own package-change broadcasts, never another app's, so there is no reason to accept
 * one from outside the platform.
 */
@Singleton
class PackageManagerWispPresence @Inject constructor(
    @ApplicationContext private val context: Context,
) : WispPresence {

    private val _installed = MutableStateFlow(checkInstalled())
    override val installed: StateFlow<Boolean> = _installed.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            _installed.value = checkInstalled()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
            addDataSchemeSpecificPart(WISP_PACKAGE, PatternMatcher.PATTERN_LITERAL)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun checkInstalled(): Boolean = try {
        @Suppress("DEPRECATION") // getPackageInfo(String, Int) is deprecated for the PackageInfoFlags
        // overload added in API 33; this is called at every currently supported minSdk, so the
        // deprecated overload is the one that actually runs everywhere.
        context.packageManager.getPackageInfo(WISP_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        /** Veldt Wisp's application id. Also the manifest `<queries>` target. */
        const val WISP_PACKAGE = "com.kaislate.veldt"
    }
}
