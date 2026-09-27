// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Settings → About's "Support Veldt": hidden with no links, one button per configured link, and
 * each button's intent through the `startActivity` seam (no browser is ever opened). The links
 * here are FIXTURES, not the shipped ones, so the tests say what the row does with any
 * configuration rather than pinning today's choice of platforms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportSectionTest {

    @get:Rule val compose = createComposeRule()

    private val started = ArrayList<Intent>()
    private var answer = true

    private val sponsors = "https://example.org/sponsors/someone"
    private val kofi = "https://example.org/ko-fi/someone"
    private val liberapay = "https://example.org/liberapay/someone"

    private fun show(links: List<SupportLink>) {
        compose.setContent {
            Column {
                SupportSection(links = links, startActivity = { started += it; answer })
            }
        }
    }

    private fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun `with every link null the row is not there`() {
        show(SupportLinks.configured(githubSponsors = null, koFi = null, liberapay = null))
        assertEquals(false, exists("Support Veldt"))
    }

    @Test fun `every configured link is offered, in order, and nothing else`() {
        show(SupportLinks.configured(githubSponsors = sponsors, koFi = kofi, liberapay = liberapay))
        compose.onNodeWithText("Support Veldt").performClick()
        assertEquals(
            listOf(true, true, true),
            listOf("GitHub Sponsors", "Ko-fi", "Liberapay").map(::exists),
        )
    }

    @Test fun `a null link is not offered`() {
        show(SupportLinks.configured(githubSponsors = null, koFi = kofi, liberapay = liberapay))
        compose.onNodeWithText("Support Veldt").performClick()
        assertEquals(
            listOf(false, true, true),
            listOf("GitHub Sponsors", "Ko-fi", "Liberapay").map(::exists),
        )
    }

    @Test fun `each link views its own page in a browser`() {
        show(SupportLinks.configured(githubSponsors = sponsors, koFi = kofi, liberapay = liberapay))
        compose.onNodeWithText("Support Veldt").performClick()
        listOf("GitHub Sponsors", "Ko-fi", "Liberapay").forEach { compose.onNodeWithText(it).performClick() }
        assertEquals(
            listOf(sponsors, kofi, liberapay).map { url ->
                listOf<Any?>(Intent.ACTION_VIEW, Uri.parse(url), setOf(Intent.CATEGORY_BROWSABLE))
            },
            started.map { listOf<Any?>(it.action, it.data, it.categories) },
        )
    }

    @Test fun `Close dismisses the dialog`() {
        show(SupportLinks.configured(githubSponsors = null, koFi = null, liberapay = liberapay))
        compose.onNodeWithText("Support Veldt").performClick()
        compose.onNodeWithText("Close").performClick()
        assertEquals(false, exists("Liberapay"))
    }

    /** No browser at all: the seam answers false, and the row says where the page is. */
    @Test fun `a link nothing can open is reported, not crashed on`() {
        answer = false
        show(SupportLinks.configured(githubSponsors = null, koFi = null, liberapay = liberapay))
        compose.onNodeWithText("Support Veldt").performClick()
        compose.onNodeWithText("Liberapay").performClick()
        assertEquals(
            listOf<Any?>(1, "No app can open links. The page is $liberapay"),
            listOf<Any?>(started.size, ShadowToast.getTextOfLatestToast()),
        )
    }

    /** Not a production VALUE, a production invariant: whatever is configured is a web page. */
    @Test fun `every shipped link is an https page`() {
        assertTrue(SupportLinks.configured().all { it.url.startsWith("https://") })
    }
}
