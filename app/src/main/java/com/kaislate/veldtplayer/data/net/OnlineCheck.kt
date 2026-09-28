// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * "Is there a working internet connection right now?" — as a seam, so the server tab's
 * pull-to-refresh decision can be tested with a fake.
 *
 * Not `playback.NetworkReturn`: that answers "which network is the default", and a default
 * network can be a captive portal or a Wi-Fi with no uplink. The question at pull time is whether
 * a sync could actually run, which is what [NetworkCapabilities.NET_CAPABILITY_VALIDATED] means —
 * the system itself has reached the internet over it.
 */
fun interface OnlineCheck {
    fun isOnline(): Boolean

    companion object {
        /** The real check: the active network has INTERNET and has been VALIDATED. */
        fun of(context: Context): OnlineCheck {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            return OnlineCheck {
                val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
                caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
    }
}
