package music.ai.recommend.sync

import music.ai.recommend.ai.SharedDecoder

/**
 * The terms on which a device will believe somebody else's embedding.
 *
 * Four things have to agree, and the reason they are checked rather than assumed is that a vector
 * which is wrong in a way nobody notices is worse than a vector that was never sent: the track
 * simply sits in the wrong place in the similarity space, and every answer drawn from it is quietly
 * off. The app treats a cosine of 0.9985 as "the same embedding", while two unrelated tracks sit at
 * about 0.27 — so there is no tolerance to spend. A near miss costs as much as a gross one.
 *
 * What has to agree:
 *
 * **The model.** An int8 export and an fp32 export of the same network produce different vectors.
 * Named by the sha256 of the file, because that is the only name a variant has that cannot be got
 * wrong, and both apps already declare it for the file they were developed against.
 *
 * **The analysis version.** Bumped whenever anything that changes the value of an embedding
 * changes — the feature extractor, the excerpt rule, the projection.
 *
 * **The decoder.** This is the one that decides what may travel at all. mp3, flac, wav and m4a go
 * through `ai/` decoders that are the same code in both apps — see `SharedDecoder` — so both get
 * the same samples by construction. Everything else goes through the platform: MediaCodec on the
 * phone, ffmpeg on the desktop, two different decoders whose agreement is a matter of luck. For a
 * lossy format that luck does not hold at all, and even for a lossless one the window and the
 * resampler differ, which is enough.
 *
 * **The excerpt.** Which ten seconds were listened to. It travels as a number rather than being
 * recomputed and trusted, because the two platforms read a track's duration from different places;
 * `Excerpt.startMs` is built to make them agree, and this is the check that a mismatch is noticed
 * instead of papered over. Twenty-one milliseconds of disagreement costs a cosine of 0.9585.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
data class VectorTerms(
    /** sha256 of the audio model this device analyses with. */
    val model: String,
    val analysisVersion: Int,
    /** How many floats this device's embeddings have; 512 for CLAP. */
    val dimensions: Int
) {

    /**
     * Whether a vector made elsewhere may be used for a local track.
     *
     * @param localDecoder what this device would use on its own copy of the file. Asked of the
     *   caller because only the platform knows the file: the record names a recording, not a path.
     * @param localExcerptStartMs what this device works out for its own copy, from its own reading
     *   of the duration.
     */
    fun judge(record: VectorRecord, localDecoder: String, localExcerptStartMs: Long): VectorVerdict =
        when {
            record.model != model -> VectorVerdict.OtherModel
            record.analysisVersion != analysisVersion -> VectorVerdict.OtherVersion
            record.decoder != localDecoder -> VectorVerdict.OtherDecoder
            record.decoder !in TRAVELS -> VectorVerdict.DecoderDoesNotTravel
            record.excerptStartMs != localExcerptStartMs -> VectorVerdict.OtherExcerpt
            record.vector.size != dimensions -> VectorVerdict.WrongShape
            record.vector.all { it == 0f } -> VectorVerdict.Empty
            else -> VectorVerdict.Usable
        }

    /**
     * These terms as one line, for remembering what a folder was last read on.
     *
     * The decoder is not in it: it belongs to a file rather than to a device, and a vector refused
     * over one is refused for that track alone. These three are the device's own, and all three can
     * change under it — the owner switches to the other export of the model, or a new version of
     * the app changes what an embedding means.
     */
    fun fingerprint(): String = "$model/$analysisVersion/$dimensions"

    companion object {

        /**
         * Decoders whose output is the same on both sides by construction, and therefore the only
         * ones whose vectors are allowed to travel.
         *
         * A sender checks this too, rather than leaving it to the receiver: a vector the other side
         * is certain to refuse is megabytes of somebody's folder spent on nothing. The set grows
         * when a format gets a shared decoder, and growing it is a format change, so it is written
         * here and not derived from whatever each app happens to have.
         */
        val TRAVELS: Set<String> = SharedDecoder.NAMES
    }
}

/** Why a vector was or was not taken. Named so a count of each reads as a diagnosis. */
enum class VectorVerdict {
    Usable,

    /** A different export of the network — int8 against fp32 — so different numbers. */
    OtherModel,

    /** Made before something that changes every embedding changed. */
    OtherVersion,

    /** The two sides would turn this file into samples with different code. */
    OtherDecoder,

    /** Both sides use the same decoder, but it is not one whose agreement is by construction. */
    DecoderDoesNotTravel,

    /** The two sides disagree about which ten seconds the track's middle is. */
    OtherExcerpt,

    /** Not the number of floats this device's model produces. */
    WrongShape,

    /** All zeros: how both apps mark a file they could not decode, which is not an embedding. */
    Empty
}
