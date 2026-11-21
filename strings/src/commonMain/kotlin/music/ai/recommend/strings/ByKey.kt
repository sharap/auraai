package music.ai.recommend.strings

import androidx.compose.runtime.Composable
import music.ai.recommend.strings.resources.Res
import music.ai.recommend.strings.resources.allStringResources
import org.jetbrains.compose.resources.stringResource

/**
 * A word from this module, by the key the shared code names it with.
 *
 * Shared code cannot hold a resource of either platform, so what it holds is a key — see
 * `WaveMood.nameKey` and `AlbumLabel.nameKey` — and the interface looks it up. [fallback] is what
 * to show when the key names nothing, which happens only if a table in the shared module has grown
 * an entry this module has not: the English prompt behind a mood is a poor name, and a better one
 * than blank.
 *
 * It lives here, in the module that holds the words, because it is the one place both interfaces
 * can reach that knows what Compose is: the shared module has no Compose dependency on purpose.
 * Each port had its own copy of these three lines for a day, which is how a shared thing starts.
 *
 * Outside composition the lookup has to suspend — `Res.allStringResources[key]` plus `getString` —
 * and each port does that in its own smart-album builder.
 */
@Composable
fun byKey(key: String, fallback: String): String =
    Res.allStringResources[key]?.let { stringResource(it) } ?: fallback
