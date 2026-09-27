// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill.ui.island

import androidx.compose.ui.graphics.Color
import com.kaislate.veldtplayer.ui.theme.ArtSeed
import com.kaislate.veldtplayer.ui.theme.backdropMarks
import com.kaislate.veldtplayer.ui.theme.backdropText

/** The collapsed pill's colours. The pill draws an opaque [surface], so every role is solved against it. */
data class PillRoles(
    val surface: Color,
    val title: Color,
    val subtitle: Color,
    val controls: Color,
    val wave: Color,
)

/**
 * The expanded card's colours. The card draws the blurred cover under a [base]-coloured scrim,
 * so text and marks are solved against that composited ground ([ArtSeed.backdropText] /
 * [ArtSeed.backdropMarks]), the way the now-playing screen does it.
 */
data class CardRoles(
    val base: Color,
    val title: Color,
    val subtitle: Color,
    val icons: Color,
    /** The scrub bar's played portion (wave, baseline, thumb). */
    val progress: Color,
    /** The unplayed scrub track: quiet but operable, so solved rather than alpha-dimmed. */
    val track: Color,
    /** The little hills beside the title, per the wave-colour setting. */
    val wave: Color,
)

/**
 * Replaces Wisp's `PopUpColors` + `ColorExtractor` with Veldt's solved [ArtSeed] tones.
 *
 * Wisp coloured text with `onBg` and then alpha-dimmed it (`0.85f`, `0.9f`, the track at
 * `0.22f`); Veldt's rule is that hierarchy comes from a solved ratio, never from dimming a
 * colour toward its ground (see [ArtSeed.backdropText]). So every role here is a solved tone at
 * full alpha, and "quieter" means a lower solved ratio, not a lower alpha.
 *
 * Pure: the only inputs are a seed, the theme and the setting string.
 */
object PillColors {

    /**
     * The card's `bg` scrim over the blurred art, top and bottom.
     *
     * **Stronger than Wisp's (0.58 → 0.44), deliberately.** Wisp's weakest point composites to
     * a `bg` share of only ~0.50 (see [CARD_GROUND_SCRIM]); Veldt's own cover corpus
     * (`BackdropCorpus`, the one `scrimAtText` was derived from) shows no solvable tone clears
     * 7:1 / 4.5:1 below a share of ~0.60 on the extreme covers — black cover in light, white
     * cover in dark. 0.62 → 0.58 puts the weakest share at ~0.62, the same value the now-playing
     * title band uses, so the card's text meets the same bar as the now-playing screen's.
     */
    const val CARD_SCRIM_TOP = 0.62f
    const val CARD_SCRIM_BOTTOM = 0.58f

    /** The blurred art layer's alpha over the opaque base — Wisp's value. */
    const val CARD_ART_ALPHA = 0.90f

    /**
     * The WEAKEST share of `bg` in the card's ground anywhere text sits, as the single scrim
     * alpha [ArtSeed.backdropText] models (`art` lerped toward `bg` by this much).
     *
     * The card stacks two layers over an opaque `bg` base: the art at [CARD_ART_ALPHA], then a
     * `bg` scrim at [CARD_SCRIM_TOP]..[CARD_SCRIM_BOTTOM]. The art's share of the final pixel is
     * `CARD_ART_ALPHA * (1 - scrim)`, so `bg`'s is `1 - CARD_ART_ALPHA * (1 - scrim)`, weakest
     * where the scrim is weakest (the bottom). Using the weakest value makes the solved tones a
     * floor for the whole card, the same convention as `scrimAtText` on the now-playing screen.
     */
    val CARD_GROUND_SCRIM: Float = 1f - CARD_ART_ALPHA * (1f - CARD_SCRIM_BOTTOM)

    fun pill(seed: ArtSeed, isLight: Boolean, waveColorMode: String): PillRoles {
        val p = seed.colors(isLight)
        // Scrim 1f: the ground IS `bg` (no art under it), so this solves the accent hue at
        // 4.5:1 against bg — the "stronger accent" the accent-light mode asks for.
        val strongAccent = seed.backdropMarks(p.bg, 1f, isLight).accent
        return PillRoles(
            surface = p.bg,
            title = p.onBg,
            subtitle = p.onBgSecondary,
            controls = p.onBg,
            wave = waveColor(waveColorMode, standard = p.accent, strong = strongAccent, ink = p.onBg),
        )
    }

    fun card(seed: ArtSeed, isLight: Boolean, waveColorMode: String): CardRoles {
        val p = seed.colors(isLight)
        val text = seed.backdropText(p.bg, CARD_GROUND_SCRIM, isLight)
        val marks = seed.backdropMarks(p.bg, CARD_GROUND_SCRIM, isLight)
        return CardRoles(
            base = p.bg,
            title = text.primary,
            subtitle = text.secondary,
            icons = text.primary,
            progress = text.secondary,
            track = marks.quiet,
            // There is only one solved accent on the backdrop (4.5:1), so "auto" and
            // "accent-light" land on the same tone here; "white" is still the ink.
            wave = waveColor(waveColorMode, standard = marks.accent, strong = marks.accent, ink = text.primary),
        )
    }

    /**
     * The wave's colour for Wisp's three wave-colour modes, mapped onto solved roles:
     *
     * - `"white"` — the ground's ink (the primary text tone): white on a dark ground, and on a
     *   light ground the dark ink, because literal white on a tone-98 surface is invisible.
     * - `"accent-light"` — the accent hue solved to the stronger ratio ([strong]). Wisp lifted
     *   the accent 40% toward white "to buy legibility without a contrast calculation"; Veldt
     *   has the calculation, so it asks for the ratio directly.
     * - anything else, `"auto"` included — the standard solved accent ([standard]). Wisp's auto
     *   nudged the accent only as far as the ground required; this is that, solved.
     *
     * Unknown strings degrade to auto, as in Wisp.
     */
    fun waveColor(mode: String, standard: Color, strong: Color, ink: Color): Color = when (mode) {
        "white" -> ink
        "accent-light" -> strong
        else -> standard
    }
}
