// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.browse

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/**
 * The on-disk half of [BrowseArtProvider]: one encoded thumbnail per art key, in [dir], at most
 * [maxFiles] of them.
 *
 * A file rather than an in-memory cache because the provider's answer IS a file descriptor — the
 * head unit reads the bytes itself, in its own process — and because a car browsing an album grid
 * asks for the same covers every time it comes back to it. [dir] is under `cacheDir`: all of this
 * can be rebuilt from the library, so the system is welcome to clear it.
 *
 * Writes go to a temp file renamed into place, so a reader never opens a half-written image, and
 * two binder threads producing the same cover at once simply both rename a complete file.
 */
class ArtFileCache(private val dir: File, private val maxFiles: Int = MAX_FILES) {

    /** Where [key]'s image lives, whether or not it exists yet. The key is hashed: it holds a file
     *  path and a uri, neither of which belongs in a file name. */
    fun fileFor(key: String): File = File(dir, sha1(key) + ".jpg")

    /** The cached file for [key], producing it with [write] first if needed; null when [write]
     *  had nothing to write (no art) or failed. */
    fun getOrPut(key: String, write: (OutputStream) -> Boolean): File? {
        val target = fileFor(key)
        if (target.isFile) {
            // Touched so trim() keeps what is being used, not merely what was made last.
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        var temp: File? = null
        return try {
            dir.mkdirs()
            temp = File.createTempFile("art.", ".tmp", dir)
            val wrote = temp.outputStream().use(write)
            if (!wrote) {
                temp.delete()
                return null
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return null
                }
            }
            trim()
            target
        } catch (t: Throwable) {
            temp?.delete()
            null
        }
    }

    /** Keeps the [maxFiles] most recently used images and deletes the rest. */
    fun trim() {
        val images = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return
        if (images.size <= maxFiles) return
        images.sortedByDescending { it.lastModified() }.drop(maxFiles).forEach { it.delete() }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** ~300 covers at a few tens of KB each: a car's album grid, scrolled, and back. */
        const val MAX_FILES = 300
    }
}
