// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import com.kaislate.veldtplayer.data.library.model.Song

/** What happened when [SubsonicClient.fetchCatalog] tried to pull a whole account's library. */
sealed interface CatalogResult {

    /** Every song the account's server reported, across every album it listed. */
    data class Ok(val songs: List<Song>) : CatalogResult

    /** The server answered and refused — see [SubsonicError.meansCredentialsWontWork]. */
    data class Rejected(val error: SubsonicError, val code: Int, val message: String) : CatalogResult

    /** No usable answer partway through the sync: a dead socket, a malformed page, a 500. */
    data class Unreachable(val reason: String) : CatalogResult
}
