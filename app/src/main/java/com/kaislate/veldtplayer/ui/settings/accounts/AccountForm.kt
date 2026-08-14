// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings.accounts

import com.kaislate.veldtplayer.data.net.SubsonicUrls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a typed server address is, once judged. */
sealed interface UrlVerdict {
    /** Usable over TLS. [normalized] is what to store. */
    data class Secure(val normalized: String) : UrlVerdict

    /**
     * Usable, but in the clear.
     *
     * This is a warning and not an error by owner decision: LAN and Tailscale servers
     * present as `http://` and refusing them would lock out the common self-hosted case.
     */
    data class Cleartext(val normalized: String) : UrlVerdict

    /** Not an address at all. */
    data object Invalid : UrlVerdict
}

/**
 * The pure form rules behind the account screen.
 *
 * Separated from the Composable so the rules are plain-JUnit testable — the same reason
 * `SearchFilter` and `FolderSort` are separate from their screens.
 */
object AccountForm {

    fun judge(raw: String): UrlVerdict {
        val normalized = SubsonicUrls.normalizeBase(raw) ?: return UrlVerdict.Invalid
        return if (normalized.startsWith("https://")) UrlVerdict.Secure(normalized)
        else UrlVerdict.Cleartext(normalized)
    }

    fun canSubmit(url: String, username: String, password: String): Boolean =
        judge(url) !is UrlVerdict.Invalid && username.isNotBlank() && password.isNotEmpty()

    /** As [canSubmit], but a blank password means "keep the stored one". */
    fun canSubmitEdit(url: String, username: String, password: String): Boolean =
        judge(url) !is UrlVerdict.Invalid && username.isNotBlank()

    /**
     * The host, so a user who names nothing still gets something recognisable in the list.
     *
     * A bare hostname is a host and comes back as itself: `navidrome` normalises to
     * `http://navidrome`, which is a real LAN address, so [FALLBACK_NAME] is reached only when
     * there is no host to take — see `AccountFormTest`.
     */
    fun defaultName(url: String): String {
        val normalized = SubsonicUrls.normalizeBase(url) ?: return FALLBACK_NAME
        return normalized.toHttpUrlOrNull()?.host?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME
    }

    private const val FALLBACK_NAME = "Server"
}
