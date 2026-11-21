package music.ai.recommend.ui

import androidx.compose.runtime.Composable
import music.ai.recommend.strings.resources.Res
import music.ai.recommend.strings.resources.allStringResources
import org.jetbrains.compose.resources.stringResource

/**
 * A word from the `:strings` module, by the key the shared code names it with.
 *
 * Shared code holds a key rather than a resource — see [music.ai.recommend.ai.WaveMood] and
 * [music.ai.recommend.ai.AlbumLabel] — and the interface looks it up. [fallback] is what to show
 * when the key names nothing, which happens only if a table in the shared module has grown an
 * entry `:strings` has not.
 *
 * The rest of this port's interface is English literals; these words are not, because they are the
 * same words the phone shows and the phone's are translated.
 */
@Composable
fun byKey(key: String, fallback: String): String =
    Res.allStringResources[key]?.let { stringResource(it) } ?: fallback
