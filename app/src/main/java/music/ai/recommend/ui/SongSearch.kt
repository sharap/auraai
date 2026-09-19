package music.ai.recommend.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import music.ai.recommend.MusicViewModel
import music.ai.recommend.R
import music.ai.recommend.ScoredSong
import music.ai.recommend.model.Song
import music.ai.recommend.ui.theme.LocalMutedOnBackground

/**
 * One screen's search: its query and results.
 *
 * Held by the screen rather than the ViewModel so that searching inside an album does not
 * overwrite the library search on the other tab, which stays composed in the pager.
 */
@Stable
class SongSearchState {
    var query by mutableStateOf("")
    /** Null while there is no query. */
    var plain by mutableStateOf<List<Song>?>(null)
        internal set
    var ai by mutableStateOf<List<ScoredSong>?>(null)
        internal set
    var aiSearching by mutableStateOf(false)
        internal set
    var aiNeedsModel by mutableStateOf(false)
        internal set

    val isActive: Boolean get() = query.isNotBlank()

    fun clear() {
        query = ""
    }
}

/**
 * @param scope songs the plain-text search is limited to, or null for the whole library. The AI
 *   search always covers the whole library.
 */
@Composable
fun rememberSongSearch(viewModel: MusicViewModel, scope: List<Song>? = null): SongSearchState {
    val state = remember { SongSearchState() }
    // Restarting the effect cancels the previous run, text-encoder inference included.
    LaunchedEffect(state.query, scope) {
        val query = state.query
        if (query.isBlank()) {
            state.plain = null
            state.ai = null
            state.aiSearching = false
            state.aiNeedsModel = false
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        state.plain = viewModel.searchPlain(query, scope)
        state.aiSearching = true
        try {
            val result = viewModel.searchAi(query)
            state.ai = result.matches
            state.aiNeedsModel = result.needsModel
        } finally {
            state.aiSearching = false
        }
    }
    return state
}

@Composable
fun SongSearchField(state: SongSearchState, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = state.query,
        onValueChange = { state.query = it },
        modifier = modifier,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
        trailingIcon = {
            if (state.aiSearching) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else if (state.query.isNotEmpty()) {
                IconButton(onClick = { state.clear() }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(id = R.string.clear_search))
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium
    )
}

/**
 * Plain matches first, then the library-wide AI matches.
 *
 * @param plainTitle header over the plain matches, which differ in reach between screens.
 * @param onPlayPlain plays a plain match; the album screen plays it within the album.
 */
fun LazyListScope.songSearchResults(
    state: SongSearchState,
    plainTitle: String,
    aiTitle: String,
    currentSongId: Long?,
    scannedIds: Set<Long>,
    onPlayPlain: (Song, List<Song>) -> Unit,
    onPlayAi: (Song, List<Song>) -> Unit
) {
    val plain = state.plain
    val ai = state.ai

    if (!plain.isNullOrEmpty()) {
        item(key = "header_plain") {
            SearchHeader(plainTitle, MaterialTheme.colorScheme.primary)
        }
        items(plain, key = { "plain_${it.id}" }) { song ->
            SongItem(
                song = song,
                isActive = song.id == currentSongId,
                isScanned = song.id in scannedIds,
                onClick = { onPlayPlain(song, plain) },
                onLongClick = { }
            )
        }
    } else if (plain != null) {
        item(key = "no_plain") {
            Text(
                text = stringResource(id = R.string.search_nothing_found),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMutedOnBackground.current,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }

    if (state.aiNeedsModel) {
        item(key = "ai_needs_model") {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(id = R.string.ai_search_needs_model),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMutedOnBackground.current
            )
        }
    }

    if (!ai.isNullOrEmpty()) {
        item(key = "header_ai") {
            Spacer(modifier = Modifier.height(16.dp))
            SearchHeader(aiTitle, MaterialTheme.colorScheme.secondary)
        }
        items(ai, key = { "ai_${it.song.id}" }) { scored ->
            SongItem(
                song = scored.song,
                isActive = scored.song.id == currentSongId,
                isScanned = true,
                score = scored.score,
                onClick = { onPlayAi(scored.song, ai.map { it.song }) },
                onLongClick = { }
            )
        }
    }
}

@Composable
private fun SearchHeader(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

private const val SEARCH_DEBOUNCE_MS = 300L
