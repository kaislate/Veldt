package com.kaislate.veldtplayer.data.library.model

/** Framework-free library domain models. `uri` is a String so pure code/tests
 *  never import android.net.Uri; the UI parses it at the play call site. */
data class Song(
    val id: Long,               // stable identity = MediaStore _ID
    val uri: String,            // content:// playable uri, as String
    val filePath: String?,      // MediaStore DATA path for tag reading; null for remote sources
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val durationMs: Long,
    val dateModifiedSec: Long,  // MediaStore DATE_MODIFIED (seconds) — scan-diff change key
    val hasEmbeddedArt: Boolean,
)

data class Album(val name: String, val albumArtist: String?, val songCount: Int)

data class Artist(val name: String, val albumCount: Int, val songCount: Int)
