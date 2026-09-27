// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.di

import com.kaislate.veldtplayer.pill.AppForeground
import com.kaislate.veldtplayer.pill.OverlayPermission
import com.kaislate.veldtplayer.pill.PackageManagerWispPresence
import com.kaislate.veldtplayer.pill.ProcessLifecycleAppForeground
import com.kaislate.veldtplayer.pill.SystemOverlayPermission
import com.kaislate.veldtplayer.pill.WispPresence
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the built-in pill's environment seams (P1.5c Task 2) to their real implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PillModule {

    @Binds abstract fun bindWispPresence(impl: PackageManagerWispPresence): WispPresence

    @Binds abstract fun bindOverlayPermission(impl: SystemOverlayPermission): OverlayPermission

    @Binds abstract fun bindAppForeground(impl: ProcessLifecycleAppForeground): AppForeground
}
