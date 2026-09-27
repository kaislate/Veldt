// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import android.content.Intent
import android.net.Uri

/**
 * Where "Support Veldt" (Settings → About) can send someone, one entry per platform. Each is
 * nullable: a null entry is not shown, and with every entry null the row itself is hidden. This is
 * the ONE place they are configured.
 */
internal object SupportLinks {

    /**
     * GitHub Sponsors. Null until the owner's Sponsors profile is approved (it does not exist yet,
     * so a link would 404); then it becomes `https://github.com/sponsors/kaislate`.
     */
    val GITHUB_SPONSORS: String? = null

    /** Ko-fi. Null until the owner supplies the username. */
    val KO_FI: String? = null

    /** Liberapay. */
    val LIBERAPAY: String? = "https://liberapay.com/kaislate"

    /** The configured links, in display order. Empty means there is no "Support Veldt" row. */
    fun configured(
        githubSponsors: String? = GITHUB_SPONSORS,
        koFi: String? = KO_FI,
        liberapay: String? = LIBERAPAY,
    ): List<SupportLink> = listOfNotNull(
        githubSponsors?.let { SupportLink("GitHub Sponsors", it) },
        koFi?.let { SupportLink("Ko-fi", it) },
        liberapay?.let { SupportLink("Liberapay", it) },
    )
}

/** One way to support Veldt: the platform's [name] and its page. */
internal data class SupportLink(val name: String, val url: String) {

    /**
     * The page, in whatever browser the user has — the same user-started `ACTION_VIEW` as "Get
     * Veldt Wisp" (see [PillIntents.getWisp]): the browser goes online, never Veldt. See
     * `OfflineByDefaultAuditTest`'s KDoc.
     */
    fun intent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
}
