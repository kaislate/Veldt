// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real check, backed by [ProcessLifecycleOwner] (`androidx.lifecycle:lifecycle-process`).
 *
 * [Lifecycle.Event.targetState] is read directly off the event rather than re-reading
 * `ProcessLifecycleOwner.get().lifecycle.currentState` inside the observer: the two are the
 * same value at this point, but reading the event is what an observer is handed for, and
 * avoids a second hop back into the lifecycle object on every transition.
 */
@Singleton
class ProcessLifecycleAppForeground @Inject constructor() : AppForeground {

    private val _inForeground = MutableStateFlow(
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
    )
    override val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                _inForeground.value = event.targetState.isAtLeast(Lifecycle.State.STARTED)
            },
        )
    }
}
