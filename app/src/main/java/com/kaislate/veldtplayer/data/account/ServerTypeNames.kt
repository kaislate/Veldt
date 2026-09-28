// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

import java.util.Locale

/** The two things the server tab needs to know about an account: who it is, and what to call it. */
data class ServerAccount(val sourceId: String, val displayName: String)

/**
 * The server tab's label (player-sheet/server-tab spec, Task B §2), as pure functions so every
 * rule is a unit test rather than a device check.
 */
object ServerTypeNames {

    /**
     * How each known OpenSubsonic `type` is spelled on screen — the project's own spelling, not a
     * mechanical capitalisation: gonic styles itself lower-case, LMS is an acronym, and "nextcloud
     * music" is Nextcloud's Music app, which users know as Nextcloud. Keys are lower-case; the
     * lookup lower-cases the stored type, since servers are not consistent about case.
     */
    private val KNOWN = mapOf(
        "navidrome" to "Navidrome",
        "gonic" to "gonic",
        "airsonic" to "Airsonic",
        "airsonic-advanced" to "Airsonic",
        "airsonic advanced" to "Airsonic",
        "ampache" to "Ampache",
        "funkwhale" to "Funkwhale",
        "lms" to "LMS",
        "nextcloud music" to "Nextcloud",
        "nextcloud-music" to "Nextcloud",
        "supysonic" to "Supysonic",
    )

    /** What a server that answers without a `type` is: the protocol's original implementation. */
    const val PLAIN_SUBSONIC = "Subsonic"

    /** The label for several accounts that do not agree on one name. */
    const val MIXED = "Servers"

    /**
     * The display name for one stored type: [ServerTypeStore.PLAIN] (or blank) is
     * [PLAIN_SUBSONIC], a known type is its entry in [KNOWN], and anything else is shown as the
     * server reported it with its first letter capitalised — a new server should read as a name,
     * not be hidden behind a generic word.
     */
    fun displayName(serverType: String): String {
        val trimmed = serverType.trim()
        if (trimmed.isEmpty()) return PLAIN_SUBSONIC
        return KNOWN[trimmed.lowercase(Locale.ROOT)]
            ?: trimmed.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /**
     * The tab's label for [accounts], given the stored [types] (`sourceId` → raw type, as
     * [ServerTypeStore.types] emits it), or null when there are no accounts and so no tab.
     *
     * Each account is named first — its type's [displayName] once known, else its own display
     * name (just added, never synced) — and then: one distinct name among them is the label;
     * several are [MIXED]. That one rule covers the spec's cases without special-casing any of
     * them: a lone account shows its type or its name; two Navidromes show "Navidrome"; a
     * Navidrome beside a gonic shows "Servers"; and two never-synced accounts show "Servers"
     * unless the user gave them the same name.
     */
    fun tabLabel(accounts: List<ServerAccount>, types: Map<String, String>): String? {
        if (accounts.isEmpty()) return null
        val names = accounts.map { account ->
            types[account.sourceId]?.let(::displayName) ?: account.displayName
        }.distinct()
        return names.singleOrNull() ?: MIXED
    }
}
