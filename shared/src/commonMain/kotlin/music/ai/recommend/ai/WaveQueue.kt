package music.ai.recommend.ai

/**
 * Whether playing [chosen] means the listener is done with the wave.
 *
 * A wave owns the queue, and both interfaces play a track through one function whatever the reason
 * — the player reaching the end of one, the skip button, a tap on a track in the queue, a tap on
 * an album. Only some of those mean "stop steering", and the difference is whether the queue is
 * being left behind:
 *
 * - A list passed in replaces the queue, so it is a choice: an album, a playlist, a search result.
 * - A track that is not in the queue is a choice too, wherever it came from.
 * - Anything inside the queue is the wave working. The player advancing is obvious; a skip is the
 *   less obvious one, and the important one, because a skip is how a wave is told it went wrong.
 *   A wave that stopped when skipped would be the opposite of the feature.
 *
 * Spelled out here, with tests, because getting it wrong does not look like getting a rule wrong:
 * the desktop's first wave called this "not from the wave, so stop", which every automatic advance
 * satisfies. The stream died on the first track change and left the handful of tracks the start had
 * queued — which reads as "the wave only makes a short playlist", not as "the stop rule is wrong".
 *
 * The phone had the same bug before the desktop did, and answered it in its own shape rather than
 * with this function: there, playing a track always replaces the queue with the list it is given,
 * so "not from the wave" really is always a choice, and tapping ahead in the queue goes through a
 * separate `playFromQueue` that moves the player instead of rebuilding the playlist. Its note says
 * what happened before that existed: "counted as picking by hand and silently ended the stream".
 * Twice in two ports, from two directions, which is what this doc comment is for.
 *
 * @param replacingQueue a list was passed in to become the new queue.
 */
fun <Track> choosingOverTheWave(chosen: Track, queue: List<Track>, replacingQueue: Boolean): Boolean =
    replacingQueue || chosen !in queue
