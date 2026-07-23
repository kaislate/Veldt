package com.kaislate.veldtplayer.data.library.tag

/**
 * Reads a file's audio tags, preferring the parser and degrading to [fallback]
 * (the MediaStore-derived values) on any failure. Implementations must never let a
 * tag-read error propagate into the scan.
 */
interface TagReader {
    fun read(filePath: String?, fallback: TrackTags): TrackTags
}
