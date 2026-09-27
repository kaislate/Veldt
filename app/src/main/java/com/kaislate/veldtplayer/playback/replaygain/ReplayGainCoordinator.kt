// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.replaygain

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.kaislate.veldtplayer.data.library.LibraryKeys
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.replaygain.ReplayGainMode
import com.kaislate.veldtplayer.data.replaygain.ReplayGainValues
import com.kaislate.veldtplayer.playback.SessionMediaId
import com.kaislate.veldtplayer.playback.TrackRef
import com.kaislate.veldtplayer.playback.audio.GainStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The ReplayGain settings, as one value. */
data class ReplayGainSettings(val mode: ReplayGainMode, val preampDb: Int)

/**
 * Which items need a gain right now, and who their queue neighbours are (for Auto's album test).
 * Pure: built from the queue's mediaIds and two indices, so it is asserted on the JVM.
 */
internal object ReplayGainPlan {

    data class Target(val mediaId: String, val previousId: String?, val nextId: String?)

    /**
     * The current item and the one that plays after it — the LOOKAHEAD (spec §5), so the next
     * item's gain is in [GainStage] before its first sample is. Neighbours are by queue position:
     * Auto only ever picks album gain with shuffle off, when queue order IS play order.
     */
    fun targets(mediaIds: List<String>, currentIndex: Int, nextIndex: Int): List<Target> =
        listOf(currentIndex, nextIndex)
            .filter { it in mediaIds.indices }
            .distinct()
            .map { i -> Target(mediaIds[i], mediaIds.getOrNull(i - 1), mediaIds.getOrNull(i + 1)) }
}

/**
 * Keeps [stage]'s per-item ReplayGain factors right for the playing item and the next one
 * (0.9.2 spec §5): values resolved at play time through [valuesFor], the mode decision and the
 * arithmetic from [ReplayGainMath]. It never touches audio itself — [GainStage] applies what this
 * decides, at the right sample (see its KDoc).
 *
 * Re-plans on what can change an answer: an item transition (a new "next"), a queue edit, shuffle
 * (Auto's album test), and a settings change. A new plan cancels one still resolving.
 *
 * Main thread; resolution runs in [scope] with its I/O on `Dispatchers.IO`.
 */
class ReplayGainCoordinator(
    private val player: Player,
    private val scope: CoroutineScope,
    private val lookupSongs: suspend (Collection<TrackRef>) -> Map<TrackRef, Song>,
    private val valuesFor: suspend (Song) -> ReplayGainValues?,
    private val settings: Flow<ReplayGainSettings>,
    private val stage: GainStage,
) : Player.Listener {

    private var current: ReplayGainSettings? = null
    private var job: Job? = null

    /** mediaId → its row, so a transition does not re-read the song table for known items. */
    private val songs = object : LinkedHashMap<String, Song?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song?>?) = size > SONG_CACHE
    }

    fun start() {
        player.addListener(this)
        scope.launch {
            settings.collect {
                current = it
                refresh()
            }
        }
    }

    fun release() {
        player.removeListener(this)
        job?.cancel()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refresh()

    /** Queue edits only; a SOURCE_UPDATE (a duration becoming known) changes no answer here. */
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) refresh()
    }

    private fun refresh() {
        val settings = current ?: return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: -1
        val targets = ReplayGainPlan.targets(ids, player.currentMediaItemIndex, next)
        val shuffle = player.shuffleModeEnabled
        job?.cancel()
        job = scope.launch {
            val wanted = targets.flatMap { listOfNotNull(it.mediaId, it.previousId, it.nextId) }.distinct()
            resolveSongs(wanted)
            for (t in targets) {
                val song = songs[t.mediaId]
                val values = song?.let { valuesFor(it) }
                val album = ReplayGainMath.useAlbumGain(
                    settings.mode, shuffle, song?.let(LibraryKeys::albumKey),
                    t.previousId?.let { songs[it] }?.let(LibraryKeys::albumKey),
                    t.nextId?.let { songs[it] }?.let(LibraryKeys::albumKey),
                )
                val linear = ReplayGainMath.linearGain(values, settings.mode, album, settings.preampDb.toFloat())
                stage.setItemGain(t.mediaId, linear)
                Log.i(
                    GainStage.LOG_TAG,
                    "resolved: item=${t.mediaId} mode=${settings.mode} album=$album preamp=${settings.preampDb} " +
                        "values=$values linear=$linear",
                )
            }
            stage.retainItemGains(targets.map { it.mediaId }.toSet())
        }
    }

    /** Fills [songs] for every id in [ids] not already known; a failed read leaves them unknown. */
    private suspend fun resolveSongs(ids: List<String>) {
        val missing = ids.filterNot(songs::containsKey)
        if (missing.isEmpty()) return
        val refs = missing.mapNotNull { id -> SessionMediaId.parse(id)?.let { id to it } }
        val found = withContext(Dispatchers.IO) {
            runCatching { lookupSongs(refs.map { it.second }) }.getOrNull()
        } ?: return
        for ((id, ref) in refs) songs[id] = found[ref]
    }

    private companion object {
        const val SONG_CACHE = 256
    }
}
