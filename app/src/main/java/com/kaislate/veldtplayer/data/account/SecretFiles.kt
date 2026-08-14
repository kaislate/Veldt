// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a sealed secret lives: one app-private file per account id.
 *
 * Not DataStore and not a Room column. A blob whose only reader is [SecretBox] has no business
 * in a table people query, and keeping it out of the database means a future `exportSchema` or
 * a debug DB dump cannot carry it. The file name is the account's own UUID; [safeName] refuses
 * anything that could climb out of the directory, which is defence against a future caller
 * rather than against today's, since today's ids are minted UUIDs.
 */
@Singleton
class SecretFiles @Inject constructor(@ApplicationContext private val context: Context) {

    private val dir: File get() = File(context.filesDir, "account-secrets").apply { mkdirs() }

    fun write(id: String, bytes: ByteArray) {
        val name = safeName(id) ?: return
        try {
            File(dir, name).writeBytes(bytes)
        } catch (_: IOException) {
            // A full disk must not crash account creation; the account will simply need its
            // password re-entered, which is the same state as an invalidated key.
        }
    }

    fun read(id: String): ByteArray? {
        val name = safeName(id) ?: return null
        val file = File(dir, name)
        return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    fun delete(id: String) {
        val name = safeName(id) ?: return
        File(dir, name).delete()
    }

    private fun safeName(id: String): String? =
        id.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '-' } }
}
