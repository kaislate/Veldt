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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.data.library.scan.MediaStoreWatcher
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.data.settings.ThemeMode
import com.kaislate.veldtplayer.ui.nav.VeldtNavHost
import com.kaislate.veldtplayer.ui.theme.VeldtTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Process-scoped, not activity-scoped — the watcher keeps the library live while the app is
     * in the background playing. This class only supplies the *moment* to re-evaluate whether it
     * should be registered at all.
     */
    @Inject lateinit var mediaStoreWatcher: MediaStoreWatcher

    /** Read once in [onCreate] to seed the Compose collection; the mode itself lives in
     *  DataStore, not here. */
    @Inject lateinit var settingsRepository: SettingsRepository

    /**
     * Registration is re-stated on every resume rather than fired once on a grant, for the same
     * reason `PermissionGate` re-reads the permission here: both directions of the change happen
     * outside the app and outside any callback it owns — the user flipping audio access in
     * Settings, and the system revoking it for an unused app. A one-shot "register on grant"
     * would miss the revoke entirely, and would miss the grant too whenever it happened in
     * Settings rather than in the in-app dialog.
     *
     * [MediaStoreWatcher.sync] is idempotent, so the ordinary case — resuming with the
     * permission unchanged — costs a permission check and nothing else.
     */
    override fun onResume() {
        super.onResume()
        mediaStoreWatcher.sync()
    }
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
     * **Both bars are still declared `dark`, not `auto` — and that is now a known gap, not a
     * decision this slice makes correct.** The reasoning that justified it originally no
     * longer holds: `VeldtTheme` resolves `ThemeMode` (Light, Dark, or System) itself now, so
     * Veldt is emphatically NOT dark on every phone any more. `SystemBarStyle.auto()` would be
     * wrong for the same old reason — it reads the SYSTEM's day/night setting, which ignores a
     * user who explicitly picked Light or Dark in Settings — but forcing `dark()`
     * unconditionally is wrong too: on the genuinely light branch (`ThemeMode.LIGHT`, or
     * `SYSTEM` on a light-mode phone) the bars now sit on a LIGHT scaffold with dark-tinted
     * icons still requested on top of it. Fixing this needs the resolved mode available before
     * [setContent] — [enableEdgeToEdge] runs ahead of the first composition — which is
     * follow-up work, not something this change does.
     *
     * That choice also settles navigation-bar contrast, which is why nothing here sets
     * `isNavigationBarContrastEnforced`. It is `SystemBarStyle.auto()` that turns the
     * framework scrim ON; `dark()` already sets the flag false. Assigning it again after this
     * call is a no-op — and on three-button navigation the scrim would land exactly where the
     * mini-player already paints its own translucent pane, stacking two slabs. That reasoning
     * holds regardless of which `SystemBarStyle` eventually answers the question above.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            // SYSTEM until the first DataStore emission, never a hardcoded LIGHT or DARK — a
            // literal here would flash the wrong theme on every cold start, for everyone whose
            // stored choice is not SYSTEM, on every launch until the read completes.
            val mode by settingsRepository.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            VeldtTheme(mode) { Surface(Modifier.fillMaxSize()) { VeldtNavHost() } }
        }
    }
}
