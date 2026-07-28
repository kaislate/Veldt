// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.kaislate.veldtplayer.ui.nav.VeldtNavHost
import com.kaislate.veldtplayer.ui.theme.VeldtTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * **[enableEdgeToEdge] is what makes six screens' worth of inset work do anything below
     * API 35.**
     *
     * `SongsScreen`, `AlbumsScreen`, `ArtistsScreen`, `AlbumDetailScreen`, `ArtistDetailScreen`
     * and `SearchScreen` all split the system-bar insets into their own `contentPadding` so
     * rows scroll UNDER the translucent bottom chrome rather than stopping above it;
     * `AlbumDetailScreen`'s cover is full-bleed behind the status bar; `SearchScreen` does IME
     * arithmetic. All of it reads `WindowInsets.systemBars` — and in a window that still fits
     * system windows, every one of those values is ZERO, so the padding is nothing, the bleed
     * does not bleed and the IME maths is vacuous.
     *
     * The window was never told otherwise. `targetSdk = 36` gets edge-to-edge enforced for
     * free, but ONLY on API 35+; on API 29-34 — which `minSdk = 29` means this app ships to —
     * nothing sets `decorFitsSystemWindows = false` and the insets stay flat zero. The theme's
     * transparent `statusBarColor` looks like it covers this and does not: it colours a bar the
     * window is still laid out beneath.
     *
     * Called before [setContent] so the first composition already measures against real insets
     * and no screen lays out twice. From `androidx.activity`, which
     * `androidx.activity:activity-compose` already brings in — no new dependency.
     *
     * **Both bars are declared `dark`, not `auto`.** `SystemBarStyle.auto()` picks its icon
     * tint from the SYSTEM's day/night setting, and Veldt is dark on every phone (see
     * `VeldtTheme`) — so on a light-mode device `auto` would ask for dark icons and put them
     * on the app's near-black chrome, which is the light-mode bug one layer up.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A `dark` navigation-bar style asks the framework to enforce contrast behind a
        // transparent bar, which on three-button navigation draws a system scrim exactly where
        // the mini-player and the navigation bar already paint their own translucent pane —
        // two slabs, which is the one thing CHROME_ALPHA exists to avoid. This app supplies its
        // own scrim, so it opts out of the framework's. No-op from API 35 up, where the
        // property is deprecated and the system stops drawing the bar background at all.
        window.isNavigationBarContrastEnforced = false
        setContent { VeldtTheme { Surface(Modifier.fillMaxSize()) { VeldtNavHost() } } }
    }
}
