// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.di

import com.kaislate.veldtplayer.pill.NotificationStashNotice
import com.kaislate.veldtplayer.pill.PillOverlay
import com.kaislate.veldtplayer.pill.StashNotice
import com.kaislate.veldtplayer.pill.domain.overlay.OverlayRepository
import com.kaislate.veldtplayer.pill.overlay.OverlayWindowManager
import com.kaislate.veldtplayer.pill.overlay.WindowOverlayRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ServiceComponent

/**
 * The pill's service-scoped graph: everything that holds a window or a receiver lives and dies
 * with `PlaybackService` (spec §3: no second foreground service). `OverlayWindowManager` is
 * `@ServiceScoped`, so the repository the state machine drives and the [PillOverlay] the
 * controller watches are the same instance.
 */
@Module
@InstallIn(ServiceComponent::class)
abstract class PillServiceModule {

    @Binds abstract fun bindOverlayRepository(impl: WindowOverlayRepository): OverlayRepository

    @Binds abstract fun bindPillOverlay(impl: OverlayWindowManager): PillOverlay

    @Binds abstract fun bindStashNotice(impl: NotificationStashNotice): StashNotice
}
