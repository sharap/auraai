package music.ai.recommend.ai

/**
 * A mood the wave can be pointed at: a name for the listener and an English prompt for CLAP.
 *
 * The prompts are English because the text encoder was trained on English captions, and they
 * describe how music sounds rather than what genre it belongs to — that is what an audio embedding
 * can match. These are shortcuts to the same machinery as "by description": the mood only decides
 * where the stream starts, and from there it steers on what the listener does.
 *
 * [nameKey] names a string in the `:strings` module, which both interfaces read, exactly as
 * [AlbumLabel] does. The phone used to hold an Android string resource id here, which is what kept
 * this file — and the wave with it — out of the shared module.
 */
data class WaveMood(val nameKey: String, val prompt: String)

object WaveMoods {
    val all = listOf(
        WaveMood("mood_calm", "calm quiet music, soft and relaxing"),
        WaveMood("mood_energetic", "energetic driving music with a strong beat"),
        WaveMood("mood_sad", "sad melancholic music"),
        WaveMood("mood_bright", "bright happy uplifting music"),
        WaveMood("mood_dance", "danceable club music"),
        WaveMood("mood_instrumental", "instrumental music without vocals"),
        WaveMood("mood_focus", "steady background music for concentration"),
        WaveMood("mood_epic", "epic cinematic orchestral music")
    )
}
