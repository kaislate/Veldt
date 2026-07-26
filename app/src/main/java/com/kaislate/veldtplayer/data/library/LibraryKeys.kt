package com.kaislate.veldtplayer.data.library

/**
 * The grouping identity for albums and artists. Tags in real libraries are messy —
 * "Beatles", "beatles" and "BEATLES " are one artist to a human and must be one row
 * in the UI. The normalized form is also the navigation route parameter, so casing
 * variants resolve to the same destination.
 *
 * Display names keep their original spelling; only the KEY is folded.
 */
object LibraryKeys {
    fun normalize(value: String): String = value.trim().lowercase()
}
