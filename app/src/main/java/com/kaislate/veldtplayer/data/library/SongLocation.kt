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

/** Mount root under which every removable volume appears as `/storage/<id>/`. */
private const val STORAGE_MOUNT = "/storage/"

/** The `<id>` of [PRIMARY_MOUNT], which is emphatically NOT a removable volume id. */
private const val EMULATED = "emulated"

/**
 * The volume [path] sits on, paired with the part of [path] that is relative to it.
 *
 * Three cases, and the middle one exists because of a concrete split-root defect:
 *
 * 1. `/storage/emulated/0/…` → [VOLUME_PRIMARY]. Primary storage.
 * 2. `/storage/<id>/…` (`<id>` not `emulated`) → `<id>`, lowercased. A removable card. **Without
 *    this case a single card is torn into two tree roots**: its `Music/a.mp3` has
 *    `RELATIVE_PATH == "Music/"` so `composeRelativeKey` succeeds and rung 1 files it under volume
 *    `1234-5678`, while its `root.mp3` has `RELATIVE_PATH == "/"` which `composeRelativeKey` trims
 *    to empty and rejects — so it falls to this rung, and if this rung answered [VOLUME_UNKNOWN]
 *    the same physical card would appear twice, the second time as `?` containing the phantom
 *    directories `storage/` and `1234-5678/` that exist on no card.
 * 3. Anything else (`/mnt/weird/x.mp3`, and a bare `/storage/x.mp3` with no volume component) →
 *    [VOLUME_UNKNOWN], with the whole path kept as segments. Degradation, not a drop: the track
 *    still has a place in the tree.
 *
 * **The lowercase in case 2 applies to the volume identifier and to nothing else.** It is there so
 * this rung agrees with rung 1, whose volume comes from MediaStore's `VOLUME_NAME` and is lowercase
 * by convention — `composeRelativeKey` already treats the volume as a MediaStore identifier rather
 * than a user-authored name, while leaving every path segment verbatim. Global constraints 7 and 8
 * forbid folding the case of a *segment*; they do not bind a volume id, and folding one here is
 * what keeps one card one root. Do not extend this fold to segments, and do not remove it from the
 * volume — both directions are bugs, in opposite ways.
 */
private fun volumeOf(path: String): Pair<String, String> = when {
    path.startsWith(PRIMARY_MOUNT) -> VOLUME_PRIMARY to path.removePrefix(PRIMARY_MOUNT)
    path.startsWith(STORAGE_MOUNT) -> {
        val rest = path.removePrefix(STORAGE_MOUNT)
        val slash = rest.indexOf('/')
        val id = if (slash > 0) rest.substring(0, slash).lowercase() else ""
        // `/storage/emulated/1/…` (a secondary user) is deliberately NOT claimed as volume
        // "emulated": a confidently wrong volume is worse than an honest unknown.
        if (id.isEmpty() || id == EMULATED) VOLUME_UNKNOWN to path
        else id to rest.substring(slash + 1)
    }
    else -> VOLUME_UNKNOWN to path
}

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
        val (volume, relative) = volumeOf(path)
        val rest = PathSegments.split(relative)
        if (rest.isNotEmpty()) {
            return SongLocation(
                volume = volume,
                segments = rest.dropLast(1),
                fileName = rest.last(),
            )
        }
    }
    return null
}
