package music.ai.recommend.ai

/**
 * What the scan still has to look at, once refusals are taken into account.
 *
 * A file that will not decode cannot simply be tried again on every scan: a library holds a few
 * such files for ever — a download that stopped at its ID3 tag, a playlist saved under the name
 * `.mp3` — and retrying them would cost a decode attempt each, every pass, with the same answer.
 * So a refusal is remembered. The whole question this object answers is what the memory of a
 * refusal is *about*, because remembering it for ever is just as wrong: the owner may finish that
 * download tomorrow, and the file would stay unanalysed with nothing to say why.
 *
 * The answer is that a refusal is remembered about the file as it was, named by its length and the
 * time it was last written. The same file still looking the same means the answer has not changed
 * and there is nothing to gain by asking again. A different length or a later write means this is
 * no longer the file that was refused, and it goes back into the backlog.
 *
 * The stamp never leaves the device. A refusal is one device's verdict, reached with one device's
 * decoders — the other may well read the file — and the two platforms do not even agree on how to
 * name a track, let alone when its file was written.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object AnalysisBacklog {

    /**
     * How a file is named for the purpose of remembering that it could not be read.
     *
     * Length and modification time rather than a hash: both are a stat call, while a hash is a
     * read of the whole file, and the one thing this has to catch — a broken file replaced by a
     * whole one — changes both.
     *
     * @return the stamp, or an empty string for a file whose length cannot be read at all. Empty
     *   is not a stamp and never equals one, so a file that is missing while the refusal stands is
     *   left alone rather than retried on every pass.
     */
    fun stamp(sizeBytes: Long, modifiedAt: Long): String =
        if (sizeBytes <= 0L) "" else "$sizeBytes:$modifiedAt"

    /**
     * Which tracks the scan should take, in the order they were given.
     *
     * @param tracks every track in the library, by whatever this platform calls one — a MediaStore
     *   id on the phone, a path on the desktop.
     * @param analysed tracks that already hold a vector.
     * @param refusals tracks that were tried and could not be read, each with the stamp of the
     *   file it was refused for; see [stamp].
     * @param stampOf the file's stamp now. Asked only about refused tracks, so a library of
     *   thousands costs as many stat calls as it has refusals — in practice a handful.
     */
    fun <K> pending(
        tracks: List<K>,
        analysed: Set<K>,
        refusals: Map<K, String>,
        stampOf: (K) -> String
    ): List<K> = tracks.filter { track ->
        val refused = refusals[track]
        if (refused == null) {
            track !in analysed
        } else {
            // A refusal recorded before stamps existed carries an empty one, which no present file
            // can equal: those get exactly one more chance, and then a stamp of their own.
            val now = stampOf(track)
            now.isNotEmpty() && now != refused
        }
    }
}
