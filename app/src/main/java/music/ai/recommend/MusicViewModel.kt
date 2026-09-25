package music.ai.recommend

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import music.ai.recommend.ai.AiScanState
import music.ai.recommend.ai.AudioModelVariant
import music.ai.recommend.ai.ClapTextEncoder
import music.ai.recommend.ai.DailyMixBuilder
import music.ai.recommend.ai.EmbeddingStore
import music.ai.recommend.ai.ModelAsset
import music.ai.recommend.ai.ModelProgress
import music.ai.recommend.ai.ModelRepository
import music.ai.recommend.ai.ScanStage
import music.ai.recommend.ai.SmartAlbum
import music.ai.recommend.ai.SmartAlbumBuilder
import music.ai.recommend.ai.SmartAlbumClustering
import music.ai.recommend.history.PlayHistory
import music.ai.recommend.model.Folder
import music.ai.recommend.model.Song
import music.ai.recommend.scanner.MusicScanner
import music.ai.recommend.ui.theme.BackgroundTone
import music.ai.recommend.ui.theme.measureBackgroundTone
import kotlin.math.roundToInt
import java.lang.reflect.Type

class UriAdapter : JsonSerializer<Uri>, JsonDeserializer<Uri> {
    override fun serialize(src: Uri, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        return JsonPrimitive(src.toString())
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Uri {
        return Uri.parse(json.asString)
    }
}

@androidx.compose.runtime.Immutable
data class Playlist(val name: String, val songs: List<Song>)
data class EqBand(val index: Int, val freq: Int, val level: Int)
data class EqPreset(val name: String, val levels: List<Int>)
data class ScoredSong(val song: Song, val score: Float)

/** @property needsModel the text model is not on the device, so no AI search was attempted. */
data class AiSearchResult(val matches: List<ScoredSong>, val needsModel: Boolean = false)

/** What the settings screen needs to know about the on-device weights. */
data class ModelStatus(
    val variant: AudioModelVariant,
    val audioReady: Boolean,
    val textReady: Boolean,
    val audioBundled: Boolean,
    val textBundled: Boolean,
    val quantizedReady: Boolean,
    val fullReady: Boolean,
    val quantizedBundled: Boolean,
    val fullBundled: Boolean,
    val pendingBytes: Long
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = MusicScanner()
    // Shared with AiScanService: it writes embeddings and drives downloads while this ViewModel
    // renders them, so both sides must see the same cache and the same progress flow.
    private val modelRepository = ModelRepository.getInstance(application)
    private val embeddings = EmbeddingStore.getInstance(application)
    private val textEncoder by lazy { ClapTextEncoder(modelRepository) }
    private val smartAlbumBuilder by lazy { SmartAlbumBuilder(application, modelRepository, embeddings, textEncoder) }
    private val playHistory = PlayHistory.getInstance(application)
    private val dailyMixBuilder by lazy { DailyMixBuilder(application, embeddings, playHistory) }
    private val gson = GsonBuilder()
        .registerTypeAdapter(Uri::class.java, UriAdapter())
        .create()
    private val prefs = application.getSharedPreferences("music_prefs", Context.MODE_PRIVATE)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _favoriteSongIds = MutableStateFlow<Set<Long>>(emptySet())
    val favoriteSongIds: StateFlow<Set<Long>> = _favoriteSongIds.asStateFlow()

    private val _backgroundImageUri = MutableStateFlow<String?>(null)
    val backgroundImageUri: StateFlow<String?> = _backgroundImageUri.asStateFlow()

    private val _backgroundAlpha = MutableStateFlow(0.3f)
    val backgroundAlpha: StateFlow<Float> = _backgroundAlpha.asStateFlow()

    /** Brightness of the wallpaper, so the theme can pick content colours that read against it. */
    private val _backgroundTone = MutableStateFlow(BackgroundTone.Unknown)
    val backgroundTone: StateFlow<BackgroundTone> = _backgroundTone.asStateFlow()

    private val _eqBands = MutableStateFlow<List<EqBand>>(emptyList())
    val eqBands: StateFlow<List<EqBand>> = _eqBands.asStateFlow()

    private val _eqRange = MutableStateFlow(-1500..1500)
    val eqRange: StateFlow<IntRange> = _eqRange.asStateFlow()

    private val _eqPresets = MutableStateFlow<List<EqPreset>>(emptyList())
    val eqPresets: StateFlow<List<EqPreset>> = _eqPresets.asStateFlow()

    private val _eqEnabled = MutableStateFlow(prefs.getBoolean(PlaybackService.KEY_EQ_ENABLED, true))
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    private val _pauseOnDisconnect =
        MutableStateFlow(prefs.getBoolean(PlaybackService.KEY_PAUSE_ON_DISCONNECT, true))
    val pauseOnDisconnect: StateFlow<Boolean> = _pauseOnDisconnect.asStateFlow()

    private val _handleAudioFocus =
        MutableStateFlow(prefs.getBoolean(PlaybackService.KEY_HANDLE_AUDIO_FOCUS, true))
    val handleAudioFocus: StateFlow<Boolean> = _handleAudioFocus.asStateFlow()

    private val _dailyMix = MutableStateFlow<List<Song>>(emptyList())
    /** The playlist of the day: fixed for the day, rebuilt at midnight. */
    val dailyMix: StateFlow<List<Song>> = _dailyMix.asStateFlow()

    private val _dailyMixBuilding = MutableStateFlow(false)
    val dailyMixBuilding: StateFlow<Boolean> = _dailyMixBuilding.asStateFlow()

    private val _smartAlbums = MutableStateFlow<List<SmartAlbum>>(emptyList())
    val smartAlbums: StateFlow<List<SmartAlbum>> = _smartAlbums.asStateFlow()

    private val _smartAlbumsEpsScale = MutableStateFlow(prefs.getFloat(KEY_SMART_ALBUMS_EPS_SCALE, 1f))
    /** Multiplier on the automatically chosen DBSCAN eps; 1 means automatic. */
    val smartAlbumsEpsScale: StateFlow<Float> = _smartAlbumsEpsScale.asStateFlow()

    private val _smartAlbumsBuilding = MutableStateFlow(false)
    val smartAlbumsBuilding: StateFlow<Boolean> = _smartAlbumsBuilding.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _aiScanProgress = MutableStateFlow(0f)
    val aiScanProgress: StateFlow<Float> = _aiScanProgress.asStateFlow()

    private val _aiScanStatus = MutableStateFlow("")
    val aiScanStatus: StateFlow<String> = _aiScanStatus.asStateFlow()

    /** Owned by AiScanService, which outlives this ViewModel. */
    val isAiScanning: StateFlow<Boolean> = AiScanState.running

    private val _scannedSongIds = MutableStateFlow<Set<Long>>(emptySet())
    val scannedSongIds: StateFlow<Set<Long>> = _scannedSongIds.asStateFlow()


    /** Set when embeddings from an older, incorrect analysis had to be discarded. */
    private val _analysisReset = MutableStateFlow(false)
    val analysisReset: StateFlow<Boolean> = _analysisReset.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _shuffleModeEnabled = MutableStateFlow(false)
    val shuffleModeEnabled: StateFlow<Boolean> = _shuffleModeEnabled.asStateFlow()

    private val _aiShuffleEnabled = MutableStateFlow(false)
    val aiShuffleEnabled: StateFlow<Boolean> = _aiShuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _audioSessionId = MutableStateFlow<Int?>(null)
    val audioSessionId: StateFlow<Int?> = _audioSessionId.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _sleepTimerRemaining = MutableStateFlow<Long?>(null) // ms
    val sleepTimerRemaining: StateFlow<Long?> = _sleepTimerRemaining.asStateFlow()

    /** Progress of an in-flight weights download. */
    val modelProgress: StateFlow<ModelProgress> = modelRepository.progress

    /** Non-null and different from the selection when the library needs re-analysing. */
    val embeddingsVariant: StateFlow<AudioModelVariant?> = modelRepository.embeddingsVariant

    private val _modelStatus = MutableStateFlow(readModelStatus())
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    /**
     * Analysed-song counts per folder and per playlist.
     *
     * The list items used to derive these inline, so every badge repaint walked the folder's whole
     * song list — and the virtual "All Tracks" folder holds the entire library. Computing them once
     * off the main thread keeps scrolling cheap no matter how large the library is.
     */
    val folderScanCounts: StateFlow<Map<String, Int>> =
        combine(_folders, _scannedSongIds) { folders, scanned ->
            if (scanned.isEmpty()) emptyMap()
            else folders.associate { folder -> folder.name to folder.songs.count { it.id in scanned } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val playlistScanCounts: StateFlow<Map<String, Int>> =
        combine(_playlists, _scannedSongIds) { playlists, scanned ->
            if (scanned.isEmpty()) emptyMap()
            else playlists.associate { playlist -> playlist.name to playlist.songs.count { it.id in scanned } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var saveQueueJob: Job? = null
    private var saveEqJob: Job? = null
    private var downloadJob: Job? = null
    private var smartAlbumsJob: Job? = null
    private var dailyMixJob: Job? = null
    private var savedPlaylists: List<Playlist> = emptyList()
    private var playlistSongs: List<Song> = emptyList()
    private var allSongs: List<Song> = emptyList()

    init {
        // Two cheap scalar reads; everything else is a SharedPreferences read plus a Gson parse,
        // and the playlists blob grows with the library. Doing that during ViewModel construction
        // put it squarely on the main thread in front of the first frame.
        _backgroundImageUri.value = prefs.getString("background_uri", null)
        _backgroundAlpha.value = prefs.getFloat("background_alpha", 0.3f)
        refreshBackgroundTone()
        initializeController()
        refreshScannedIds()
        observeScan()

        viewModelScope.launch(Dispatchers.IO) {
            loadFavorites()
            loadEqPresets()
            loadPlaylists()
        }
    }

    @OptIn(UnstableApi::class)
    private fun initializeController() {
        val sessionToken = SessionToken(
            getApplication(),
            ComponentName(getApplication(), PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(getApplication(), sessionToken).buildAsync()
        controllerFuture?.addListener({
            val c = controllerFuture?.get() ?: return@addListener
            controller = c

            _isPlaying.value = c.isPlaying
            _duration.value = c.duration
            _currentPosition.value = c.currentPosition
            _shuffleModeEnabled.value = c.shuffleModeEnabled
            _repeatMode.value = c.repeatMode

            c.sessionExtras.let { extras ->
                val sessionId = extras.getInt("AUDIO_SESSION_ID", -1)
                if (sessionId != -1) {
                    _audioSessionId.value = sessionId
                }
            }

            fetchEqParams()
            fetchAudioOptions()
            syncCurrentMediaItem(c)

            if (c.isPlaying) startProgressUpdate()

            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    if (isPlaying) startProgressUpdate() else stopProgressUpdate()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val mediaId = mediaItem?.mediaId?.toLongOrNull()
                    val song = playlistSongs.find { it.id == mediaId } ?: allSongs.find { it.id == mediaId }
                    _currentSong.value = song
                    _duration.value = c.duration
                    saveCurrentQueue()

                    if (_aiShuffleEnabled.value && song != null) {
                        applySmartShuffle(song)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        _duration.value = c.duration
                    }
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    _shuffleModeEnabled.value = shuffleModeEnabled
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    _repeatMode.value = repeatMode
                }
            })

            if (c.mediaItemCount == 0) {
                restoreQueue()
            }
        }, MoreExecutors.directExecutor())
    }

    fun loadMusic() {
        if (_isScanning.value) return
        _isScanning.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { scanner.scanMusic(getApplication()) }
                allSongs = result.flatMap { it.songs }

                val allTracksFolder = Folder(getApplication<Application>().getString(R.string.all_tracks), allSongs)
                _folders.value = listOf(allTracksFolder) + result

                rebuildPlaylists() // Favorites is derived from allSongs, which just changed.
                refreshSmartAlbums()
                refreshDailyMix()
                controller?.let { syncCurrentMediaItem(it) }
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun syncCurrentMediaItem(c: Player) {
        val mediaId = c.currentMediaItem?.mediaId?.toLongOrNull()
        if (mediaId != null) {
            val song = playlistSongs.find { it.id == mediaId } ?: allSongs.find { it.id == mediaId }
            _currentSong.value = song

            if (song != null && playlistSongs.isEmpty()) {
                val byId = allSongs.associateBy { it.id }
                val items = ArrayList<Song>(c.mediaItemCount)
                for (i in 0 until c.mediaItemCount) {
                    val mId = c.getMediaItemAt(i).mediaId.toLongOrNull()
                    byId[mId]?.let { items.add(it) }
                }
                if (items.isNotEmpty()) {
                    playlistSongs = items
                    _queue.value = items
                }
            }
        }
    }

    fun playSong(song: Song, playlist: List<Song>) {
        playlistSongs = playlist
        _queue.value = playlist
        val mediaItems = playlist.map { createMediaItem(it) }
        val index = playlist.indexOfFirst { it.id == song.id }.coerceAtLeast(0)

        controller?.apply {
            setMediaItems(mediaItems, index, 0L)
            prepare()
            play()
        }
        saveCurrentQueue()
    }

    fun pause() {
        controller?.pause()
        saveCurrentQueue(immediate = true)
    }

    fun resume() {
        controller?.play()
    }

    fun next() {
        controller?.seekToNext()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(position: Long) {
        controller?.seekTo(position)
        _currentPosition.value = position
    }

    fun toggleShuffle() {
        controller?.let {
            it.shuffleModeEnabled = !it.shuffleModeEnabled
            if (it.shuffleModeEnabled) _aiShuffleEnabled.value = false
        }
    }

    fun toggleAiShuffle() {
        _aiShuffleEnabled.value = !_aiShuffleEnabled.value
        if (_aiShuffleEnabled.value) {
            controller?.shuffleModeEnabled = false
            _currentSong.value?.let { applySmartShuffle(it) }
        }
    }

    private fun applySmartShuffle(currentSong: Song) {
        val c = controller ?: return
        val currentIndex = c.currentMediaItemIndex
        val mediaItemCount = c.mediaItemCount
        val upcomingIds = ArrayList<Pair<Int, Long>>()

        for (i in (currentIndex + 1) until mediaItemCount) {
            c.getMediaItemAt(i).mediaId.toLongOrNull()?.let { upcomingIds.add(i to it) }
        }
        if (upcomingIds.isEmpty()) return

        viewModelScope.launch {
            try {
                val stored = embeddings.all()
                val currentEmbedding = stored[currentSong.id] ?: return@launch

                val ranked = withContext(Dispatchers.Default) {
                    upcomingIds
                        .map { (index, mediaId) ->
                            val embedding = stored[mediaId]
                            index to (embedding?.let { cosineSimilarity(currentEmbedding, it) } ?: -1f)
                        }
                        .sortedByDescending { it.second }
                        .take(5)
                }

                // Stack the best matches directly after the current track, furthest first.
                ranked.asReversed().forEach { (originalIdx, _) ->
                    val mediaIdToFind = upcomingIds.find { it.first == originalIdx }?.second?.toString() ?: return@forEach
                    for (j in 0 until c.mediaItemCount) {
                        if (c.getMediaItemAt(j).mediaId == mediaIdToFind) {
                            val currentIdx = c.currentMediaItemIndex
                            if (j > currentIdx && j != currentIdx + 1) {
                                c.moveMediaItem(j, currentIdx + 1)
                                val q = _queue.value.toMutableList()
                                if (j < q.size && (currentIdx + 1) < q.size) {
                                    q.add(currentIdx + 1, q.removeAt(j))
                                    _queue.value = q
                                    playlistSongs = q
                                }
                            }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Smart shuffle failed", e)
            }
        }
    }

    fun nextRepeatMode() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun removeFromQueue(index: Int) {
        controller?.removeMediaItem(index)
        val currentList = _queue.value.toMutableList()
        if (index in currentList.indices) {
            currentList.removeAt(index)
            _queue.value = currentList
            playlistSongs = currentList
            saveCurrentQueue()
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        controller?.moveMediaItem(from, to)
        val currentList = _queue.value.toMutableList()
        if (from in currentList.indices && to in currentList.indices) {
            currentList.add(to, currentList.removeAt(from))
            _queue.value = currentList
            playlistSongs = currentList
            saveCurrentQueue()
        }
    }

    fun addToEndOfQueue(song: Song) {
        controller?.addMediaItem(createMediaItem(song))
        val currentList = _queue.value.toMutableList()
        currentList.add(song)
        _queue.value = currentList
        playlistSongs = currentList
        saveCurrentQueue()
    }

    fun playNext(song: Song) {
        val currentIndex = controller?.currentMediaItemIndex ?: -1
        val nextIndex = if (currentIndex == -1) 0 else currentIndex + 1
        controller?.addMediaItem(nextIndex, createMediaItem(song))

        val currentList = _queue.value.toMutableList()
        if (nextIndex <= currentList.size) currentList.add(nextIndex, song) else currentList.add(song)
        _queue.value = currentList
        playlistSongs = currentList
        saveCurrentQueue()
    }

    /** Adds many songs in one controller call instead of one round trip per song. */
    fun addAllToEndOfQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        controller?.addMediaItems(songs.map { createMediaItem(it) })
        val currentList = _queue.value.toMutableList()
        currentList.addAll(songs)
        _queue.value = currentList
        playlistSongs = currentList
        saveCurrentQueue()
    }

    fun playAllNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        val currentIndex = controller?.currentMediaItemIndex ?: -1
        val nextIndex = if (currentIndex == -1) 0 else currentIndex + 1
        controller?.addMediaItems(nextIndex, songs.map { createMediaItem(it) })

        val currentList = _queue.value.toMutableList()
        currentList.addAll(nextIndex.coerceAtMost(currentList.size), songs)
        _queue.value = currentList
        playlistSongs = currentList
        saveCurrentQueue()
    }

    private fun createMediaItem(song: Song): MediaItem {
        return MediaItem.Builder()
            .setUri(song.uri)
            .setMediaId(song.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .build()
            )
            .build()
    }

    fun deleteSong(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.delete(song.uri, null, null)
                withContext(Dispatchers.Main) { loadMusic() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete file", e)
            }
        }
    }

    private fun loadFavorites() {
        val json = prefs.getString("favorite_ids", null) ?: return
        val type = object : TypeToken<Set<Long>>() {}.type
        _favoriteSongIds.value = runCatching { gson.fromJson<Set<Long>>(json, type) }.getOrNull() ?: emptySet()
    }

    fun toggleFavorite(songId: Long) {
        val current = _favoriteSongIds.value.toMutableSet()
        if (!current.remove(songId)) current.add(songId)
        _favoriteSongIds.value = current
        persistAsync("favorite_ids", current)
        rebuildPlaylists()
    }

    /** Parses the stored playlists. Only needed at startup — afterwards [savedPlaylists] is current. */
    private fun loadPlaylists() {
        val json = prefs.getString("playlists", null)
        savedPlaylists = if (json != null) {
            val type = object : TypeToken<List<Playlist>>() {}.type
            runCatching { gson.fromJson<List<Playlist>>(json, type) }.getOrNull() ?: emptyList()
        } else {
            emptyList()
        }
        rebuildPlaylists()
    }

    /**
     * Recomputes the visible list: the virtual Favorites entry in front of the stored playlists.
     *
     * Separate from [loadPlaylists] because tapping a heart only changes which songs are favourite —
     * re-reading and re-parsing the whole playlists blob on every tap was pure waste.
     */
    private fun rebuildPlaylists() {
        val favoriteIds = _favoriteSongIds.value
        val favoriteSongs = if (favoriteIds.isEmpty()) emptyList() else allSongs.filter { it.id in favoriteIds }
        _playlists.value = if (favoriteSongs.isNotEmpty()) {
            listOf(Playlist(getApplication<Application>().getString(R.string.favorites), favoriteSongs)) + savedPlaylists
        } else {
            savedPlaylists
        }
    }

    fun savePlaylist(name: String) {
        createPlaylistWithSongs(name, _queue.value)
    }

    fun createPlaylistWithSongs(name: String, songs: List<Song>) {
        updatePlaylists(_playlists.value + Playlist(name, songs))
    }

    fun addSongsToPlaylist(playlistName: String, songs: List<Song>) {
        val updated = _playlists.value.map { playlist ->
            if (playlist.name == playlistName) {
                val existing = playlist.songs.mapTo(HashSet()) { it.id }
                playlist.copy(songs = playlist.songs + songs.filter { it.id !in existing })
            } else playlist
        }
        updatePlaylists(updated)
    }

    fun overwritePlaylist(playlistName: String, songs: List<Song>) {
        updatePlaylists(_playlists.value.map { if (it.name == playlistName) it.copy(songs = songs) else it })
    }

    fun addSongToPlaylist(playlistName: String, song: Song) {
        addSongsToPlaylist(playlistName, listOf(song))
    }

    private fun updatePlaylists(newPlaylists: List<Playlist>) {
        // The Favorites entry is virtual and rebuilt from favorite_ids, so it is not persisted.
        val favoritesName = getApplication<Application>().getString(R.string.favorites)
        savedPlaylists = newPlaylists.filterNot { it.name == favoritesName }
        rebuildPlaylists()
        persistAsync("playlists", savedPlaylists)
    }

    fun deletePlaylist(playlist: Playlist) {
        updatePlaylists(_playlists.value.filter { it.name != playlist.name })
    }

    fun setBackgroundImage(uri: Uri?) {
        if (uri != null) {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to take persistable permission", e)
            }
        }
        _backgroundImageUri.value = uri?.toString()
        prefs.edit().putString("background_uri", uri?.toString()).apply()
        refreshBackgroundTone()
    }

    private fun refreshBackgroundTone() {
        val uri = _backgroundImageUri.value
        if (uri == null) {
            _backgroundTone.value = BackgroundTone.Unknown
            return
        }
        viewModelScope.launch {
            _backgroundTone.value = measureBackgroundTone(getApplication(), uri) ?: BackgroundTone.Unknown
        }
    }

    fun setBackgroundAlpha(alpha: Float) {
        _backgroundAlpha.value = alpha
        prefs.edit().putFloat("background_alpha", alpha).apply()
    }

    private fun fetchEqParams() {
        val c = controller ?: return
        val future = c.sendCustomCommand(SessionCommand("GET_EQ_PARAMS", Bundle.EMPTY), Bundle.EMPTY)
        future.addListener({
            val result = future.get()
            if (result.resultCode != SessionResult.RESULT_SUCCESS) return@addListener
            val extras = result.extras
            val numBands = extras.getInt("num_bands", 0)
            val freqs = extras.getIntArray("center_freqs") ?: IntArray(0)
            val levels = extras.getIntArray("band_levels") ?: IntArray(0)

            _eqRange.value = extras.getInt("min_level", -1500)..extras.getInt("max_level", 1500)
            val bands = List(numBands) { i -> EqBand(i, freqs.getOrElse(i) { 0 }, levels.getOrElse(i) { 0 }) }
            _eqBands.value = bands

            // The service is the authority on whether the effect is actually on.
            _eqEnabled.value = extras.getBoolean("enabled", _eqEnabled.value)

            val savedJson = prefs.getString("current_eq_levels", null) ?: return@addListener
            val type = object : TypeToken<List<Int>>() {}.type
            val savedLevels: List<Int> = runCatching { gson.fromJson<List<Int>>(savedJson, type) }.getOrNull() ?: return@addListener
            savedLevels.forEachIndexed { index, level ->
                if (index < bands.size) setEqBandLevel(index, level)
            }
        }, MoreExecutors.directExecutor())
    }

    /** The service is the authority once connected; before that the stored values stand in. */
    private fun fetchAudioOptions() {
        val c = controller ?: return
        val future = c.sendCustomCommand(
            SessionCommand(PlaybackService.COMMAND_GET_AUDIO_OPTIONS, Bundle.EMPTY),
            Bundle.EMPTY
        )
        future.addListener({
            val result = runCatching { future.get() }.getOrNull() ?: return@addListener
            if (result.resultCode != SessionResult.RESULT_SUCCESS) return@addListener
            _pauseOnDisconnect.value =
                result.extras.getBoolean(PlaybackService.KEY_PAUSE_ON_DISCONNECT, _pauseOnDisconnect.value)
            _handleAudioFocus.value =
                result.extras.getBoolean(PlaybackService.KEY_HANDLE_AUDIO_FOCUS, _handleAudioFocus.value)
        }, MoreExecutors.directExecutor())
    }

    /** Pause instead of continuing on the speaker when headphones are unplugged. */
    fun setPauseOnDisconnect(enabled: Boolean) =
        setAudioOption(_pauseOnDisconnect, PlaybackService.KEY_PAUSE_ON_DISCONNECT,
            PlaybackService.COMMAND_SET_PAUSE_ON_DISCONNECT, enabled)

    /** Pause for calls, duck for notifications, give way to other players. */
    fun setHandleAudioFocus(enabled: Boolean) =
        setAudioOption(_handleAudioFocus, PlaybackService.KEY_HANDLE_AUDIO_FOCUS,
            PlaybackService.COMMAND_SET_AUDIO_FOCUS, enabled)

    private fun setAudioOption(
        state: MutableStateFlow<Boolean>,
        key: String,
        command: String,
        enabled: Boolean
    ) {
        if (state.value == enabled) return
        state.value = enabled
        // Written here as well as by the service, so the setting survives even if the service has
        // not been started yet and reads it fresh when it is.
        prefs.edit().putBoolean(key, enabled).apply()
        controller?.sendCustomCommand(
            SessionCommand(command, Bundle.EMPTY),
            Bundle().apply { putBoolean("enabled", enabled) }
        )
    }

    fun setEqBandLevel(band: Int, level: Int) {
        val args = Bundle().apply {
            putInt("band", band)
            putInt("level", level)
        }
        controller?.sendCustomCommand(SessionCommand("SET_EQ_BAND", Bundle.EMPTY), args)

        val updatedBands = _eqBands.value.map { if (it.index == band) it.copy(level = level) else it }
        _eqBands.value = updatedBands

        // A slider drag fires dozens of these per second; only the resting value needs to survive a
        // restart, so the Gson encode and the SharedPreferences write are coalesced.
        saveEqJob?.cancel()
        saveEqJob = viewModelScope.launch(Dispatchers.IO) {
            delay(PERSIST_DEBOUNCE_MS)
            prefs.edit().putString("current_eq_levels", gson.toJson(updatedBands.map { it.level })).apply()
        }
    }

    /**
     * Turns the effect on or off without discarding the curve, so switching back on restores what
     * the user had.
     */
    fun setEqEnabled(enabled: Boolean) {
        if (_eqEnabled.value == enabled) return
        _eqEnabled.value = enabled
        prefs.edit().putBoolean(PlaybackService.KEY_EQ_ENABLED, enabled).apply()
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.COMMAND_SET_EQ_ENABLED, Bundle.EMPTY),
            Bundle().apply { putBoolean("enabled", enabled) }
        )
    }

    fun resetEqualizer() {
        _eqBands.value.forEach { setEqBandLevel(it.index, 0) }
    }

    private fun loadEqPresets() {
        val json = prefs.getString("eq_presets", null) ?: return
        val type = object : TypeToken<List<EqPreset>>() {}.type
        _eqPresets.value = runCatching { gson.fromJson<List<EqPreset>>(json, type) }.getOrNull() ?: emptyList()
    }

    fun saveEqPreset(name: String) {
        val updated = _eqPresets.value + EqPreset(name, _eqBands.value.map { it.level })
        _eqPresets.value = updated
        persistAsync("eq_presets", updated)
    }

    fun applyEqPreset(preset: EqPreset) {
        preset.levels.forEachIndexed { index, level ->
            if (index < _eqBands.value.size) setEqBandLevel(index, level)
        }
    }

    fun deleteEqPreset(preset: EqPreset) {
        val updated = _eqPresets.value.filter { it.name != preset.name }
        _eqPresets.value = updated
        persistAsync("eq_presets", updated)
    }

    /** Gson encoding of a potentially large structure, off the main thread. */
    private fun persistAsync(key: String, value: Any) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit().putString(key, gson.toJson(value)).apply()
        }
    }

    /**
     * Persists the queue.
     *
     * This runs on every track transition and every queue edit, and the queue can hold thousands of
     * songs — encoding it on the main thread showed up as a stutter at exactly the moment a new
     * track started. Writes are debounced and moved to IO; [immediate] skips the debounce for
     * moments where the process may be about to go away.
     */
    private fun saveCurrentQueue(immediate: Boolean = false) {
        val snapshot = _queue.value
        val songId = _currentSong.value?.id
        val position = controller?.currentPosition ?: 0L

        saveQueueJob?.cancel()
        saveQueueJob = viewModelScope.launch(Dispatchers.IO) {
            if (!immediate) delay(PERSIST_DEBOUNCE_MS)
            val editor = prefs.edit().putString("current_queue", gson.toJson(snapshot))
            songId?.let { editor.putLong("last_song_id", it) }
            editor.putLong("last_position", position)
            editor.apply()
        }
    }

    private fun restoreQueue() {
        viewModelScope.launch {
            val json = prefs.getString("current_queue", null) ?: return@launch
            val restoredQueue: List<Song> = withContext(Dispatchers.IO) {
                val type = object : TypeToken<List<Song>>() {}.type
                runCatching { gson.fromJson<List<Song>>(json, type) }.getOrNull() ?: emptyList()
            }
            if (restoredQueue.isEmpty()) return@launch

            playlistSongs = restoredQueue
            _queue.value = restoredQueue

            val lastSongId = prefs.getLong("last_song_id", -1)
            val lastPosition = prefs.getLong("last_position", 0L)
            val index = restoredQueue.indexOfFirst { it.id == lastSongId }.coerceAtLeast(0)
            _currentSong.value = restoredQueue[index]

            controller?.setMediaItems(restoredQueue.map { createMediaItem(it) }, index, lastPosition)
            controller?.prepare()
            _currentPosition.value = lastPosition
        }
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                val c = controller ?: break
                if (c.isPlaying) _currentPosition.value = c.currentPosition
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
        progressJob = null
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val durationMs = minutes * 60 * 1000L
        _sleepTimerRemaining.value = durationMs

        sleepTimerJob = viewModelScope.launch {
            var remaining = durationMs
            while (remaining > 0) {
                delay(1000)
                remaining -= 1000
                _sleepTimerRemaining.value = remaining
            }
            _sleepTimerRemaining.value = null
            pause()
        }
    }

    fun stopSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimerRemaining.value = null
    }

    // ---------------------------------------------------------------- models

    private fun readModelStatus(): ModelStatus {
        val variant = modelRepository.audioVariant.value
        val needed = variant.assets + ModelAsset.forText
        return ModelStatus(
            variant = variant,
            audioReady = modelRepository.isAvailable(variant.assets),
            textReady = modelRepository.isAvailable(ModelAsset.forText),
            audioBundled = modelRepository.isBundled(variant.asset),
            textBundled = modelRepository.isBundled(ModelAsset.TEXT_MODEL),
            quantizedReady = modelRepository.isAvailable(ModelAsset.AUDIO_MODEL_QUANTIZED),
            fullReady = modelRepository.isAvailable(ModelAsset.AUDIO_MODEL),
            quantizedBundled = modelRepository.isBundled(ModelAsset.AUDIO_MODEL_QUANTIZED),
            fullBundled = modelRepository.isBundled(ModelAsset.AUDIO_MODEL),
            // Only what the chosen variant needs; the other export is not a pending download.
            pendingBytes = modelRepository.pendingDownloadBytes(needed)
        )
    }

    /** Switching export invalidates nothing on disk, but the stored embeddings no longer match. */
    fun selectAudioVariant(variant: AudioModelVariant) {
        modelRepository.selectAudioVariant(variant)
        refreshModelStatus()
    }

    fun refreshModelStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = readModelStatus()
            withContext(Dispatchers.Main) { _modelStatus.value = status }
        }
    }

    /** Fetches every missing weight file up front, rather than waiting for the first scan. */
    fun downloadModels() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            modelRepository.ensure(modelRepository.audioVariant.value.assets + ModelAsset.forText)
            refreshModelStatus()
        }
    }

    fun cancelModelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        refreshModelStatus()
    }

    fun deleteDownloadedModels() {
        viewModelScope.launch {
            // Releasing touches the lazily built scanner and encoder, so a fault in either would
            // otherwise reach an uncaught coroutine and take the process down. Freeing the space is
            // worth attempting even if closing a session misbehaves.
            // The scanner's own session belongs to the service; stopping it releases that.
            if (isAiScanning.value) AiScanService.stop(getApplication())
            runCatching { textEncoder.release() }
                .onFailure { Log.e(TAG, "Releasing the text session failed", it) }
            runCatching { modelRepository.deleteDownloaded() }
                .onFailure { Log.e(TAG, "Deleting downloaded weights failed", it) }
            refreshModelStatus()
        }
    }

    // ---------------------------------------------------------------- ai

    fun startAiScan() {
        if (isAiScanning.value) return
        AiScanService.start(getApplication())
    }

    /**
     * Mirrors the service's progress into the settings screen. Collected for as long as this
     * ViewModel lives; the scan itself keeps going regardless.
     */
    private fun observeScan() {
        viewModelScope.launch {
            var lastRefreshedAt = 0
            AiScanState.stage.collect { stage ->
                applyScanStage(stage)
                if (stage is ScanStage.Scanning && stage.done - lastRefreshedAt >= SCAN_REFRESH_EVERY) {
                    lastRefreshedAt = stage.done
                    refreshScannedIds()
                }
            }
        }
        viewModelScope.launch {
            AiScanState.running.collect { running ->
                if (!running) {
                    refreshScannedIds()
                    refreshModelStatus()
                    refreshSmartAlbums()
                    refreshDailyMix()
                }
            }
        }
        viewModelScope.launch {
            // A 280 MB fetch would otherwise look like a frozen bar to anyone who never opens the
            // AI Models card.
            modelRepository.progress.collect { download ->
                if (download.running && isAiScanning.value) {
                    _aiScanProgress.value = download.fraction
                    _aiScanStatus.value = getApplication<Application>().getString(R.string.ai_model_downloading)
                }
            }
        }
    }

    private fun applyScanStage(stage: ScanStage) {
        val context = getApplication<Application>()
        when (stage) {
            is ScanStage.Idle -> Unit

            is ScanStage.DownloadingModel -> {
                _aiScanProgress.value = stage.progress.fraction
                _aiScanStatus.value = context.getString(R.string.ai_model_downloading)
            }

            is ScanStage.PreparingModel -> {
                _aiScanProgress.value = 0f
                _aiScanStatus.value = context.getString(R.string.ai_engine_init)
            }

            is ScanStage.Scanning -> {
                _aiScanProgress.value = if (stage.total == 0) 0f else stage.done.toFloat() / stage.total
                _aiScanStatus.value = if (stage.title.isEmpty()) {
                    context.getString(R.string.ai_scan_complete)
                } else {
                    val progress = context.getString(R.string.scan_progress, stage.done, stage.total, formatEtr(stage.etrSeconds))
                    "${context.getString(R.string.scanning_track, stage.title)}\n$progress"
                }
            }

            is ScanStage.Failed -> {
                _aiScanStatus.value = context.getString(R.string.ai_scan_error, describeError(stage.reason))
            }
        }
    }

    private fun describeError(reason: String): String {
        val context = getApplication<Application>()
        return when (reason) {
            "not_enough_space" -> context.getString(R.string.model_error_space)
            "download_failed", "model_missing" -> context.getString(R.string.model_error_download)
            AiScanService.REASON_NO_SONGS -> context.getString(R.string.no_songs_to_scan)
            else -> reason
        }
    }

    private fun refreshScannedIds() {
        viewModelScope.launch {
            try {
                _scannedSongIds.value = embeddings.ids()
                _analysisReset.value = embeddings.analysisWasReset
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh scanned IDs", e)
            }
        }
    }

    /**
     * Regroups the analysed tracks into smart albums. Cheap when nothing changed — the builder
     * answers from its cache — so it is called whenever the library or the analysis may have.
     *
     * @param rebuild recluster and rename even if the cached result is current.
     */
    fun refreshSmartAlbums(rebuild: Boolean = false) {
        val library = allSongs
        if (library.isEmpty()) return
        smartAlbumsJob?.cancel()
        smartAlbumsJob = viewModelScope.launch {
            _smartAlbumsBuilding.value = true
            try {
                _smartAlbums.value = smartAlbumBuilder.albums(library, _smartAlbumsEpsScale.value, rebuild)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build smart albums", e)
            } finally {
                // A cancelled run finishes after its replacement started; leave the flag to that one.
                if (smartAlbumsJob === coroutineContext[Job]) _smartAlbumsBuilding.value = false
            }
        }
    }

    fun setSmartAlbumsEpsScale(scale: Float) {
        val snapped = (scale.coerceIn(SmartAlbumClustering.MIN_EPS_SCALE, SmartAlbumClustering.MAX_EPS_SCALE) * 20)
            .roundToInt() / 20f
        if (snapped == _smartAlbumsEpsScale.value) return
        _smartAlbumsEpsScale.value = snapped
        prefs.edit().putFloat(KEY_SMART_ALBUMS_EPS_SCALE, snapped).apply()
        refreshSmartAlbums()
    }

    /**
     * Rebuilds the playlist of the day when it is missing or stale, and schedules the next rebuild
     * for midnight so the app does not have to be restarted to see a new one.
     *
     * @param rebuild asks for a different playlist for today, on the user's request.
     */
    fun refreshDailyMix(rebuild: Boolean = false) {
        val library = allSongs
        if (library.isEmpty()) return
        dailyMixJob?.cancel()
        dailyMixJob = viewModelScope.launch {
            _dailyMixBuilding.value = true
            try {
                val playlist = dailyMixBuilder.playlist(library, _favoriteSongIds.value, rebuild)
                _dailyMix.value = playlist.songs
                if (playlist.songs.isEmpty()) Log.w(TAG, "daily mix came back empty for ${library.size} songs")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build the daily mix", e)
            } finally {
                if (dailyMixJob === coroutineContext[Job]) _dailyMixBuilding.value = false
            }
            delay(dailyMixBuilder.millisUntilNextDay())
            refreshDailyMix()
        }
    }

    fun playDailyMix() {
        val mix = _dailyMix.value
        if (mix.isNotEmpty()) playSong(mix.first(), mix)
    }

    fun stopAiScan() {
        AiScanService.stop(getApplication())
        _aiScanStatus.value = getApplication<Application>().getString(R.string.ai_scan_stopped)
        refreshScannedIds()
    }

    fun clearAiData() {
        viewModelScope.launch {
            embeddings.clear()
            modelRepository.forgetEmbeddingsVariant()
            refreshScannedIds()
            _aiScanStatus.value = getApplication<Application>().getString(R.string.ai_data_cleared)
        }
    }

    fun playSimilar(song: Song) {
        viewModelScope.launch {
            try {
                val stored = embeddings.all()
                val currentEmbedding = stored[song.id] ?: run {
                    _aiScanStatus.value = getApplication<Application>().getString(R.string.song_not_analyzed)
                    return@launch
                }

                val similarSongs = withContext(Dispatchers.Default) {
                    allSongs
                        .mapNotNull { other ->
                            if (other.id == song.id) return@mapNotNull null
                            val embedding = stored[other.id] ?: return@mapNotNull null
                            other to cosineSimilarity(currentEmbedding, embedding)
                        }
                        .sortedByDescending { it.second }
                        .take(30)
                        .map { it.first }
                }

                if (similarSongs.isNotEmpty()) playSong(song, similarSongs)
            } catch (e: Exception) {
                Log.e(TAG, "Play similar failed", e)
            }
        }
    }

    /**
     * Plain-text matches for [query] in [scope], or in the whole library when [scope] is null.
     * A global search keeps only the first [GLOBAL_PLAIN_RESULTS]; within one album every match
     * is shown, since the album is already small.
     */
    suspend fun searchPlain(query: String, scope: List<Song>? = null): List<Song> {
        val songs = scope ?: allSongs
        return withContext(Dispatchers.Default) {
            val matches = songs.asSequence().filter {
                it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
            }
            if (scope == null) matches.take(GLOBAL_PLAIN_RESULTS).toList() else matches.toList()
        }
    }

    /**
     * CLAP matches for [query] across the whole library, wherever the search was started: a
     * description ("calm piano") is a request for music, not for a spot in one album.
     *
     * Screens call this from their own debounced effect, so a new keystroke cancels the previous
     * inference and each screen keeps its own results.
     */
    suspend fun searchAi(query: String): AiSearchResult {
        // Typing must never kick off a 126 MB download on whatever connection is at hand; the
        // settings screen is where the user opts into that.
        if (textEncoder.needsDownload()) return AiSearchResult(emptyList(), needsModel = true)
        return try {
            val stored = embeddings.all()
            if (stored.isEmpty()) return AiSearchResult(emptyList())
            // Without a usable query vector every score would be identical noise.
            val queryEmbedding = textEncoder.encode(query) ?: return AiSearchResult(emptyList())
            val songs = allSongs
            val matches = withContext(Dispatchers.Default) {
                val scored = songs.mapNotNull { song ->
                    val embedding = stored[song.id] ?: return@mapNotNull null
                    song to cosineSimilarity(queryEmbedding, embedding)
                }
                if (scored.isEmpty()) return@withContext emptyList()

                // Where the bulk of the library sits for this particular query. A fixed
                // threshold cannot work: the absolute cosine depends on the wording and on what
                // is in the library, so what marks a match is standing out from the rest.
                val mean = scored.sumOf { it.second.toDouble() } / scored.size
                val deviation = kotlin.math.sqrt(
                    scored.sumOf { (it.second - mean) * (it.second - mean) } / scored.size
                )
                val cut = maxOf(mean + deviation, MIN_SEARCH_SIMILARITY.toDouble())

                scored
                    .filter { it.second >= cut }
                    .sortedByDescending { it.second }
                    .take(50)
                    .map { ScoredSong(it.first, mapSimilarityToDisplay(it.second)) }
            }
            AiSearchResult(matches)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI Search failed", e)
            AiSearchResult(emptyList())
        }
    }

    /** Both vectors are stored unit-length, so the dot product is already the cosine. */
    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        val n = minOf(v1.size, v2.size)
        for (i in 0 until n) {
            dot += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom <= 0f) 0f else dot / denom
    }

    /**
     * Turns a text-audio cosine into the percentage the list shows.
     *
     * Recalibrated for the corrected features. Measured over this checkpoint, an unrelated track
     * scores around 0.11 against a query and a good match around 0.35-0.5; the previous curve was
     * fitted to the old extractor, whose matches peaked near 0.1, so with correct features it
     * reported almost everything as a near-perfect hit.
     */
    private fun mapSimilarityToDisplay(rawSimilarity: Float): Float {
        return when {
            rawSimilarity >= STRONG_SIMILARITY -> 0.99f
            rawSimilarity <= MIN_SEARCH_SIMILARITY -> 0f
            else -> (rawSimilarity - MIN_SEARCH_SIMILARITY) / (STRONG_SIMILARITY - MIN_SEARCH_SIMILARITY)
        }.coerceIn(0f, 1f)
    }

    private fun formatEtr(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        stopProgressUpdate()
        textEncoder.release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "MusicViewModel"
        const val PROGRESS_INTERVAL_MS = 500L
        const val GLOBAL_PLAIN_RESULTS = 20
        const val PERSIST_DEBOUNCE_MS = 400L
        const val SCAN_REFRESH_EVERY = 5
        const val KEY_SMART_ALBUMS_EPS_SCALE = "smart_albums_eps_scale"

        /** Below this a track is not a match for the query under any reading. */
        const val MIN_SEARCH_SIMILARITY = 0.15f

        /** Where a match is unambiguous, and the displayed score saturates. */
        const val STRONG_SIMILARITY = 0.45f
    }
}
