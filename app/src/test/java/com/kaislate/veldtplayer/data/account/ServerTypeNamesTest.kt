// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The server tab's label rules (player-sheet/server-tab spec, Task B §2), as a pure function. */
class ServerTypeNamesTest {

    @Test fun `known types are spelled the way each project spells itself`() {
        val raw = listOf(
            "navidrome", "gonic", "airsonic", "airsonic-advanced", "ampache", "funkwhale", "lms",
            "nextcloud music", "supysonic",
        )
        assertEquals(
            listOf(
                "Navidrome", "gonic", "Airsonic", "Airsonic", "Ampache", "Funkwhale", "LMS",
                "Nextcloud", "Supysonic",
            ),
            raw.map(ServerTypeNames::displayName),
        )
    }

    @Test fun `known types match regardless of case and stray whitespace`() {
        assertEquals(
            listOf("Navidrome", "LMS", "gonic"),
            listOf("Navidrome", " LMS ", "GONIC").map(ServerTypeNames::displayName),
        )
    }

    @Test fun `an unknown type is shown as reported with its first letter capitalised`() {
        assertEquals(
            listOf("Astiga", "MyServer", "X"),
            listOf("astiga", "myServer", "x").map(ServerTypeNames::displayName),
        )
    }

    @Test fun `a server that answered without a type is plain Subsonic`() {
        assertEquals(
            listOf("Subsonic", "Subsonic"),
            listOf(ServerTypeStore.PLAIN, "  ").map(ServerTypeNames::displayName),
        )
    }

    private val home = ServerAccount("a", "Home")
    private val office = ServerAccount("b", "Office")

    @Test fun `no accounts means no tab`() {
        assertNull(ServerTypeNames.tabLabel(emptyList(), mapOf("a" to "navidrome")))
    }

    @Test fun `one account is labelled by its type`() {
        assertEquals("Navidrome", ServerTypeNames.tabLabel(listOf(home), mapOf("a" to "navidrome")))
    }

    @Test fun `one plain Subsonic account is labelled Subsonic`() {
        assertEquals("Subsonic", ServerTypeNames.tabLabel(listOf(home), mapOf("a" to ServerTypeStore.PLAIN)))
    }

    @Test fun `before the type is known the account's own name stands in`() {
        assertEquals("Home", ServerTypeNames.tabLabel(listOf(home), emptyMap()))
    }

    @Test fun `several accounts of one type share its name`() {
        assertEquals(
            "Navidrome",
            ServerTypeNames.tabLabel(listOf(home, office), mapOf("a" to "navidrome", "b" to "Navidrome")),
        )
    }

    @Test fun `accounts of different types are Servers`() {
        assertEquals(
            "Servers",
            ServerTypeNames.tabLabel(listOf(home, office), mapOf("a" to "navidrome", "b" to "gonic")),
        )
    }

    /** Mid-way through a second account's first sync: one name is a type, the other still the
     *  account's own name, and they do not agree. */
    @Test fun `a known and a not-yet-known account are Servers`() {
        assertEquals("Servers", ServerTypeNames.tabLabel(listOf(home, office), mapOf("a" to "navidrome")))
    }

    @Test fun `types for accounts that no longer exist are ignored`() {
        assertEquals(
            "Navidrome",
            ServerTypeNames.tabLabel(listOf(home), mapOf("a" to "navidrome", "gone" to "gonic")),
        )
    }
}
