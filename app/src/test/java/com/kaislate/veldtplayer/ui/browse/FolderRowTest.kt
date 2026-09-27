// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import com.kaislate.veldtplayer.data.library.FolderNode
import com.kaislate.veldtplayer.data.library.UNFILED_KEY
import com.kaislate.veldtplayer.data.settings.ThemeMode
import com.kaislate.veldtplayer.ui.theme.VeldtTheme
import com.kaislate.veldtplayer.ui.theme.neutralPalette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A directory row and its long-press menu, driven through [FolderListEntry] — the composable the
 * Folders list itself draws — rather than a re-assembly of it.
 *
 * The nodes here hold no songs, so the row's mosaic draws its placeholder and never asks Coil for
 * a cover; nothing asserted depends on the art.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pinned as the other Robolectric suites are.
@Config(sdk = [34])
class FolderRowTest {

    @get:Rule val compose = createComposeRule()

    private fun node(key: String, name: String, hidden: Boolean = false) = FolderNode(
        key = key, volume = key.substringBefore(':'), segments = key.substringAfter(':').split('/'),
        name = name, children = emptyList(), songs = emptyList(),
        deepSongCount = 0, deepDurationMs = 0L, deepFolderCount = 0, hidden = hidden,
    )

    private val verbs = ArrayList<Pair<FolderVerb, FolderScope>>()
    private val hides = ArrayList<Pair<String, Boolean>>()
    private val renames = ArrayList<String?>()
    private val resets = ArrayList<String>()

    @Composable
    private fun Entry(item: FolderRowItem) {
        var open by remember { mutableStateOf(false) }
        FolderListEntry(
            item = item,
            palette = neutralPalette(),
            menuOpen = open,
            onOpen = {},
            onOpenMenu = { open = true },
            onDismissMenu = { open = false },
            onVerb = { verb, scope -> verbs += verb to scope },
            onSetHidden = { key, hidden -> hides += key to hidden },
            onRename = { renames += item.renameableVolume },
            onResetName = { resets += it },
            modifier = Modifier.testTag("row"),
        )
    }

    /** The row reads the app theme (neutralPalette needs VeldtTheme's locals), so draw inside it. */
    private fun show(content: @Composable () -> Unit) =
        compose.setContent { VeldtTheme(ThemeMode.LIGHT, content) }

    private fun openMenu() = compose.onNodeWithTag("row").performTouchInput { longClick() }

    /** The colour a Text was actually laid out with — what the user sees, not what was intended. */
    private fun colourOf(text: String): Color {
        val layouts = ArrayList<TextLayoutResult>()
        // Unmerged: the row is one clickable, so the MERGED node's layout action answers for
        // whichever Text it picks — observed to be the mosaic's placeholder initial.
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().layoutInput.style.color
    }

    // ---- the row ----------------------------------------------------------------------

    /**
     * The row draws [FolderRowItem.label], not the node's own name — a volume row's node is
     * routinely `Music` on both volumes. Label and name differ here, so drawing the name fails.
     */
    @Test fun `the row draws its label, not the node's name`() {
        show { Entry(FolderRowItem(node("1234-5678:Music", "Music"), "SanDisk Ultra")) }
        compose.onNodeWithText("SanDisk Ultra").assertExists()
        compose.onNodeWithText("Music").assertDoesNotExist()
    }

    /**
     * A hidden row: the `Hidden` marker, and the name in the solved secondary tone — exactly the
     * theme's `onSurfaceVariant` at full opacity, so an alpha-dimmed primary cannot pass. A row that
     * is not hidden has no marker and does not use that tone for its name.
     */
    @Test fun `a hidden row is marked and toned, a visible one is neither`() {
        var secondary = Color.Unspecified
        show {
            secondary = MaterialTheme.colorScheme.onSurfaceVariant
            Column {
                Entry(FolderRowItem(node("v:Music/A", "A", hidden = true), "Drafts", "v:Music/A", true))
                FolderListEntry(
                    item = FolderRowItem(node("v:Music/B", "B"), "Records", "v:Music/B", false),
                    palette = neutralPalette(), menuOpen = false, onOpen = {}, onOpenMenu = {},
                    onDismissMenu = {}, onVerb = { _, _ -> }, onSetHidden = { _, _ -> },
                    onRename = {}, onResetName = {},
                )
            }
        }
        compose.onNodeWithText(HIDDEN_MARKER).assertExists()
        assertEquals(
            listOf<Any>(true, secondary, 1f, false),
            listOf<Any>(
                compose.onAllNodes(hasText(HIDDEN_MARKER))
                    .fetchSemanticsNodes().size == 1,
                colourOf("Drafts"),
                colourOf("Drafts").alpha,
                colourOf("Records") == secondary,
            ),
        )
    }

    // ---- the menu ---------------------------------------------------------------------

    /**
     * A visible folder's menu keeps every existing verb — "Add to playlist" included — and adds
     * "Hide from library", which hides the row's own key and closes the menu.
     */
    @Test fun `a visible folder offers hide, beside the existing verbs`() {
        show {
            Entry(FolderRowItem(node("v:BACKUP/Downloads", "Downloads"), "Downloads", "v:BACKUP/Downloads"))
        }
        openMenu()
        listOf("Play", "Shuffle", "Add to queue", "Add to playlist", "Hide from library")
            .forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Show in library").assertDoesNotExist()

        compose.onNodeWithText("Hide from library").performClick()
        compose.onNodeWithText("Hide from library").assertDoesNotExist()
        assertEquals(listOf("v:BACKUP/Downloads" to true), hides)
    }

    /** Hidden itself: "Show in library" instead, and the playlist verb still works. */
    @Test fun `a folder hidden itself offers show, and still adds to a playlist`() {
        show {
            Entry(
                FolderRowItem(
                    node("v:BACKUP/Downloads", "Downloads", hidden = true),
                    "Downloads", "v:BACKUP/Downloads", hiddenHere = true,
                ),
            )
        }
        openMenu()
        compose.onNodeWithText("Hide from library").assertDoesNotExist()
        compose.onNodeWithText("Show in library").performClick()
        openMenu()
        compose.onNodeWithText("Add to playlist").performClick()
        assertEquals(
            listOf<Any>(
                listOf("v:BACKUP/Downloads" to false),
                listOf(FolderVerb.PLAYLIST to FolderScope.WITH_SUBFOLDERS),
            ),
            listOf<Any>(hides, verbs),
        )
    }

    /**
     * Hidden only because a folder above it is: neither verb. "Show" would
     * have to un-hide a different folder than the one pressed.
     */
    @Test fun `a folder hidden only by its parent offers neither`() {
        show {
            Column {
                Entry(
                    FolderRowItem(
                        node("v:BACKUP/Downloads/Sub", "Sub", hidden = true),
                        "Sub", "v:BACKUP/Downloads/Sub", hiddenHere = false,
                    ),
                )
            }
        }
        openMenu()
        compose.onNodeWithText("Add to playlist").assertExists()
        compose.onNodeWithText("Hide from library").assertDoesNotExist()
        compose.onNodeWithText("Show in library").assertDoesNotExist()
    }

    @Test fun `the unfiled bucket offers neither`() {
        show {
            Entry(FolderRowItem(node(UNFILED_KEY, "Unfiled"), "Unfiled", exclusionKey = null))
        }
        openMenu()
        compose.onNodeWithText("Add to playlist").assertExists()
        compose.onNodeWithText("Hide from library").assertDoesNotExist()
        compose.onNodeWithText("Show in library").assertDoesNotExist()
    }

    // ---- volume rename (Step 5 spec §5) ----------------------------------------------

    private fun volumeRow(renamed: Boolean) = FolderRowItem(
        node = node("1234-5678:Music", "Music"),
        label = if (renamed) "Band card" else "SD card",
        exclusionKey = "1234-5678",
        renameableVolume = "1234-5678",
        renamed = renamed,
    )

    /** "Rename…" on a volume row; no "Reset name" until there is a name to reset. */
    @Test fun `a volume row offers rename, and reset only once renamed`() {
        show { Entry(volumeRow(renamed = false)) }
        openMenu()
        compose.onNodeWithText("Add to playlist").assertExists()
        compose.onNodeWithText("Hide from library").assertExists()
        compose.onNodeWithText("Reset name").assertDoesNotExist()
        compose.onNodeWithText("Rename…").performClick()
        assertEquals(listOf<String?>("1234-5678"), renames)
    }

    @Test fun `a renamed volume row offers reset, which resets that volume`() {
        show { Entry(volumeRow(renamed = true)) }
        openMenu()
        compose.onNodeWithText("Rename…").assertExists()
        compose.onNodeWithText("Reset name").performClick()
        assertEquals(listOf("1234-5678"), resets)
    }

    /** A directory row is not a volume: no rename, whatever its label. */
    @Test fun `a directory row offers no rename`() {
        show { Entry(FolderRowItem(node("v:Music/A", "A"), "A", "v:Music/A")) }
        openMenu()
        compose.onNodeWithText("Rename…").assertDoesNotExist()
        compose.onNodeWithText("Reset name").assertDoesNotExist()
    }

    // ---- the rename dialog ------------------------------------------------------------

    private fun field() = compose.onNode(hasSetTextAction())

    private fun fieldText(): String =
        field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /**
     * The dialog hands over what was typed and nothing else (the repository trims), starting from
     * the stored name — and a keystroke past forty characters is refused rather than Save being
     * disabled, so the field holds exactly forty.
     */
    @Test fun `the rename dialog starts from the stored name and stops at forty characters`() {
        val saved = ArrayList<String>()
        show {
            VolumeRenameDialog(
                initial = "Band card", placeholder = "Band card",
                onSave = { saved += it }, onDismiss = {},
            )
        }
        val before = fieldText()
        field().performTextClearance()
        field().performTextInput("x".repeat(40))
        field().performTextInput("y")
        val capped = fieldText()
        compose.onNodeWithText("Save").performClick()
        assertEquals(
            listOf<Any>("Band card", "x".repeat(40), listOf("x".repeat(40))),
            listOf<Any>(before, capped, saved),
        )
    }

    /** Saving an empty field hands over blank, which the repository reads as "reset". */
    @Test fun `saving a blank field hands over blank`() {
        val saved = ArrayList<String>()
        show {
            VolumeRenameDialog(
                initial = "", placeholder = "SD card", onSave = { saved += it }, onDismiss = {},
            )
        }
        field().performTextInput("   ")
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("   "), saved)
    }
}
