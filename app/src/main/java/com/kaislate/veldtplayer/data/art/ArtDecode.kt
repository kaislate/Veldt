package com.kaislate.veldtplayer.data.art

import coil.request.ImageRequest
import coil.request.Options

/**
 * How small to decode a piece of artwork, as a linear divisor: [FULL] means the source's
 * natural size, 8 asks for a bitmap an eighth as wide and tall.
 *
 * **Why a decode hint and not just a smaller draw.** The now-playing backdrop needs the
 * artwork *low-passed* — detail gone, colour kept. Below API 31 there is no `RenderEffect`
 * to do that with, and dimming or magnifying the full-size bitmap cannot substitute:
 * both attenuate fine detail and broad colour by the same factor, so no setting of them
 * hides cover typography while keeping the cover recognisable. Decoding small and
 * magnifying the result IS a real low-pass, and it is the only one available there.
 *
 * **Why it forks the cache key.** [AlbumArtKeyer] deliberately keys on the song id alone so
 * every surface shares ONE decoded bitmap — that shared instance is what makes the
 * shared-element art morph look continuous. A sampled decode must therefore be a SEPARATE
 * cache entry, or the backdrop's small bitmap could be handed to the full-screen art and
 * the morph would land on a blurry cover. The key is left byte-identical at [FULL], so
 * every existing caller keeps exactly the behaviour and exactly the cache entry it has now.
 */
object ArtDecode {

    /** The source's natural size — what every surface except the backdrop wants. */
    const val FULL = 1

    /** Coil request-parameter name carrying the divisor. */
    internal const val PARAMETER = "veldt.art.sample"
}

/**
 * Ask for a decode [sample] times smaller in each dimension. Coil folds the parameter into
 * the memory-cache key on its own; [AlbumArtKeyer] folds it into the base key as well, so
 * the separation does not depend on Coil's internal handling of parameter extras.
 */
fun ImageRequest.Builder.artDecodeSample(sample: Int): ImageRequest.Builder =
    setParameter(ArtDecode.PARAMETER, sample)

/** The divisor this request asked for, or [ArtDecode.FULL] when it asked for nothing. */
internal fun Options.artDecodeSample(): Int =
    (parameters.value(ArtDecode.PARAMETER) as? Int)?.coerceAtLeast(ArtDecode.FULL)
        ?: ArtDecode.FULL
