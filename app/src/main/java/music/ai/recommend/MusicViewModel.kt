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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import music.ai.recommend.ai.AiScanner
import music.ai.recommend.ai.ClapTextEncoder
import music.ai.recommend.model.Folder
import music.ai.recommend.model.Song
import music.ai.recommend.scanner.MusicScanner
import music.ai.recommend.R
import java.lang.reflect.Type

class UriAdapter : JsonSerializer<Uri>, JsonDeserializer<Uri> {
    override fun serialize(src: Uri, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        return JsonPrimitive(src.toString())
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Uri {
        return Uri.parse(json.asString)
    }
}

data class Playlist(val name: String, val songs: List<Song>)
data class EqBand(val index: Int, val freq: Int, val level: Int)
data class EqPreset(val name: String, val levels: List<Int>)
data class ScoredSong(val song: Song, val score: Float)

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = MusicScanner()
    private val aiScanner by lazy { AiScanner(application) }
    private val textEncoder by lazy { ClapTextEncoder(application) }
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

    private val _eqBands = MutableStateFlow<List<EqBand>>(emptyList())
    val eqBands: StateFlow<List<EqBand>> = _eqBands.asStateFlow()

    private val _eqRange = MutableStateFlow(-1500..1500)
    val eqRange: StateFlow<IntRange> = _eqRange.asStateFlow()

    private val _eqPresets = MutableStateFlow<List<EqPreset>>(emptyList())
    val eqPresets: StateFlow<List<EqPreset>> = _eqPresets.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _aiScanProgress = MutableStateFlow(0f)
    val aiScanProgress: StateFlow<Float> = _aiScanProgress.asStateFlow()

    private val _aiScanStatus = MutableStateFlow("")
    val aiScanStatus: StateFlow<String> = _aiScanStatus.asStateFlow()

    private val _isAiScanning = MutableStateFlow(false)
    val isAiScanning: StateFlow<Boolean> = _isAiScanning.asStateFlow()

    private val _scannedSongIds = MutableStateFlow<Set<Long>>(emptySet())
    val scannedSongIds: StateFlow<Set<Long>> = _scannedSongIds.asStateFlow()

    private val _aiSearchResults = MutableStateFlow<List<ScoredSong>?>(null)
    val aiSearchResults: StateFlow<List<ScoredSong>?> = _aiSearchResults.asStateFlow()

    private val _regularSearchResults = MutableStateFlow<List<Song>?>(null)
    val regularSearchResults: StateFlow<List<Song>?> = _regularSearchResults.asStateFlow()

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

    private var progressJob: Job? = null
    private var aiScanJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var playlistSongs: List<Song> = emptyList()
    private var allSongs: List<Song> = emptyList()

    init {
        loadFavorites()
        loadPlaylists()
        loadEqPresets()
        refreshScannedIds()
        _backgroundImageUri.value = prefs.getString("background_uri", null)
        _backgroundAlpha.value = prefs.getFloat("background_alpha", 0.3f)
        initializeController()
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
            
            // Sync initial state
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
            
            syncCurrentMediaItem(c)
            
            if (c.isPlaying) startProgressUpdate()

            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    if (isPlaying) {
                        startProgressUpdate()
                    } else {
                        stopProgressUpdate()
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val mediaId = mediaItem?.mediaId?.toLongOrNull()
                    val song = playlistSongs.find { it.id == mediaId } ?: allSongs.find { it.id == mediaId }
                    _currentSong.value = song
                    _duration.value = c.duration
                    saveCurrentQueue()
                    
                    Log.d("MusicViewModel", "Transition to ${song?.title}, reason: $reason, aiShuffle: ${_aiShuffleEnabled.value}")
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = scanner.scanMusic(getApplication())
                allSongs = result.flatMap { it.songs }
                
                // Add virtual "All Tracks" folder
                val allTracksFolder = Folder(getApplication<Application>().getString(R.string.all_tracks), allSongs)
                _folders.value = listOf(allTracksFolder) + result
                
                viewModelScope.launch(Dispatchers.Main) {
                    loadPlaylists() // Ensure Favorites virtual playlist is updated
                }
                
                controller?.let {
                    viewModelScope.launch(Dispatchers.Main) {
                        syncCurrentMediaItem(it)
                    }
                }
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
                val items = mutableListOf<Song>()
                for (i in 0 until c.mediaItemCount) {
                    val mId = c.getMediaItemAt(i).mediaId.toLongOrNull()
                    allSongs.find { it.id == mId }?.let { items.add(it) }
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
        saveCurrentQueue()
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
    }

    fun toggleShuffle() {
        controller?.let {
            it.shuffleModeEnabled = !it.shuffleModeEnabled
            if (it.shuffleModeEnabled) _aiShuffleEnabled.value = false
        }
    }

    fun toggleAiShuffle() {
        _aiShuffleEnabled.value = !_aiShuffleEnabled.value
        Log.d("MusicViewModel", "AI Shuffle toggled: ${_aiShuffleEnabled.value}")
        if (_aiShuffleEnabled.value) {
            controller?.shuffleModeEnabled = false
            _currentSong.value?.let { applySmartShuffle(it) }
        }
    }

    private fun applySmartShuffle(currentSong: Song) {
        val c = controller ?: return
        val currentIndex = c.currentMediaItemIndex
        val mediaItemCount = c.mediaItemCount
        val upcomingIds = mutableListOf<Pair<Int, Long>>()
        
        for (i in (currentIndex + 1) until mediaItemCount) {
            c.getMediaItemAt(i).mediaId.toLongOrNull()?.let { 
                upcomingIds.add(i to it)
            }
        }

        if (upcomingIds.isEmpty()) return

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val embeddings = aiScanner.getStoredEmbeddings()
                val currentEmbedding = embeddings[currentSong.id]
                
                if (currentEmbedding == null) {
                    Log.d("MusicViewModel", "Current song not scanned, skipping smart shuffle")
                    return@launch
                }
                
                val remainingItems = mutableListOf<Pair<Int, Float>>()
                for ((originalIndex, mediaId) in upcomingIds) {
                    val embedding = embeddings[mediaId]
                    val similarity = if (embedding != null) {
                        calculateCosineSimilarity(currentEmbedding, embedding)
                    } else {
                        -1f 
                    }
                    remainingItems.add(originalIndex to similarity)
                }
                
                val sortedRemaining = remainingItems.sortedByDescending { it.second }
                
                withContext(Dispatchers.Main) {
                    val topMatches = sortedRemaining.take(5)
                    
                    // Move the top 5 matches to the next positions (in reverse order to stack correctly)
                    topMatches.asReversed().forEach { (originalIdx, score) ->
                        val mediaIdToFind = upcomingIds.find { it.first == originalIdx }?.second?.toString() ?: return@forEach
                        
                        // Search for the current position of the item (it might have shifted)
                        for (j in 0 until c.mediaItemCount) {
                            if (c.getMediaItemAt(j).mediaId == mediaIdToFind) {
                                val currentIdx = c.currentMediaItemIndex
                                if (j > currentIdx && j != currentIdx + 1) {
                                    val trackName = c.getMediaItemAt(j).mediaMetadata.title
                                    Log.d("MusicViewModel", "Smart Shuffle: Moving '$trackName' (Score: $score) to next position")
                                    c.moveMediaItem(j, currentIdx + 1)
                                    
                                    // Update local state to keep UI in sync
                                    val q = _queue.value.toMutableList()
                                    if (j < q.size && (currentIdx + 1) < q.size) {
                                        val item = q.removeAt(j)
                                        q.add(currentIdx + 1, item)
                                        _queue.value = q
                                        playlistSongs = q
                                    }
                                }
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Smart shuffle failed", e)
            }
        }
    }

    fun nextRepeatMode() {
        controller?.let {
            val nextMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_OFF
            }
            it.repeatMode = nextMode
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
            val item = currentList.removeAt(from)
            currentList.add(to, item)
            _queue.value = currentList
            playlistSongs = currentList
            saveCurrentQueue()
        }
    }

    fun addToEndOfQueue(song: Song) {
        val mediaItem = createMediaItem(song)
        controller?.addMediaItem(mediaItem)
        val currentList = _queue.value.toMutableList()
        currentList.add(song)
        _queue.value = currentList
        playlistSongs = currentList
        saveCurrentQueue()
    }

    fun playNext(song: Song) {
        val currentIndex = controller?.currentMediaItemIndex ?: -1
        val nextIndex = if (currentIndex == -1) 0 else currentIndex + 1
        val mediaItem = createMediaItem(song)
        controller?.addMediaItem(nextIndex, mediaItem)
        
        val currentList = _queue.value.toMutableList()
        if (nextIndex <= currentList.size) {
            currentList.add(nextIndex, song)
        } else {
            currentList.add(song)
        }
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
                loadMusic()
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Failed to delete file", e)
            }
        }
    }

    private fun loadFavorites() {
        val json = prefs.getString("favorite_ids", null)
        if (json != null) {
            val type = object : TypeToken<Set<Long>>() {}.type
            _favoriteSongIds.value = gson.fromJson(json, type)
        }
    }

    fun toggleFavorite(songId: Long) {
        val current = _favoriteSongIds.value.toMutableSet()
        if (songId in current) {
            current.remove(songId)
        } else {
            current.add(songId)
        }
        _favoriteSongIds.value = current
        prefs.edit().putString("favorite_ids", gson.toJson(current)).apply()
        
        // Refresh virtual "Favorites" playlist
        loadPlaylists() 
    }

    private fun loadPlaylists() {
        val json = prefs.getString("playlists", null)
        val savedPlaylists: List<Playlist> = if (json != null) {
            val type = object : TypeToken<List<Playlist>>() {}.type
            gson.fromJson(json, type)
        } else {
            emptyList()
        }
        
        // Add virtual "Favorites" playlist
        val favoriteSongs = allSongs.filter { it.id in _favoriteSongIds.value }
        val finalPlaylists = if (favoriteSongs.isNotEmpty()) {
            listOf(Playlist(getApplication<Application>().getString(R.string.favorites), favoriteSongs)) + savedPlaylists
        } else {
            savedPlaylists
        }
        
        _playlists.value = finalPlaylists
    }

    fun savePlaylist(name: String) {
        createPlaylistWithSongs(name, _queue.value)
    }

    fun createPlaylistWithSongs(name: String, songs: List<Song>) {
        val newPlaylist = Playlist(name, songs)
        val currentPlaylists = _playlists.value.toMutableList()
        currentPlaylists.add(newPlaylist)
        updatePlaylists(currentPlaylists)
    }

    fun addSongsToPlaylist(playlistName: String, songs: List<Song>) {
        val currentPlaylists = _playlists.value.map {
            if (it.name == playlistName) {
                val newSongs = songs.filter { newSong -> 
                    it.songs.none { existingSong -> existingSong.id == newSong.id }
                }
                it.copy(songs = it.songs + newSongs)
            } else it
        }
        updatePlaylists(currentPlaylists)
    }

    fun overwritePlaylist(playlistName: String, songs: List<Song>) {
        val currentPlaylists = _playlists.value.map {
            if (it.name == playlistName) {
                it.copy(songs = songs)
            } else it
        }
        updatePlaylists(currentPlaylists)
    }

    fun addSongToPlaylist(playlistName: String, song: Song) {
        addSongsToPlaylist(playlistName, listOf(song))
    }
    
    private fun updatePlaylists(newPlaylists: List<Playlist>) {
        _playlists.value = newPlaylists
        prefs.edit().putString("playlists", gson.toJson(newPlaylists)).apply()
    }
    
    fun deletePlaylist(playlist: Playlist) {
        val currentPlaylists = _playlists.value.filter { it.name != playlist.name }
        updatePlaylists(currentPlaylists)
    }

    fun setBackgroundImage(uri: Uri?) {
        val uriString = uri?.toString()
        if (uri != null) {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Failed to take persistable permission", e)
            }
        }
        _backgroundImageUri.value = uriString
        prefs.edit().putString("background_uri", uriString).apply()
    }

    fun setBackgroundAlpha(alpha: Float) {
        _backgroundAlpha.value = alpha
        prefs.edit().putFloat("background_alpha", alpha).apply()
    }

    private fun fetchEqParams() {
        controller?.let { c ->
            val future = c.sendCustomCommand(SessionCommand("GET_EQ_PARAMS", Bundle.EMPTY), Bundle.EMPTY)
            future.addListener({
                val result = future.get()
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    val extras = result.extras
                    val numBands = extras.getInt("num_bands", 0)
                    val minLevel = extras.getInt("min_level", -1500)
                    val maxLevel = extras.getInt("max_level", 1500)
                    val freqs = extras.getIntArray("center_freqs") ?: IntArray(0)
                    val levels = extras.getIntArray("band_levels") ?: IntArray(0)
                    
                    _eqRange.value = minLevel..maxLevel
                    val bands = List(numBands) { i ->
                        EqBand(i, freqs.getOrElse(i) { 0 }, levels.getOrElse(i) { 0 })
                    }
                    _eqBands.value = bands

                    // Apply persisted EQ levels if they exist
                    val savedJson = prefs.getString("current_eq_levels", null)
                    if (savedJson != null) {
                        val type = object : TypeToken<List<Int>>() {}.type
                        val savedLevels: List<Int> = gson.fromJson(savedJson, type)
                        savedLevels.forEachIndexed { index, level ->
                            if (index < bands.size) {
                                setEqBandLevel(index, level)
                            }
                        }
                    }
                }
            }, MoreExecutors.directExecutor())
        }
    }

    fun setEqBandLevel(band: Int, level: Int) {
        val args = Bundle().apply {
            putInt("band", band)
            putInt("level", level)
        }
        controller?.sendCustomCommand(SessionCommand("SET_EQ_BAND", Bundle.EMPTY), args)
        
        // Update local state
        val updatedBands = _eqBands.value.map {
            if (it.index == band) it.copy(level = level) else it
        }
        _eqBands.value = updatedBands
        
        // Persist current EQ state
        val levels = updatedBands.map { it.level }
        prefs.edit().putString("current_eq_levels", gson.toJson(levels)).apply()
    }

    fun resetEqualizer() {
        _eqBands.value.forEach { band ->
            setEqBandLevel(band.index, 0)
        }
    }

    private fun loadEqPresets() {
        val json = prefs.getString("eq_presets", null)
        if (json != null) {
            val type = object : TypeToken<List<EqPreset>>() {}.type
            _eqPresets.value = gson.fromJson(json, type)
        }
    }

    fun saveEqPreset(name: String) {
        val levels = _eqBands.value.map { it.level }
        val newPreset = EqPreset(name, levels)
        val current = _eqPresets.value.toMutableList()
        current.add(newPreset)
        _eqPresets.value = current
        prefs.edit().putString("eq_presets", gson.toJson(current)).apply()
    }

    fun applyEqPreset(preset: EqPreset) {
        preset.levels.forEachIndexed { index, level ->
            if (index < _eqBands.value.size) {
                setEqBandLevel(index, level)
            }
        }
    }

    fun deleteEqPreset(preset: EqPreset) {
        val current = _eqPresets.value.filter { it.name != preset.name }
        _eqPresets.value = current
        prefs.edit().putString("eq_presets", gson.toJson(current)).apply()
    }

    private fun saveCurrentQueue() {
        val json = gson.toJson(_queue.value)
        prefs.edit().putString("current_queue", json).apply()
        _currentSong.value?.let {
            prefs.edit().putLong("last_song_id", it.id).apply()
        }
        controller?.let {
            prefs.edit().putLong("last_position", it.currentPosition).apply()
        }
    }

    private fun restoreQueue() {
        val json = prefs.getString("current_queue", null)
        if (json != null) {
            val type = object : TypeToken<List<Song>>() {}.type
            val restoredQueue: List<Song> = gson.fromJson(json, type)
            if (restoredQueue.isNotEmpty()) {
                playlistSongs = restoredQueue
                _queue.value = restoredQueue
                
                val lastSongId = prefs.getLong("last_song_id", -1)
                val lastPosition = prefs.getLong("last_position", 0L)
                val index = restoredQueue.indexOfFirst { it.id == lastSongId }.coerceAtLeast(0)
                if (index >= 0 && index < restoredQueue.size) {
                    _currentSong.value = restoredQueue[index]
                }
                
                val mediaItems = restoredQueue.map { createMediaItem(it) }
                controller?.setMediaItems(mediaItems, index, lastPosition)
                controller?.prepare()
                _currentPosition.value = lastPosition
            }
        }
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                controller?.let {
                    if (it.isPlaying) {
                        _currentPosition.value = it.currentPosition
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
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

    fun startAiScan() {
        if (_isAiScanning.value) return
        if (allSongs.isEmpty()) {
            _aiScanStatus.value = getApplication<Application>().getString(R.string.no_songs_to_scan)
            return
        }
        _isAiScanning.value = true
        aiScanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                aiScanner.scanSongs(allSongs) { progress, status, index, etr ->
                    _aiScanProgress.value = progress
                    if (etr < 0) {
                        _aiScanStatus.value = status
                    } else {
                        val formattedProgress = getApplication<Application>().getString(
                            R.string.scan_progress, index, allSongs.size, formatEtr(etr)
                        )
                        _aiScanStatus.value = "${getApplication<Application>().getString(R.string.scanning_track, status)}\n$formattedProgress"
                    }
                    if (index % 5 == 0) refreshScannedIds()
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "AI Scan failed", e)
                _aiScanStatus.value = getApplication<Application>().getString(R.string.ai_scan_error, e.message ?: "Unknown")
            } finally {
                refreshScannedIds()
                _isAiScanning.value = false
                if (_aiScanStatus.value.contains("Scanning") || _aiScanStatus.value.contains("Сканирование")) {
                    _aiScanStatus.value = getApplication<Application>().getString(R.string.ai_scan_complete)
                }
            }
        }
    }

    private fun refreshScannedIds() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ids = aiScanner.getStoredEmbeddings().keys
                _scannedSongIds.value = ids
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Failed to refresh scanned IDs", e)
            }
        }
    }

    fun stopAiScan() {
        aiScanner.stop()
        aiScanJob?.cancel()
        _isAiScanning.value = false
        _aiScanStatus.value = getApplication<Application>().getString(R.string.ai_scan_stopped)
        refreshScannedIds()
    }

    fun clearAiData() {
        viewModelScope.launch(Dispatchers.IO) {
            aiScanner.clearDatabase()
            refreshScannedIds()
            withContext(Dispatchers.Main) {
                _aiScanStatus.value = "AI data cleared"
            }
        }
    }

    fun playSimilar(song: Song) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val embeddings = aiScanner.getStoredEmbeddings()
                val currentEmbedding = embeddings[song.id] ?: run {
                    withContext(Dispatchers.Main) {
                        _aiScanStatus.value = getApplication<Application>().getString(R.string.song_not_analyzed)
                    }
                    return@launch
                }
                
                val similarSongs = allSongs
                    .filter { it.id in embeddings.keys && it.id != song.id }
                    .map { otherSong ->
                        val otherEmbedding = embeddings[otherSong.id]!!
                        val similarity = calculateCosineSimilarity(currentEmbedding, otherEmbedding)
                        otherSong to similarity
                    }
                    .sortedByDescending { it.second }
                    .map { it.first }
                    .take(30)
                
                if (similarSongs.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        playSong(song, similarSongs)
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Play similar failed", e)
            }
        }
    }

    fun aiSearch(query: String) {
        if (query.isBlank()) {
            _aiSearchResults.value = null
            _regularSearchResults.value = null
            return
        }
        
        // Regular Search
        val regularResults = allSongs.filter { 
            it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
        }.take(20)
        _regularSearchResults.value = regularResults

        // AI Search
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val queryEmbedding = textEncoder.encode(query).toList()
                val embeddings = aiScanner.getStoredEmbeddings()
                
                val results = allSongs
                    .filter { it.id in embeddings.keys }
                    .map { song ->
                        val songEmbedding = embeddings[song.id]!!
                        val similarity = calculateCosineSimilarity(queryEmbedding, songEmbedding)
                        ScoredSong(song, mapSimilarityToDisplay(similarity))
                    }
                    .sortedByDescending { it.score }
                    .filter { it.score > 0.1f } 
                    .take(50)
                
                _aiSearchResults.value = results
            } catch (e: Exception) {
                Log.e("MusicViewModel", "AI Search failed", e)
            }
        }
    }

    private fun calculateCosineSimilarity(v1: List<Float>, v2: List<Float>): Float {
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in 0 until minOf(v1.size, v2.size)) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom <= 0f) 0f else dotProduct / denom
    }

    private fun mapSimilarityToDisplay(rawSimilarity: Float): Float {
        return when {
            rawSimilarity >= 0.45f -> 0.99f
            rawSimilarity <= 0.05f -> 0f
            else -> {
                0.1f + (rawSimilarity - 0.05f) * (0.85f / 0.40f)
            }
        }.coerceIn(0f, 1f)
    }

    private fun formatEtr(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }

    override fun onCleared() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        stopProgressUpdate()
    }
}
