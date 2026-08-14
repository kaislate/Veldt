// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

/**
 * The Subsonic API's numeric error codes.
 *
 * [meansCredentialsWontWork] exists because of a measurement, not a guess. Against Navidrome
 * 0.63.2 on 2026-08-14, a request with NO credentials returned code **10** ("missing
 * parameter: 'u'") — parameter validation runs before authentication — while a request with a
 * wrong password returned **40**. A client that offers "re-enter your password" only on 40
 * therefore sits silently on a 10 forever. Both codes, and 41, mean the same thing to a user:
 * this account cannot talk to this server until its credentials change.
 *
 * 40 is returned for an unknown username as well as a wrong password, which is correct
 * behaviour — it means the code cannot be used to discover which accounts exist — and it is
 * why nothing here tries to distinguish those two cases.
 */
enum class SubsonicError(val code: Int) {
    GENERIC(0),
    MISSING_PARAMETER(10),
    CLIENT_TOO_OLD(20),
    SERVER_TOO_OLD(30),
    WRONG_CREDENTIALS(40),
    TOKEN_AUTH_NOT_SUPPORTED(41),
    NOT_AUTHORIZED(50),
    TRIAL_EXPIRED(60),
    NOT_FOUND(70),

    /**
     * Any code this enum does not name. Its [code] is a sentinel that no server sends, so
     * [of] can never resolve a real response to it by matching; the response's actual number
     * is carried separately on [SubsonicResult.Failed] and must be used for display.
     */
    UNKNOWN(Int.MIN_VALUE),
    ;

    /** Whether a user must change this account's credentials before anything will work. */
    val meansCredentialsWontWork: Boolean
        get() = this == MISSING_PARAMETER ||
            this == WRONG_CREDENTIALS ||
            this == TOKEN_AUTH_NOT_SUPPORTED

    companion object {
        fun of(code: Int): SubsonicError = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
