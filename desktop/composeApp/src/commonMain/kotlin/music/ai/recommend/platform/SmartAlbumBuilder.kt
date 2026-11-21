package music.ai.recommend.platform

import music.ai.recommend.ai.SmartAlbum
import music.ai.recommend.model.Song

/**
 * Builds smart albums from the analysed library and keeps the last result on disk.
 *
 * Clustering takes seconds on a large library and naming needs the text model, so neither should
 * run on every launch: the result is cached under a signature of the analysed tracks, and the
 * label text embeddings are cached separately so the vocabulary is only ever encoded once.
 */
expect class SmartAlbumBuilder(textEncoder: ClapTextEncoder) {
    /**
     * @param epsScale the user's multiplier on the automatically chosen eps.
     * @param rebuild ignore the cache even if it matches.
     */
    suspend fun albums(library: List<Song>, epsScale: Float, rebuild: Boolean): List<SmartAlbum>
}
