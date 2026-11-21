package music.ai.recommend.ui

import androidx.compose.runtime.Composable
import music.ai.recommend.strings.resources.Res
import music.ai.recommend.strings.resources.allStringResources
import org.jetbrains.compose.resources.stringResource

/**
 * A word from the `:strings` module, by the key the shared code names it with.
 *
 * Shared code cannot hold an Android string resource id, so what it holds is a key — see
 * [music.ai.recommend.ai.WaveMood] and [music.ai.recommend.ai.AlbumLabel] — and the interface
 * looks it up. [fallback] is what to show if the key names nothing, which can only happen when a
 * table in the shared module has grown an entry that `:strings` has not: the English prompt or
 * label is a poor name but a better one than blank.
 *
 * `SmartAlbums` resolves the same keys outside composition, where the lookup has to suspend.
 */
@Composable
fun byKey(key: String, fallback: String): String =
    Res.allStringResources[key]?.let { stringResource(it) } ?: fallback
