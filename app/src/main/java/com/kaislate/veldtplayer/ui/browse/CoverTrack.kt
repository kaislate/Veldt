package com.kaislate.veldtplayer.ui.browse

import com.kaislate.veldtplayer.data.library.model.Song

/**
 * The track that lends its artwork to a group of songs — an album tile, an artist portrait,
 * a detail header. Neither an album nor an artist has art of its own.
 *
 * ORDER-INDEPENDENT, and that is the whole point. The same album reaches the grid inside a
 * title-ordered library and the detail screen in disc/track order, so a `first()`-shaped
 * rule picks a DIFFERENT track on each side. Art is cached per song id (`AlbumArtKeyer`),
 * so the two ends of the shared-element morph would then hold two different cache entries:
 * the destination shows its loading wash for a frame and the morph reads as a swap rather
 * than as one object moving.
 *
 * It also makes an artist's portrait STABLE. Under the old first-with-embedded-art rule,
 * importing one album could silently re-cover an unrelated artist, because "first" was
 * first-in-the-whole-catalogue.
 *
 * Embedded art wins because it gives `AlbumArtFetcher` a second source when MediaStore has
 * no thumbnail for the track; the lowest song id breaks the tie because a MediaStore `_ID`
 * is stable and does not move when the library is re-sorted.
 *
 * **Order-independent is not set-independent.** Both ends of a morph must call this over
 * the SAME songs, not merely over the same songs in a different order. An artist page holds
 * only that artist's share of a compilation, so it asks this over the album's full track
 * list rather than over its own rows — see `ArtistDetailScreen.coverByAlbum`.
 */
internal fun List<Song>.coverTrack(): Song? =
    minWithOrNull(compareBy({ !it.hasEmbeddedArt }, { it.id }))
