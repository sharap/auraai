package music.ai.recommend.ai

import androidx.annotation.StringRes
import music.ai.recommend.R

/**
 * A mood the wave can be pointed at: a name for the listener and an English prompt for CLAP.
 *
 * The prompts are English because the text encoder was trained on English captions, and they
 * describe how music sounds rather than what genre it belongs to — that is what an audio embedding
 * can match. These are shortcuts to the same machinery as "by description": the mood only decides
 * where the stream starts, and from there it steers on what the listener does.
 */
data class WaveMood(@StringRes val title: Int, val prompt: String)

object WaveMoods {
    val all = listOf(
        WaveMood(R.string.mood_calm, "calm quiet music, soft and relaxing"),
        WaveMood(R.string.mood_energetic, "energetic driving music with a strong beat"),
        WaveMood(R.string.mood_sad, "sad melancholic music"),
        WaveMood(R.string.mood_bright, "bright happy uplifting music"),
        WaveMood(R.string.mood_dance, "danceable club music"),
        WaveMood(R.string.mood_instrumental, "instrumental music without vocals"),
        WaveMood(R.string.mood_focus, "steady background music for concentration"),
        WaveMood(R.string.mood_epic, "epic cinematic orchestral music")
    )
}
