// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.tag

/**
 * Framework-free bag of the audio tags Veldt cares about. Produced either by an
 * eAlvaTag parse (best-effort) or from MediaStore-provided values (fallback), then
 * reconciled field-by-field via [TagMerge]. Kept `android.*`-free so the selection
 * logic is unit-testable on the JVM.
 */
data class TrackTags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val hasEmbeddedArt: Boolean,
)
