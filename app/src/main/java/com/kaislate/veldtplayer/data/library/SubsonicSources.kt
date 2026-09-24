// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.account.AccountRepository
import com.kaislate.veldtplayer.data.account.db.AccountDao
import com.kaislate.veldtplayer.data.account.db.AccountEntity
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.net.ServerCapabilities
import com.kaislate.veldtplayer.data.net.SubsonicCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `associateBy(sourceId)`, minus any row whose id would break the `sourceId:externalId` media id
 * or the `veldt://` uri — the same ban [SourceRegistry]'s constructor enforces on the static
 * side. A malformed row is REFUSED here, not crashed on: it came from SQLite, possibly written by
 * a future migration or an out-of-band edit, not from this app's own minting, and one bad row
 * must make only itself invisible rather than take every other account down with it.
 */
private fun List<AccountEntity>.filterValidIds(): Map<String, AccountEntity> =
    associateBy { it.sourceId }.filterKeys { it.isNotBlank() && ':' !in it && '/' !in it }

/**
 * Every configured server account, as a [RemoteSources] (N2 Task 2 — spec §4.2, §5.2).
 *
 * **The construction-time read is synchronous and blocking, on purpose.**
 * [com.kaislate.veldtplayer.data.playlist.PlaylistRepository.resolve] and
 * [MusicRepository.playableUri] call [byId] without suspending, and a cold app start must not
 * render every remote playlist entry unresolved for the one frame it takes a `Flow` to deliver
 * its first value. The accounts table holds a handful of rows, so one indexed `SELECT` on
 * [Dispatchers.IO] at construction is cheap enough to simply wait for.
 *
 * After construction, [AccountDao.observeAll] keeps [sources] current: a row added later becomes
 * a source, a removed one stops being one. An id that survives an edit keeps its existing
 * [SubsonicSource] instance — only [credentials] and [capabilities] read row content, so an
 * edited baseUrl needs no new [LibrarySource].
 */
@Singleton
class SubsonicSources internal constructor(
    private val accountDao: AccountDao,
    private val songDao: SongDao,
    private val accounts: AccountRepository,
    scope: CoroutineScope,
) : RemoteSources {

    /**
     * The constructor Hilt uses.
     *
     * The four-argument primary constructor takes a bare [CoroutineScope] rather than building
     * one inline so a test can hand it a deterministic `TestScope` (see `SubsonicSourcesTest`)
     * and observe exactly when the account-change collector below has, or has not, run yet.
     * Production gets a process-scoped [Dispatchers.IO] scope with a [SupervisorJob], so a
     * failure collecting account changes cannot take anything else down with it.
     */
    @Inject constructor(accountDao: AccountDao, songDao: SongDao, accounts: AccountRepository) : this(
        accountDao = accountDao,
        songDao = songDao,
        accounts = accounts,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /** The last snapshot [observeAll]'s collector saw. NEVER read by [credentials] — see there. */
    @Volatile private var rows: Map<String, AccountEntity> =
        runBlocking(Dispatchers.IO) { accountDao.getAll() }.filterValidIds()

    /** [credentials]'s decrypt cache, keyed on sourceId. Eviction happens in [init] below. */
    private val credentialCache = ConcurrentHashMap<String, SubsonicCredentials>()

    @Volatile private var sources: Map<String, SubsonicSource> =
        rows.mapValues { (id, _) -> SubsonicSource(id, songDao) }

    init {
        scope.launch {
            accountDao.observeAll()
                // A closed database reached by this collector — the app's own teardown, or (as
                // measured while adding N2 Task 6's tests) a short-lived Robolectric test's
                // `db.close()` racing an already-dispatched Room query on Room's OWN internal
                // executor — must degrade to "stop observing", not crash. `SupervisorJob` keeps a
                // sibling failure from cancelling other users of [scope], but does nothing for an
                // exception thrown BY this coroutine itself, which otherwise propagates as an
                // uncaught exception with no listener anywhere near it. `catch` is transparent to
                // `CancellationException` (kotlinx-coroutines' own guarantee), so real
                // cancellation of [scope] is unaffected.
                .catch { }
                .collect { list ->
                    val next = list.filterValidIds()
                    // Drop a cached credential for any row that changed VALUE or vanished.
                    // AccountEntity is a data class, so this is a genuine content comparison, not a
                    // presence check — a baseUrl or username edit is the whole reason this cache
                    // needs eviction at all.
                    credentialCache.keys.retainAll { k -> k in next && next[k] == rows[k] }
                    rows = next
                    // Reuse the existing SubsonicSource for an id that survives, so a caller holding
                    // a reference across an edit does not silently start comparing unequal instances.
                    sources = next.mapValues { (id, _) -> sources[id] ?: SubsonicSource(id, songDao) }
                }
        }
    }

    override fun byId(sourceId: String): LibrarySource? = sources[sourceId]

    override val all: Collection<LibrarySource> get() = sources.values

    fun contains(sourceId: String): Boolean = sourceId in sources

    /**
     * The credentials [sourceId]'s server calls need, or null when the account or its secret is
     * gone.
     *
     * A cache hit returns without touching disk or the Keystore. A miss reads
     * `accountDao.get(sourceId)` and `accounts.password(sourceId)` FRESH — never [rows], the
     * snapshot [observeAll]'s collector maintains. That distinction is the entire point of this
     * method: Task 3's sync worker is enqueued the instant `AccountRepository.add` writes the
     * row, and it can run before the collector above has had a turn to update [rows] — the two
     * are independent coroutines with no ordering between them. Reading the snapshot here would
     * fail the very first sync of every new account as "no credentials". See
     * `SubsonicSourcesTest`'s `credentials resolve for a row the collector has not seen yet`.
     */
    suspend fun credentials(sourceId: String): SubsonicCredentials? {
        credentialCache[sourceId]?.let { return it }
        val row = accountDao.get(sourceId) ?: return null
        val password = accounts.password(sourceId) ?: return null
        return SubsonicCredentials(row.baseUrl, row.username, password)
            .also { credentialCache[sourceId] = it }
    }

    /**
     * [sourceId]'s cached extension names, or [ServerCapabilities.BASELINE] if none are cached
     * (including when [sourceId] itself is unknown). Names only —
     * `AccountRepository.cacheCapabilities` stores names, not versions, and no N2 check needs a
     * version number.
     */
    suspend fun capabilities(sourceId: String): ServerCapabilities =
        rows[sourceId]?.capabilities
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.associateWith { emptyList<Int>() }
            ?.let(::ServerCapabilities)
            ?: ServerCapabilities.BASELINE
}
