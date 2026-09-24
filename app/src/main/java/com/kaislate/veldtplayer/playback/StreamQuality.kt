// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import com.kaislate.veldtplayer.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the active network is metered, asked at the moment a track is opened.
 *
 * Production is `ConnectivityManager.isActiveNetworkMetered` (see `PlaybackModule`), which answers
 * `true` when there is no active network at all — harmless here, since nothing will load then.
 */
fun interface NetworkMeter {
    fun isMetered(): Boolean
}

/**
 * The cap to send, or null for original quality. Owner decision: the cap applies on METERED
 * networks only, and a stored 0 means "original" there too.
 */
internal fun effectiveMaxBitRate(metered: Boolean, cap: Int): Int? = if (metered && cap > 0) cap else null

/**
 * The bitrate a stream request should ask for right now (N2 Task 4).
 *
 * Read by the playback resolver on ExoPlayer's loader thread, once per open — so it must answer
 * without suspending. The setting is therefore collected into a [StateFlow] in this object's own
 * scope, and [currentMaxBitRate] is a plain read of its value plus one call to the meter.
 *
 * Until DataStore delivers its first value the cap reads as 0 (original). That window is the first
 * moments of the process, and erring toward original quality for one track is the safe side of
 * the owner's rule rather than a silent downgrade.
 */
@Singleton
class StreamQuality internal constructor(
    cap: Flow<Int>,
    private val meter: NetworkMeter,
    scope: CoroutineScope,
) {
    @Inject constructor(settings: SettingsRepository, meter: NetworkMeter) : this(
        cap = settings.meteredMaxBitRate,
        meter = meter,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val cap: StateFlow<Int> = cap.stateIn(scope, SharingStarted.Eagerly, 0)

    /** The `maxBitRate` to request, or null for original quality. */
    fun currentMaxBitRate(): Int? = effectiveMaxBitRate(meter.isMetered(), cap.value)
}
