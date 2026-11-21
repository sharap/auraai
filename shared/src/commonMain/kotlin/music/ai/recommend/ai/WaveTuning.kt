package music.ai.recommend.ai

/** Where a wave takes its initial direction from. */
enum class WaveMode {
    /** The taste profile: what has been played recently, skips against and favourites for. */
    TASTE,

    /** The track playing when the wave was asked for. */
    SIMILAR,

    /** A description, through CLAP's text tower — including the moods, which are prompts. */
    DESCRIPTION
}

/**
 * How a wave behaves, as opposed to how it decides — the numbers around [Wave] rather than in it.
 *
 * Shared because the two interfaces should not differ in it. Both ports held their own copies of
 * numbers like these before the merge and one of them had drifted: the desktop grouped tracks for
 * the daily mix by artist tag alone while the phone used [TrackGroups], so "don't play the same act
 * twice in a row" meant two different things. Nothing fails when a number like that disagrees; the
 * two just quietly behave differently, and whichever one the listener is holding is the one they
 * judge.
 *
 * The values are the phone's, unchanged, because they were arrived at by listening — see the
 * tuning notes in the README. A desktop that started from different ones would be a second
 * experiment running under the same name.
 */
object WaveTuning {
    /** Tracks kept queued behind the one playing while a wave runs. */
    const val QUEUE_AHEAD = 4

    /** Below this much of a track, the wave treats it as a rejection and rebuilds its queue. */
    const val SKIP_FRACTION = 0.3f

    /** Fewer analysed tracks than this and a wave would circle immediately. */
    const val MIN_LIBRARY = 40

    /**
     * How long a saved wave is worth resuming.
     *
     * A wave is about the moment. Coming back to the app the same evening should carry on where it
     * stopped; coming back two days later should not resurrect a direction that was steered by a
     * mood nobody remembers.
     */
    const val MAX_AGE_MS = 12L * 60 * 60 * 1000
}
