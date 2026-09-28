// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nav

import com.kaislate.veldtplayer.data.settings.StoredTabs
import com.kaislate.veldtplayer.ui.nav.Destinations.ALBUMS
import com.kaislate.veldtplayer.ui.nav.Destinations.ARTISTS
import com.kaislate.veldtplayer.ui.nav.Destinations.FOLDERS
import com.kaislate.veldtplayer.ui.nav.Destinations.PLAYLISTS
import com.kaislate.veldtplayer.ui.nav.Destinations.SERVER
import com.kaislate.veldtplayer.ui.nav.Destinations.SONGS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bottom bar's arrangement rules (player-sheet/server-tab spec, Round 2 → D §3–§4). */
class TabArrangementTest {

    private val defaultFive = listOf(SONGS, ALBUMS, ARTISTS, PLAYLISTS, FOLDERS)

    // ------------------------------------------------------------------------------ order

    @Test fun `nothing stored is the default order`() {
        assertEquals(TabArrangement.DEFAULT_ORDER, TabArrangement.normalizedOrder(emptyList()))
    }

    @Test fun `unknown and duplicate ids are dropped`() {
        assertEquals(
            listOf(FOLDERS, SONGS, ALBUMS, ARTISTS, PLAYLISTS, SERVER),
            TabArrangement.normalizedOrder(
                listOf("podcasts", FOLDERS, SONGS, SONGS, ALBUMS, "", ARTISTS, PLAYLISTS, SERVER),
            ),
        )
    }

    /** An order saved before any server existed: the server tab lands after Folders, wherever
     *  Folders was moved to. */
    @Test fun `a server tab missing from the stored order lands after its default predecessor`() {
        assertEquals(
            listOf(FOLDERS, SERVER, PLAYLISTS, ARTISTS, ALBUMS, SONGS),
            TabArrangement.normalizedOrder(listOf(FOLDERS, PLAYLISTS, ARTISTS, ALBUMS, SONGS)),
        )
    }

    @Test fun `a missing first tab goes first, a missing middle tab after its default predecessor`() {
        assertEquals(
            listOf(SONGS, FOLDERS, ALBUMS, ARTISTS, PLAYLISTS, SERVER),
            TabArrangement.normalizedOrder(listOf(FOLDERS, ALBUMS, PLAYLISTS, SERVER)),
        )
    }

    // ------------------------------------------------------------------------------ shown

    @Test fun `the server tab is shown only while a server exists, keeping its slot`() {
        val tabs = StoredTabs(order = listOf(SERVER, SONGS, ALBUMS, ARTISTS, PLAYLISTS, FOLDERS))
        assertEquals(defaultFive, TabArrangement.shown(tabs, serverPresent = false))
        assertEquals(listOf(SERVER) + defaultFive, TabArrangement.shown(tabs, serverPresent = true))
    }

    @Test fun `hidden tabs leave the bar but stay in Settings' list`() {
        val tabs = StoredTabs(hidden = setOf(ARTISTS, "ghost"))
        assertEquals(listOf(SONGS, ALBUMS, PLAYLISTS, FOLDERS), TabArrangement.shown(tabs, false))
        assertEquals(defaultFive, TabArrangement.available(tabs, false))
    }

    /** Everything but the server tab hidden, then the server removed: the bar is never empty. */
    @Test fun `with every available tab hidden the first available tab is shown anyway`() {
        val tabs = StoredTabs(hidden = defaultFive.toSet())
        assertEquals(listOf(SERVER), TabArrangement.shown(tabs, serverPresent = true))
        assertEquals(listOf(SONGS), TabArrangement.shown(tabs, serverPresent = false))
    }

    // ------------------------------------------------------------------------------ start tab

    @Test fun `start defaults to Songs`() {
        assertEquals(SONGS, TabArrangement.startTab(StoredTabs(), serverPresent = false))
    }

    @Test fun `start defaults to Songs even when Songs is not first`() {
        assertEquals(SONGS, TabArrangement.startTab(StoredTabs(order = listOf(ALBUMS, SONGS)), false))
    }

    @Test fun `a chosen shown start tab is honoured`() {
        assertEquals(PLAYLISTS, TabArrangement.startTab(StoredTabs(start = PLAYLISTS), false))
    }

    @Test fun `a hidden start tab falls back to the first shown tab`() {
        val tabs = StoredTabs(order = listOf(ARTISTS, SONGS), hidden = setOf(SONGS), start = SONGS)
        assertEquals(ARTISTS, TabArrangement.startTab(tabs, serverPresent = false))
    }

    @Test fun `a server start tab falls back when the server is gone`() {
        val tabs = StoredTabs(order = listOf(ALBUMS, SONGS), start = SERVER)
        assertEquals(SERVER, TabArrangement.startTab(tabs, serverPresent = true))
        assertEquals(ALBUMS, TabArrangement.startTab(tabs, serverPresent = false))
    }

    @Test fun `an unknown start tab falls back to the first shown tab`() {
        assertEquals(ALBUMS, TabArrangement.startTab(StoredTabs(order = listOf(ALBUMS, SONGS), start = "ghost"), false))
    }

    // ------------------------------------------------------------------------------ hiding

    @Test fun `the last shown tab cannot be hidden`() {
        val tabs = StoredTabs(hidden = setOf(SONGS, ALBUMS, ARTISTS, PLAYLISTS))
        assertFalse(TabArrangement.canHide(tabs, FOLDERS, serverPresent = false))
        assertEquals(tabs, TabArrangement.withHidden(tabs, FOLDERS, hidden = true, serverPresent = false))
    }

    /** With a server, Folders is not the last — the server tab is still shown. */
    @Test fun `the server tab counts toward what is shown only while it exists`() {
        val tabs = StoredTabs(hidden = setOf(SONGS, ALBUMS, ARTISTS, PLAYLISTS))
        assertTrue(TabArrangement.canHide(tabs, FOLDERS, serverPresent = true))
        assertEquals(
            tabs.hidden + FOLDERS,
            TabArrangement.withHidden(tabs, FOLDERS, hidden = true, serverPresent = true).hidden,
        )
    }

    /** A stored id this build does not know is dropped on the next write of the set, and can
     *  never be written. */
    @Test fun `showing a tab again works, and unknown ids are cleaned out or refused`() {
        val tabs = StoredTabs(hidden = setOf(ALBUMS, "ghost"))
        val shownAgain = TabArrangement.withHidden(tabs, ALBUMS, hidden = false, serverPresent = false)
        assertEquals(emptySet<String>(), shownAgain.hidden)
        assertEquals(tabs, TabArrangement.withHidden(tabs, "ghost", hidden = true, serverPresent = false))
    }

    // ------------------------------------------------------------------------------ writing back

    /** The bar shows only shown tabs; dropping one must not move the hidden tab or the absent
     *  server tab out of their slots. */
    @Test fun `reordering a subset leaves every other slot where it was`() {
        val full = listOf(SONGS, ALBUMS, SERVER, ARTISTS, PLAYLISTS, FOLDERS)
        // Bar without the server (absent) and without Artists (hidden): FOLDERS dragged first.
        val bar = listOf(FOLDERS, SONGS, ALBUMS, PLAYLISTS)
        assertEquals(
            listOf(FOLDERS, SONGS, SERVER, ARTISTS, ALBUMS, PLAYLISTS),
            TabArrangement.reorderSubset(full, bar),
        )
    }

    @Test fun `reordering ignores ids the full order does not have`() {
        assertEquals(listOf(ALBUMS, SONGS), TabArrangement.reorderSubset(listOf(SONGS, ALBUMS), listOf("x", ALBUMS, SONGS)))
    }

    @Test fun `moved clamps its target and ignores a bad source`() {
        assertEquals(listOf("b", "c", "a"), TabArrangement.moved(listOf("a", "b", "c"), 0, 9))
        assertEquals(listOf("a", "b"), TabArrangement.moved(listOf("a", "b"), 5, 0))
    }

    // ------------------------------------------------------------------------------ drag geometry

    @Test fun `nearest slot picks the closest centre`() {
        val centers = listOf(30f, 90f, 150f)
        assertEquals(listOf(0, 1, 1, 2, -1), listOf(
            TabArrangement.nearestSlot(centers, -50f),
            TabArrangement.nearestSlot(centers, 70f),
            TabArrangement.nearestSlot(centers, 110f),
            TabArrangement.nearestSlot(centers, 500f),
            TabArrangement.nearestSlot(emptyList(), 10f),
        ))
    }

    @Test fun `a dragged row changes slot past half a row, clamped to the list`() {
        assertEquals(listOf(2, 2, 3, 0, 5, 2), listOf(
            TabArrangement.dragTarget(2, 20f, 56f, 6),
            TabArrangement.dragTarget(2, -27f, 56f, 6),
            TabArrangement.dragTarget(2, 30f, 56f, 6),
            TabArrangement.dragTarget(2, -500f, 56f, 6),
            TabArrangement.dragTarget(2, 500f, 56f, 6),
            TabArrangement.dragTarget(2, 500f, 0f, 6),
        ))
    }
}
