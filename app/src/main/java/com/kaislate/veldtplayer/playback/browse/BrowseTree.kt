// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.browse

import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import com.kaislate.veldtplayer.data.art.SongArt
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.DisplayNames
import com.kaislate.veldtplayer.data.library.LibraryDerivations
import com.kaislate.veldtplayer.data.library.LibraryKeys
import com.kaislate.veldtplayer.data.library.displayAlbum
import com.kaislate.veldtplayer.data.library.displayArtist
import com.kaislate.veldtplayer.data.library.displayTitle
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.SessionMediaId
import com.kaislate.veldtplayer.playback.browse.BrowseIds.Container
import com.kaislate.veldtplayer.playback.queue.SavedItem
import com.kaislate.veldtplayer.playback.queue.SavedQueue

/** A playlist as the tree lists it. */
data class PlaylistRef(val id: Long, val name: String)

/**
 * What the tree reads, and all it reads. The production implementation is
 * [LibraryBrowseCatalog]; tests hand the tree a fixed one.
 */
interface BrowseCatalog {
    /** The library as the app's own screens show it — hidden folders already left out. */
    suspend fun songs(): List<Song>

    suspend fun playlists(): List<PlaylistRef>

    /** A playlist's RESOLVED tracks, in playlist order; an unresolved entry has nothing to play. */
    suspend fun playlistSongs(id: Long): List<Song>

    /** The newest saved queue (spec §3), or null when there is none. */
    suspend fun recent(): SavedQueue?
}

/**
 * One browse item, framework-free. The service maps it to a Media3 `MediaItem`; everything that
 * decides WHAT the tree contains stays here, testable without a session.
 *
 * [mediaType] is a `MediaMetadata.MEDIA_TYPE_*` constant — compile-time ints, so using them does
 * not make this file need Android.
 */
data class BrowseNode(
    val id: String,
    val title: String,
    val subtitle: String?,
    val browsable: Boolean,
    val playable: Boolean,
    val mediaType: Int,
    val art: SongArt? = null,
    val durationMs: Long? = null,
)

/** One entry of a queue the tree resolved for playback. */
sealed interface PlayEntry {
    /** A library track; the service builds its item with `sessionMediaItem`. */
    data class Library(val song: Song) : PlayEntry

    /** A saved-queue track (spec §3), rebuilt from what was saved. */
    data class Saved(val item: SavedItem) : PlayEntry

    /** The controller's own item, already playable — kept exactly as sent. */
    data class Passthrough(val requestIndex: Int) : PlayEntry
}

/** A queue to hand the player, and where in it to start. */
data class PlayPlan(val entries: List<PlayEntry>, val startIndex: Int, val startPositionMs: Long)

/**
 * One item a controller asked to play or add: [mediaId] as sent, the voice [searchQuery] from its
 * request metadata, and whether it already carries a playable uri.
 */
data class PlayRequest(val mediaId: String, val searchQuery: String? = null, val hasUri: Boolean = false)

/**
 * The Android Auto / Assistant browse tree (spec §6), pure.
 *
 * ```
 * root ─ Recent      the saved queue (spec §3)
 *      ├ Playlists ─ playlist/<id> ─ tracks
 *      ├ Albums    ─ album/<key>   ─ tracks, disc/track order
 *      ├ Artists   ─ artist/<key>  ─ tracks, by album
 *      └ Songs     ─ every track, A–Z
 * ```
 *
 * Every list is paged by the caller's `page`/`pageSize`, so a large library's "Songs" is served a
 * page at a time rather than as one list a head unit has to swallow whole.
 *
 * The library is read through [BrowseCatalog.songs], i.e. the same hidden-folder filter every
 * screen of the app uses: a folder the user hid does not come back in the car.
 */
class BrowseTree(private val catalog: BrowseCatalog) {

    // ---------- structure ----------

    fun root(): BrowseNode = folder(BrowseIds.ROOT, "Veldt", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /**
     * The root for a controller asking for RECENT content, or null when nothing is saved. Its one
     * child is the item playback would resume with — see [children] for [BrowseIds.RECENT_ROOT].
     * (System UI's own resume-card query never reaches this: Media3 answers it itself from
     * `onPlaybackResumption`.)
     */
    suspend fun recentRoot(): BrowseNode? {
        catalog.recent() ?: return null
        return folder(BrowseIds.RECENT_ROOT, "Recent", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
    }

    /**
     * [parentId]'s children, one page of them; null for an id that is not a browsable node.
     *
     * [rootLimit] is the most root children the controller can show (Android Auto sends it as
     * `MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT` — root children become its tabs). When the
     * five do not fit, Recent is the one left out: a head unit that limits tabs is one that
     * surfaces "recent" itself, through the recent root.
     */
    suspend fun children(parentId: String, page: Int, pageSize: Int, rootLimit: Int? = null): List<BrowseNode>? {
        val all: List<BrowseNode> = when (parentId) {
            BrowseIds.ROOT -> rootChildren(rootLimit)
            BrowseIds.RECENT_ROOT -> {
                val recent = catalog.recent()
                val current = recent?.current
                if (recent == null || current == null) emptyList()
                else listOf(savedLeaf(recent.index, current))
            }
            BrowseIds.PLAYLISTS -> catalog.playlists().map(::playlistNode)
            BrowseIds.ALBUMS -> albumNodes()
            BrowseIds.ARTISTS -> artistNodes()
            else -> {
                val container = BrowseIds.parseContainer(parentId) ?: return null
                leavesOf(container) ?: return null
            }
        }
        return all.page(page, pageSize)
    }

    /** The node [id] names, or null when it names nothing that exists (any more). */
    suspend fun item(id: String): BrowseNode? {
        when (id) {
            BrowseIds.ROOT -> return root()
            BrowseIds.RECENT_ROOT -> return recentRoot()
            BrowseIds.RECENT, BrowseIds.PLAYLISTS, BrowseIds.ALBUMS, BrowseIds.ARTISTS, BrowseIds.SONGS ->
                return rootChildren(null).firstOrNull { it.id == id }
        }
        BrowseIds.parseLeaf(id)?.let { leaf ->
            val container = BrowseIds.parseContainer(leaf.context) ?: return null
            val entries = entriesOf(container) ?: return null
            val at = locate(entries, leaf.index, leaf.mediaId) ?: return null
            return leafNode(container, at, entries[at])
        }
        return when (val container = BrowseIds.parseContainer(id)) {
            is Container.Album -> albumNodes().firstOrNull { it.id == id }
            is Container.Artist -> artistNodes().firstOrNull { it.id == id }
            is Container.Playlist -> catalog.playlists().firstOrNull { it.id == container.id }?.let(::playlistNode)
            else -> null
        }
    }

    /** Search results (spec §6, `onSearch`/`onGetSearchResult`), as playable leaves. */
    suspend fun search(query: String, page: Int, pageSize: Int): List<BrowseNode> =
        leavesOf(Container.Search(query)).orEmpty().page(page, pageSize)

    /** How many results [search] has in all, for `notifySearchResultChanged`. */
    suspend fun searchCount(query: String): Int = searchSongs(query).size

    // ---------- playback resolution ----------

    /**
     * What `onSetMediaItems` should put on the player for [requests] (spec §6), or null when none
     * of them resolves to anything playable.
     *
     * A single request is played IN CONTEXT: a browsed leaf becomes its whole list starting at it,
     * a container becomes its list from the top, a voice [PlayRequest.searchQuery] becomes
     * [voice]'s answer, and a bare session mediaId becomes that one track. Several requests are
     * resolved one for one ([resolveAdd]) and keep the caller's start index, moved past anything
     * that did not resolve.
     *
     * [startPositionMs] is `C.TIME_UNSET` when the controller did not choose one, and a context the
     * tree chooses starts from 0 — except the recent item, which resumes where it was saved.
     */
    suspend fun resolvePlay(requests: List<PlayRequest>, startIndex: Int, startPositionMs: Long): PlayPlan? {
        val single = requests.singleOrNull()
        if (single != null && !single.hasUri) {
            val query = single.searchQuery
            if (single.mediaId.isEmpty() && query != null) return voice(query)
            BrowseIds.parseLeaf(single.mediaId)?.let { leaf ->
                playLeaf(leaf, startPositionMs)?.let { return it }
            }
            BrowseIds.parseContainer(single.mediaId)?.let { container ->
                val entries = entriesOf(container)
                if (!entries.isNullOrEmpty()) return PlayPlan(entries, 0, 0L)
            }
        }
        val perRequest = requests.mapIndexed { i, request -> resolveOne(request, i) }
        val entries = perRequest.flatten()
        if (entries.isEmpty()) return null
        val start = startIndex.coerceIn(0, requests.lastIndex)
        // Everything that resolved from requests before the chosen one comes first; the chosen
        // request's first entry — or, if it resolved to nothing, the next one that did — starts.
        val startEntry = perRequest.take(start).sumOf { it.size }.coerceAtMost(entries.lastIndex)
        val position = if (perRequest[start].isEmpty() || startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        return PlayPlan(entries, startEntry, position)
    }

    /**
     * What `onAddMediaItems` should add for [requests]: each one resolved on its own, in order, and
     * dropped when it resolves to nothing. No context expansion for a leaf — adding one track to a
     * queue must not add its whole album — but a container or a search adds everything it holds.
     */
    suspend fun resolveAdd(requests: List<PlayRequest>): List<PlayEntry> =
        requests.flatMapIndexed { i, request -> resolveOne(request, i) }

    /**
     * A voice request (spec §6): Assistant's `searchQuery`, in the order a listener means it.
     *
     * 1. Blank ("play music on Veldt"): the saved queue where it stopped, else every song.
     * 2. Exactly an album's, an artist's or a playlist's name: that album in track order, that
     *    artist's songs, that playlist.
     * 3. Otherwise the search results — title matches first — starting with an exact title match
     *    when there is one.
     */
    suspend fun voice(query: String): PlayPlan? {
        val q = LibraryKeys.normalize(query)
        if (q.isEmpty()) {
            val recent = catalog.recent()
            if (recent != null && recent.items.isNotEmpty()) {
                return PlayPlan(recent.items.map(PlayEntry::Saved), recent.index, recent.positionMs)
            }
            val all = sortedSongs()
            return if (all.isEmpty()) null else PlayPlan(all.map(PlayEntry::Library), 0, 0L)
        }
        val songs = catalog.songs()
        songs.filter { LibraryKeys.normalize(it.displayAlbum()) == q }.takeIf { it.isNotEmpty() }?.let { hits ->
            val key = LibraryKeys.albumKey(hits.first())
            val album = LibraryDerivations.sortAlbumTracks(songs.filter { LibraryKeys.albumKey(it) == key })
            return PlayPlan(album.map(PlayEntry::Library), 0, 0L)
        }
        songs.filter { LibraryKeys.artistKey(it) == q }.takeIf { it.isNotEmpty() }?.let { hits ->
            return PlayPlan(LibraryDerivations.sortArtistTracks(hits).map(PlayEntry::Library), 0, 0L)
        }
        catalog.playlists().firstOrNull { LibraryKeys.normalize(it.name) == q }?.let { playlist ->
            val tracks = catalog.playlistSongs(playlist.id)
            if (tracks.isNotEmpty()) return PlayPlan(tracks.map(PlayEntry::Library), 0, 0L)
        }
        val results = searchSongs(query)
        if (results.isEmpty()) return null
        val exact = results.indexOfFirst { LibraryKeys.normalize(it.displayTitle()) == q }.coerceAtLeast(0)
        return PlayPlan(results.map(PlayEntry::Library), exact, 0L)
    }

    // ---------- internals ----------

    private fun rootChildren(limit: Int?): List<BrowseNode> {
        val all = listOf(
            folder(BrowseIds.RECENT, "Recent", MediaMetadata.MEDIA_TYPE_PLAYLIST),
            folder(BrowseIds.PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            folder(BrowseIds.ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            folder(BrowseIds.ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
            folder(BrowseIds.SONGS, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        )
        if (limit == null || limit >= all.size) return all
        return all.filterNot { it.id == BrowseIds.RECENT }.take(limit.coerceAtLeast(0))
    }

    private suspend fun albumNodes(): List<BrowseNode> {
        val songs = catalog.songs()
        val byKey = songs.groupBy(LibraryKeys::albumKey)
        return LibraryDerivations.deriveAlbums(songs).map { album ->
            val first = LibraryDerivations.sortAlbumTracks(byKey[album.key].orEmpty()).firstOrNull()
            BrowseNode(
                id = BrowseIds.album(album.key),
                title = DisplayNames.album(album.name),
                subtitle = first?.let { DisplayNames.albumArtist(album.albumArtist, it.artist) },
                browsable = true,
                playable = false,
                mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
                art = first?.toSongArt(),
            )
        }
    }

    private suspend fun artistNodes(): List<BrowseNode> {
        val songs = catalog.songs()
        val firstByKey = songs.groupBy(LibraryKeys::artistKey)
            .mapValues { (_, rows) -> LibraryDerivations.sortArtistTracks(rows).first() }
        return LibraryDerivations.deriveArtists(songs).map { artist ->
            BrowseNode(
                id = BrowseIds.artist(artist.key),
                title = DisplayNames.artist(artist.name),
                subtitle = countOf(artist.songCount, "song"),
                browsable = true,
                playable = false,
                mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
                art = firstByKey[artist.key]?.toSongArt(),
            )
        }
    }

    private fun playlistNode(p: PlaylistRef) = BrowseNode(
        id = BrowseIds.playlist(p.id),
        title = p.name,
        subtitle = null,
        browsable = true,
        playable = false,
        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
    )

    /** A container's leaves, null when the container no longer exists. */
    private suspend fun leavesOf(container: Container): List<BrowseNode>? =
        entriesOf(container)?.mapIndexed { i, entry -> leafNode(container, i, entry) }

    /**
     * A container's playable list, in play order; null when the container does not exist (an album
     * or artist key nothing in the library has any more, a deleted playlist).
     */
    private suspend fun entriesOf(container: Container): List<PlayEntry>? = when (container) {
        Container.Recent -> catalog.recent()?.items?.map(PlayEntry::Saved).orEmpty()
        Container.Songs -> sortedSongs().map(PlayEntry::Library)
        is Container.Album -> catalog.songs().filter { LibraryKeys.albumKey(it) == container.key }
            .takeIf { it.isNotEmpty() }
            ?.let(LibraryDerivations::sortAlbumTracks)
            ?.map(PlayEntry::Library)
        is Container.Artist -> catalog.songs().filter { LibraryKeys.artistKey(it) == container.key }
            .takeIf { it.isNotEmpty() }
            ?.let(LibraryDerivations::sortArtistTracks)
            ?.map(PlayEntry::Library)
        is Container.Playlist -> if (catalog.playlists().none { it.id == container.id }) null
            else catalog.playlistSongs(container.id).map(PlayEntry::Library)
        is Container.Search -> searchSongs(container.query).map(PlayEntry::Library)
    }

    private fun containerId(container: Container): String = when (container) {
        Container.Recent -> BrowseIds.RECENT
        Container.Songs -> BrowseIds.SONGS
        is Container.Album -> BrowseIds.album(container.key)
        is Container.Artist -> BrowseIds.artist(container.key)
        is Container.Playlist -> BrowseIds.playlist(container.id)
        is Container.Search -> BrowseIds.search(container.query)
    }

    private fun leafNode(container: Container, index: Int, entry: PlayEntry): BrowseNode = when (entry) {
        is PlayEntry.Library -> BrowseNode(
            id = BrowseIds.leaf(containerId(container), index, SessionMediaId.of(entry.song)),
            title = entry.song.displayTitle(),
            subtitle = entry.song.displayArtist(),
            browsable = false,
            playable = true,
            mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
            art = entry.song.toSongArt(),
            durationMs = entry.song.durationMs,
        )
        is PlayEntry.Saved -> savedLeaf(index, entry.item)
        is PlayEntry.Passthrough -> error("a browse list never holds a passthrough entry")
    }

    private fun savedLeaf(index: Int, item: SavedItem) = BrowseNode(
        id = BrowseIds.leaf(BrowseIds.RECENT, index, item.mediaId),
        title = DisplayNames.title(item.title),
        subtitle = DisplayNames.artist(item.artist),
        browsable = false,
        playable = true,
        mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
        art = item.art,
        durationMs = item.durationMs,
    )

    /** A leaf played in its context, or null when neither its list nor its track exists any more. */
    private suspend fun playLeaf(leaf: BrowseIds.Leaf, startPositionMs: Long): PlayPlan? {
        val container = BrowseIds.parseContainer(leaf.context) ?: return null
        val entries = entriesOf(container) ?: return null
        val at = locate(entries, leaf.index, leaf.mediaId) ?: return null
        val position = when {
            container == Container.Recent -> catalog.recent()
                ?.takeIf { it.index == at }?.positionMs ?: 0L
            startPositionMs == C.TIME_UNSET -> 0L
            else -> startPositionMs
        }
        return PlayPlan(entries, at, position)
    }

    /**
     * Where [mediaId] is in [entries]: at the [hint] index when it is still there (so a track
     * queued twice plays the copy that was tapped), else its first occurrence; null if absent.
     */
    private fun locate(entries: List<PlayEntry>, hint: Int, mediaId: String): Int? {
        if (entries.getOrNull(hint)?.let(::mediaIdOf) == mediaId) return hint
        return entries.indexOfFirst { mediaIdOf(it) == mediaId }.takeIf { it >= 0 }
    }

    private fun mediaIdOf(entry: PlayEntry): String? = when (entry) {
        is PlayEntry.Library -> SessionMediaId.of(entry.song)
        is PlayEntry.Saved -> entry.item.mediaId
        is PlayEntry.Passthrough -> null
    }

    /** One request resolved on its own — see [resolveAdd]. */
    private suspend fun resolveOne(request: PlayRequest, index: Int): List<PlayEntry> {
        if (request.hasUri) return listOf(PlayEntry.Passthrough(index))
        val query = request.searchQuery
        if (request.mediaId.isEmpty() && query != null) return voice(query)?.entries.orEmpty()
        BrowseIds.parseLeaf(request.mediaId)?.let { leaf ->
            val container = BrowseIds.parseContainer(leaf.context)
            val entries = container?.let { entriesOf(it) }
            val at = entries?.let { locate(it, leaf.index, leaf.mediaId) }
            if (entries != null && at != null) return listOf(entries[at])
            return bareSong(leaf.mediaId)
        }
        BrowseIds.parseContainer(request.mediaId)?.let { return entriesOf(it).orEmpty() }
        return bareSong(request.mediaId)
    }

    /** A bare session mediaId (`sourceId:externalId`) as that one library track, if it exists. */
    private suspend fun bareSong(mediaId: String): List<PlayEntry> {
        val ref = SessionMediaId.parse(mediaId) ?: return emptyList()
        val song = catalog.songs().firstOrNull { it.sourceId == ref.sourceId && it.externalId == ref.externalId }
        return listOfNotNull(song?.let(PlayEntry::Library))
    }

    private suspend fun sortedSongs(): List<Song> =
        catalog.songs().sortedWith(compareBy({ it.displayTitle().lowercase() }, { it.displayArtist().lowercase() }))

    /**
     * Songs whose title, artist or album contains [query], case-folded: title matches first, then
     * artist, then album, A–Z within each. The same fields the app's own search matches.
     */
    private suspend fun searchSongs(query: String): List<Song> {
        val q = LibraryKeys.normalize(query)
        if (q.isEmpty()) return emptyList()
        fun rank(song: Song): Int? = when {
            LibraryKeys.normalize(song.displayTitle()).contains(q) -> 0
            LibraryKeys.normalize(song.displayArtist()).contains(q) -> 1
            LibraryKeys.normalize(song.displayAlbum()).contains(q) -> 2
            else -> null
        }
        return catalog.songs()
            .mapNotNull { song -> rank(song)?.let { it to song } }
            .sortedWith(compareBy({ it.first }, { it.second.displayTitle().lowercase() }))
            .map { it.second }
    }

    private fun folder(id: String, title: String, mediaType: Int) = BrowseNode(
        id = id,
        title = title,
        subtitle = null,
        browsable = true,
        playable = false,
        mediaType = mediaType,
    )

    private fun countOf(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    companion object {
        /** One page of [this]; empty past the end. Long arithmetic, because a controller asking
         *  for page 3 of `Int.MAX_VALUE` must not overflow into page 0. */
        fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
            if (page < 0 || pageSize < 1) return emptyList()
            val from = page.toLong() * pageSize
            if (from >= size) return emptyList()
            val to = minOf(size.toLong(), from + pageSize).toInt()
            return subList(from.toInt(), to).toList()
        }
    }
}
