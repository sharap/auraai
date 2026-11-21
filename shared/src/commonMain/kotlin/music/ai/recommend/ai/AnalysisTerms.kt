package music.ai.recommend.ai

/**
 * What the analysis produces, as a number both devices have to agree on.
 *
 * Bumped whenever anything that changes the value of an embedding changes — the feature extractor,
 * the excerpt selection, the projection. A vector carries this number between devices and is
 * refused if it differs, so disagreement does not give a wrong answer: it gives no answer, quietly,
 * and both libraries go on looking analysed while refusing each other's work.
 *
 * Which is why it lives here and not twice. It used to be `3` in the phone's `EmbeddingStore` and
 * `3` in the desktop's `EmbeddingEntity`, kept equal by a line in the format document asking
 * whoever changed one to remember the other. That was the last pair of numbers in the project that
 * had to agree and had nothing but attention keeping them equal.
 *
 * Version 2 corrected the log-mel features and moved the excerpt to the middle of the track.
 * Version 3 pinned the excerpt to an exact range of samples; see [Excerpt]. Before it, the window
 * was centred on a duration the tag reader gives in whole seconds on one side and on microseconds
 * on the other, so the same track was embedded from ten seconds that began up to half a second
 * apart — a cosine of about 0.95 where 0.9985 is the bar for two embeddings describing the same
 * thing, and the reason carrying them between devices did not work at all.
 */
/**
 * What an embedding was made from, in one number.
 *
 * 4: three ten-second windows at roughly a quarter, the middle and three quarters of the track,
 * averaged — see [Excerpt.startsMs]. 3 was the single window in the middle. A vector from another
 * version is not comparable with ours and is discarded rather than trusted, which is why bumping
 * this costs every device a full re-analysis.
 */
const val ANALYSIS_VERSION = 4

/** How many numbers one embedding has. CLAP's audio tower projects to 512. */
const val EMBEDDING_DIMENSIONS = 512
