// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.di

import javax.inject.Qualifier

/**
 * Marks the one [com.kaislate.veldtplayer.data.library.LibrarySource] that reads MediaStore.
 *
 * Two classes are MediaStore-specific *by design* rather than by accident — `LibraryScanWorker`
 * enumerates the device, and `PlaylistImporter` resolves filesystem paths out of an `.m3u` — so
 * neither may take a source from the registry and neither may loop over it. A remote source syncs
 * through its own worker (design spec §5.4).
 *
 * This qualifier is how that restriction is *stated structurally* instead of by a `local` string
 * at the injection site (Global Constraint 1): the annotation is part of the type Dagger resolves,
 * so a second source cannot satisfy it and no comparison has to be remembered.
 *
 * **Why a qualifier and not the concrete `LocalSource` type** (which is what the N0 plan's Task 4
 * brief specified): `LocalSource` is final and every one of its methods goes through a
 * `ContentResolver`, so depending on it directly makes both consumers untestable — the existing
 * `FakeSource` fixtures in `LibraryScanWorkerTest`, `PlaylistImporterTest` and
 * `PlaylistViewModelTest` cannot be passed where a `LocalSource` is required. A qualifier keeps the
 * restriction structural, keeps those fixtures meaningful, and leaves `LocalSource` final.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocalLibrary
