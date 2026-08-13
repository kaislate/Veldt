// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song

/** The volume of a track whose absolute path sits under no mount root we recognise. */
const val VOLUME_UNKNOWN: String = "?"

/** Mount root of primary storage. Device-observed as the prefix of every `DATA` path on the fleet. */
private const val PRIMARY_MOUNT = "/storage/emulated/0/"

/** MediaStore's name for primary storage — `MediaStore.VOLUME_EXTERNAL_PRIMARY`, inlined so this
 *  file stays framework-free and JVM-testable. Asserted against the constant in Task 4. */
internal const val VOLUME_PRIMARY = "external_primary"

/**
 * Where a song sits on disk: a volume, the directories under it, and the file name.
 *
 * [segments] is volume-relative and **verbatim** — no trimming, no case folding (global
 * constraints 7 and 8). An empty [segments] means the file is at the volume root, which is a real
 * and observed case, not a degenerate one.
 */
data class SongLocation(
    val volume: String,
    val segments: List<String>,
    val fileName: String,
)

/**
 * This song's location, or null when it has none.
 *
 * A two-rung ladder mirroring `LocalSource.stableKey`'s reasoning:
 *
 * 1. **`relativeKey`** — volume-qualified by construction and built from the non-deprecated
 *    location columns. Preferred for exactly that reason.
 * 2. **`filePath`** — absolute, so the volume must be INFERRED and inference can fail. Second, not
 *    first. It is nonetheless load-bearing rather than defensive: a file at the volume root has
 *    `RELATIVE_PATH == "/"`, which `composeRelativeKey` trims to empty and rejects, so such a
 *    track has a null `relativeKey` by construction and reaches the tree only here. Observed on
 *    device 2026-08-14 with `/sdcard/veldt-root-probe.mp3`.
 * 3. **Neither** — null, and the caller buckets it as Unfiled rather than dropping it.
 */
fun Song.location(): SongLocation? {
    relativeKey?.let { key ->
        val separator = key.indexOf(LocalSource.VOLUME_SEPARATOR)
        if (separator > 0) {
            val volume = key.substring(0, separator)
            val rest = PathSegments.split(key.substring(separator + 1))
            // A relativeKey always carries a file name — composeRelativeKey rejects an empty one —
            // but this is read from data, so the empty case degrades to rung 2 rather than throwing.
            if (rest.isNotEmpty()) {
                return SongLocation(volume, rest.dropLast(1), rest.last())
            }
        }
    }
    filePath?.let { path ->
        val primary = path.startsWith(PRIMARY_MOUNT)
        val rest = PathSegments.split(if (primary) path.removePrefix(PRIMARY_MOUNT) else path)
        if (rest.isNotEmpty()) {
            return SongLocation(
                volume = if (primary) VOLUME_PRIMARY else VOLUME_UNKNOWN,
                segments = rest.dropLast(1),
                fileName = rest.last(),
            )
        }
    }
    return null
}
