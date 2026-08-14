// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * `SecretFiles` shipped with no test at all, while its KDoc made a security claim:
 * "[safeName] refuses anything that could climb out of the directory."
 *
 * That property had been checked once, in a temporary Robolectric probe deleted before commit,
 * so its only durable form was a comment — standing rule 3 exactly. Three branches were
 * unexercised except incidentally with well-formed UUIDs: a rejected name, `isFile` false, and a
 * delete of a file that is not there.
 *
 * Robolectric because `Context.filesDir` is the whole subject; there is no seam to fake without
 * faking the thing under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretFilesTest {

    private lateinit var ctx: Context
    private lateinit var files: SecretFiles

    /**
     * Every path under `filesDir`, so an escape is caught wherever it lands.
     *
     * Scoped to the whole of `filesDir` and not to `account-secrets/`: `File(dir, "../evil")`
     * resolves to `filesDir/evil`, which a listing of the secrets directory alone would report
     * as "nothing was written".
     */
    private fun tree(): Set<String> =
        ctx.filesDir.walkTopDown().map { it.path }.toSet()

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        files = SecretFiles(ctx)
        // Force the directory into existence so the first snapshot is not "the dir appeared".
        files.read("warmup")
    }

    @Test fun `no name that could climb out of the directory is ever written`() {
        val hostile = listOf(
            "..",
            ".",
            "../evil",
            "../../evil",
            "..\\evil",
            "sub/../../evil",
            "a/b",
            "/etc/passwd",
            "C:\\evil",
            "%2e%2e%2fevil",
            "\u0000evil",
            "evil\u0000",
            "with space",
            "quote'name",
            "dotted.name",
            "~",
            "",
            "   ",
        )

        val survivors = hostile.filter { name ->
            val before = tree()
            val accepted = files.write(name, "hunter2".toByteArray())
            val appeared = tree() - before
            accepted || appeared.isNotEmpty() || files.read(name) != null
        }

        // WHICH names got through, named in the message — a count could not say what to fix.
        assertEquals("these names were accepted by safeName", emptyList<String>(), survivors)

        // Guards the guard: if `write` had been broken into a universal no-op the list above
        // would be empty for the wrong reason, and this suite would report a clean bill of
        // health for a class that stores nothing at all.
        val real = UUID.randomUUID().toString()
        assertTrue("a legal id was refused", files.write(real, "hunter2".toByteArray()))
        assertEquals("hunter2", files.read(real)?.toString(Charsets.UTF_8))
    }

    @Test fun `a refused name writes nothing and reads back as nothing`() {
        val before = tree()
        assertEquals(false, files.write("../evil", "hunter2".toByteArray()))
        assertEquals("a file appeared for a refused name", emptySet<String>(), tree() - before)
        assertNull(files.read("../evil"))
    }

    @Test fun `reading an id that was never written is null, not an error`() {
        // The `isFile` false branch: no file, and — separately — a directory sitting where the
        // file would be, which `readBytes` would otherwise throw on.
        assertNull(files.read(UUID.randomUUID().toString()))

        val asDirectory = UUID.randomUUID().toString()
        File(ctx.filesDir, "account-secrets/$asDirectory").mkdirs()
        assertNull("a directory was read as if it were a sealed secret", files.read(asDirectory))
    }

    @Test fun `deleting an id that is not there does not throw`() {
        val absent = UUID.randomUUID().toString()
        files.delete(absent) // must simply return
        files.delete("../evil") // and so must a name that was never legal
        assertNull(files.read(absent))
    }

    @Test fun `a sealed blob survives the round trip byte for byte`() {
        val id = UUID.randomUUID().toString()
        // Not a String: the real payload is AES-GCM output, which is not valid UTF-8 and which
        // a String round trip would silently mangle.
        val blob = ByteArray(35) { (it * 7 - 128).toByte() }
        assertTrue(files.write(id, blob))
        assertEquals(blob.toList(), files.read(id)?.toList())

        files.delete(id)
        assertNull("delete left the secret on disk", files.read(id))
    }

    @Test fun `two accounts do not share a file`() {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        files.write(a, "alpha".toByteArray())
        files.write(b, "beta".toByteArray())
        assertEquals(
            listOf("alpha", "beta"),
            listOf(a, b).map { files.read(it)?.toString(Charsets.UTF_8) },
        )
        files.delete(a)
        // Deleting one must not take the other with it.
        assertEquals(listOf(null, "beta"), listOf(a, b).map { files.read(it)?.toString(Charsets.UTF_8) })
    }
}
