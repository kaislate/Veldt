// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist.m3u

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What a document provider calls the file the user just picked.
 *
 * This exists so an imported playlist gets a name a human wrote. A name derived from the uri alone
 * is right for external storage, whose document ids are real paths, and useless for everything
 * else: Downloads hands back `1000000042`, and a playlist called "1000000042" is worse than no
 * import at all.
 *
 * **It can throw [SecurityException]**, and deliberately does. A grant that has lapsed will fail
 * the read a moment later too, so failing here — where `PlaylistPresentation.importOutcome` catches
 * it and reports it — is the same failure one step earlier, not a new one. Every OTHER failure is
 * swallowed: plenty of providers simply do not implement [OpenableColumns], and losing an import
 * over a missing column would be absurd when the uri can still supply a name.
 */
class DocumentNameReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun displayName(uri: String): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(
                Uri.parse(uri),
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
