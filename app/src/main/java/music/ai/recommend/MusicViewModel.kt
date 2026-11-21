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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import music.ai.recommend.ai.AiScanState
import music.ai.recommend.ai.AudioModelVariant
import music.ai.recommend.ai.ClapTextEncoder
import music.ai.recommend.ai.DailyMix
import music.ai.recommend.ai.DailyMixBuilder
import music.ai.recommend.ai.MixListen
import music.ai.recommend.ai.MixTrack
import music.ai.recommend.ai.Wave
import music.ai.recommend.ai.WaveSession
import music.ai.recommend.ai.WaveSessions
import music.ai.recommend.ai.WaveState
import music.ai.recommend.ai.EmbeddingStore
import music.ai.recommend.ai.SoundPrint
import music.ai.recommend.ai.SoundPrintStore
import music.ai.recommend.ai.ModelAsset
import music.ai.recommend.ai.ModelProgress
import music.ai.recommend.ai.ModelRepository
import music.ai.recommend.ai.RuTextEncoder
import music.ai.recommend.ai.TrackGroups
import music.ai.recommend.ai.ScanStage
import music.ai.recommend.ai.SmartAlbum
import music.ai.recommend.ai.SmartAlbumBuilder
import music.ai.recommend.ai.SmartAlbumClustering
import music.ai.recommend.history.PlayHistory
import music.ai.recommend.sync.SafSyncFolder
import music.ai.recommend.sync.EmbeddingVectorStore
import music.ai.recommend.sync.PlayHistoryStore
import music.ai.recommend.sync.PlaylistLog
import music.ai.recommend.sync.PlaylistOp
import music.ai.recommend.sync.GcmSealing
import music.ai.recommend.sync.MixShelf
import music.ai.recommend.sync.Sealing
import music.ai.recommend.sync.PlaylistShelf
import music.ai.recommend.sync.DirectoryPeerFiles
import music.ai.recommend.sync.FileSyncFolder
import music.ai.recommend.sync.PeerClient
import music.ai.recommend.sync.PhraseVault
import music.ai.recommend.sync.PeerFiles
import music.ai.recommend.sync.PeerPresence
import music.ai.recommend.sync.PeerTransport
import music.ai.recommend.sync.SyncCoordinator
import music.ai.recommend.sync.SyncFolder
import music.ai.recommend.sync.SyncIdentity
import music.ai.recommend.sync.SyncState
import music.ai.recommend.sync.VectorCourier
import music.ai.recommend.sync.WifiNetwork
import music.ai.recommend.sync.VectorWriter
import music.ai.recommend.model.Folder
import music.ai.recommend.model.Song
import music.ai.recommend.scanner.MusicScanner
import music.ai.recommend.ui.theme.BackgroundTone
import music.ai.recommend.ui.theme.measureBackgroundTone
import kotlin.math.roundToInt
import androidx.core.net.toUri
import java.io.File
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

/** Where a wave takes its initial direction from. */
enum class WaveMode { TASTE, SIMILAR, DESCRIPTION }

/** @property needsModel the text model is not on the device, so no AI search was attempted. */
data class AiSearchResult(
    val matches: List<ScoredSong>,
    val needsModel: Boolean = false,
    /** The missing model is the Russian encoder, which is a different download and a different message. */
    val needsRussianModel: Boolean = false
)

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
    val pendingBytes: Long,
    val russianReady: Boolean,
    val russianBundled: Boolean,
    /** Counted apart from [pendingBytes]: Russian search is an opt-in extra, not part of the set. */
    val russianPendingBytes: Long
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = MusicScanner()
    // Shared with AiScanService: it writes embeddings and drives downloads while this ViewModel
    // renders them, so both sides must see the same cache and the same progress flow.
    private val modelRepository = ModelRepository.getInstance(application)
    private val embeddings = EmbeddingStore.getInstance(application)
    private val soundPrints = SoundPrintStore.getInstance(application)
    private val phrases = PhraseVault(application)
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

    private val _dislikedSongIds = MutableStateFlow<Set<Long>>(emptySet())
    /** Tracks the listener rejected outright; they are kept out of the wave and the daily mix. */
    val dislikedSongIds: StateFlow<Set<Long>> = _dislikedSongIds.asStateFlow()

    private val _waveMode = MutableStateFlow<WaveMode?>(null)
    /** The running wave's mode, or null when no wave is playing. */
    val waveMode: StateFlow<WaveMode?> = _waveMode.asStateFlow()

    private val _waveDescription = MutableStateFlow<String?>(null)
    /** What the wave was asked for, for the subtitle: the query, or the track it started from. */
    val waveDescription: StateFlow<String?> = _waveDescription.asStateFlow()

    private val _waveNovelty = MutableStateFlow(prefs.getFloat(KEY_WAVE_NOVELTY, 0.5f))
    /** 0 keeps the wave to the familiar, 1 makes it dig for what has not been heard in a long time. */
    val waveNovelty: StateFlow<Float> = _waveNovelty.asStateFlow()

    private val _waveMessage = MutableStateFlow<String?>(null)
    /** Why a wave could not start, for the screen to show and then clear. */
    val waveMessage: StateFlow<String?> = _waveMessage.asStateFlow()

    private val _waveStarting = MutableStateFlow(false)
    val waveStarting: StateFlow<Boolean> = _waveStarting.asStateFlow()

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

    /**
     * The two things about the stored vectors that only the owner can decide about.
     *
     * @param analysed tracks of the library that hold a vector. Counted against the library and
     *   not simply as rows held, because the rows outlive the library: this phone holds 392 for
     *   ids MediaStore no longer issues, and a count of them would say the library was analysed
     *   four hundred tracks past its own size.
     * @param unreadable files **of the library** the analysis tried and could not read. Shown
     *   because the answer is almost always a broken file — a download that stopped, a playlist
     *   saved as `.mp3` — and knowing how many there are is what lets them be fixed. Refusals for
     *   tracks that are gone are not among them: there is no file left to fix, and they are
     *   counted with the rest of the orphans below.
     * @param orphaned rows whose track is no longer in the library, vectors and refusals alike.
     * @param orphanedInHistory how many of those the listening history still mentions. They are
     *   the reason this is a button and not something done quietly on startup: a vector for a
     *   deleted track is what the taste model remembers of it, and with a long history that may be
     *   worth more than the space it takes.
     */
    data class VectorHousekeeping(
        val analysed: Int = 0,
        val unreadable: Int = 0,
        val orphaned: Int = 0,
        val orphanedInHistory: Int = 0
    ) {
        /** How many would go if the owner pressed the button. */
        val removable: Int get() = (orphaned - orphanedInHistory).coerceAtLeast(0)
    }

    private val _vectorHousekeeping = MutableStateFlow(VectorHousekeeping())
    val vectorHousekeeping: StateFlow<VectorHousekeeping> = _vectorHousekeeping.asStateFlow()

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
    private var waveJob: Job? = null
    private var waveEventsJob: Job? = null
    /** Serialises the wave's queue edits: fast skips used to interleave them. */
    private val waveLock = Mutex()
    private var waveState: WaveState<Long>? = null
    private var waveRestoreJob: Job? = null
    /** Dials the other device on a timer while the network transport is on; see startPeerPolling. */
    private var peerPollJob: Job? = null
    private var waveTracks: List<MixTrack<Long>> = emptyList()
    private var waveTrackById: Map<Long, MixTrack<Long>> = emptyMap()
    private var savedPlaylists: List<Playlist> = emptyList()
    private var playlistSongs: List<Song> = emptyList()
    private var allSongs: List<Song> = emptyList()
    private var allSongsById: Map<Long, Song> = emptyMap()

    /** What the settings screen shows about sync. */
    data class SyncSummary(
        val folder: String? = null,
        val deviceId: String = "",
        val sent: Int = 0,
        val takenIn: Int = 0,
        /** Embeddings offered to the other devices, and taken from them. */
        val vectorsSent: Int = 0,
        val vectorsTakenIn: Int = 0,
        /** True while a library's worth of vectors is still working its way across. */
        val vectorsWaiting: Boolean = false,
        /** Playlist and favourite changes offered to the other devices, and taken from them. */
        val changesSent: Int = 0,
        val changesTakenIn: Int = 0,
        /** Playlists of the day offered to the other devices, and taken from them. */
        val mixSent: Int = 0,
        val mixTakenIn: Int = 0,
        /**
         * True when a tunnel took this device's outgoing calls; see [PeerTransport.State.tunnelled].
         */
        val tunnelled: Boolean = false,
        /** Whether what this device writes into the folder is sealed; see [GcmSealing]. */
        val sealed: Boolean = false,
        /** Where the phrase that seals it is kept; see [PhraseVault]. */
        val phraseKept: PhraseVault.Where = PhraseVault.Where.PLAIN,
        /**
         * Sealed batches met in the folder that would not open.
         *
         * Shown because it is the only way this fails: the phrase typed here differs from the one
         * typed there, and nothing else would ever say so — a batch that does not open is counted
         * as unreadable and stepped over.
         */
        val unopened: Int = 0,
        val waiting: Int = 0,
        val busy: Boolean = false,
        val lastRunAt: Long = 0L,
        /** Whether this device carries sync itself over the network instead of through a folder. */
        val direct: Boolean = false,
        /** Whether the listener is up, and on which port; see [PeerTransport]. */
        val listening: Boolean = false,
        val port: Int = -1,
        /** This device's addresses, to type on the other one where broadcasts do not arrive. */
        val addresses: List<String> = emptyList(),
        /** Devices heard announcing themselves: the first eight characters of each id, and where. */
        val peers: List<String> = emptyList(),
        /** Why the listener is not up, or what the last conversation stumbled on. */
        val peerTrouble: String? = null,
        /** The address the owner typed, if any: dialled as well as whoever answers a beacon. */
        val peerAddressInUse: String = "",
        /**
         * How many conversations this device has answered. Zero while listening is the signature of
         * a firewall, which is the one failure that looks like success from the inside.
         */
        val served: Int = 0
    )

    private val _syncSummary = MutableStateFlow(SyncSummary())
    val syncSummary: StateFlow<SyncSummary> = _syncSummary.asStateFlow()

    private val syncDirectory by lazy { File(getApplication<Application>().filesDir, "sync") }

    /**
     * Where the socket transport keeps everything: this device's own day files and its copies of
     * the other devices'.
     *
     * Inside the app's own storage, which is the point. A folder in shared storage is readable by
     * anything holding the storage permission and is reached through the storage access framework,
     * and between them that is most of what makes sync unpleasant on a phone. Nothing here is
     * shared with another app, so none of it applies.
     */
    private val localSyncRoot by lazy { File(syncDirectory, "folder") }

    /** The byte view of [localSyncRoot] the socket transport works through. */
    private fun peerFiles(): PeerFiles? {
        val deviceId = syncDeviceId() ?: return null
        return DirectoryPeerFiles(File(localSyncRoot, "aura/v1"), deviceId)
    }

    /**
     * This device's vectors as a peer may ask for them.
     *
     * Over the cached coordinator and never one built here: this is called from whatever thread a
     * peer's conversation is on, and building a coordinator — loading state, starting outboxes —
     * from inside somebody else's phone call is not a thing to do. Until this device has one of
     * its own, the honest answer to a peer is that there is nothing here.
     */
    private val vectorCourier = VectorCourier({ syncCoordinator }, { line -> Log.i(TAG, line) })

    /** The listener, the beacon and one exchange per pass; null until this device has an id. */
    private val peerTransport: PeerTransport? by lazy {
        syncDeviceId()?.let { id ->
            PeerTransport(
                deviceId = id,
                files = { peerFiles() },
                records = { vectorCourier },
                // A VPN takes the route to the machine in the next room along with everything
                // else, and in the places this app is most wanted a VPN is ordinary.
                network = WifiNetwork(getApplication()),
                // What this device is short of, announced so that a device which cannot dial can
                // still ask. Under a VPN that is this device: it answers conversations and never
                // starts one, so without saying this out loud its only hope was to be called by
                // chance on somebody else's thirty-second timer.
                vectorsWanted = {
                    val dealtWith = embeddings.dealtWithNow()
                    if (dealtWith == null) 0 else (allSongs.size - dealtWith).coerceAtLeast(0)
                },
                onPeerInNeed = { peer ->
                    Log.i(TAG, "${peer.deviceId.take(8)} is missing ${peer.wanted} vectors; dialling")
                    viewModelScope.launch { syncPass(force = true, takeIn = true) }
                },
                note = { line -> Log.i(TAG, line) }
            ).also { transport ->
                // So that the scan, which runs in a service of its own, can ask whether waiting
                // for vectors is waiting for anybody; see [PeerPresence].
                PeerPresence.offer(
                    near = { transport.somebodyNear() },
                    conversations = { transport.conversationsHad() },
                    inFlight = { transport.conversationInFlight() }
                )
            }
        }
    }
    private var syncCoordinator: SyncCoordinator<Long>? = null

    /** One pass over the folder at a time; see [syncPass]. */
    private val syncGate = Mutex()

    /** The wait after a playlist change before it is sent; see [settlePlaylists]. */
    private var playlistSettling: Job? = null

    /** Whether the app is in front of the owner right now; see [observeSync]. */
    private val foreground = MutableStateFlow(false)

    /**
     * Every playlist and favourite change this device knows of, its own and the ones that arrived.
     *
     * The log is what travels and what the merged answer is replayed from. The visible state is
     * still kept where it was — a preferences blob and a set of ids — and is overwritten by the
     * replay on each pass, so a change made here shows at once and is agreed on later.
     */
    private val playlistLog by lazy { PlaylistLog(File(syncDirectory, "playlist-log.jsonl")) }

    /** When the folder was last read, so passes nobody asked for do not read it over and over. */
    private var lastPullAt = 0L
    private var syncState: SyncState? = null

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
        observeSync()
        // Only does anything when the owner asked for the network transport; see startPeerListener.
        startPeerListener()

        viewModelScope.launch(Dispatchers.IO) {
            loadFavorites()
            loadDislikes()
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

                    // The wave tops its queue up here, on the track change itself. It used to do
                    // so only when a listen was recorded, and a track left within three seconds
                    // records none: five quick skips ran the queue dry and the stream stopped.
                    if (waveState != null) viewModelScope.launch { refillWave() }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        _duration.value = c.duration
                    }
                    // A wave has no end; if the queue ran dry anyway, refill it and carry on.
                    if (playbackState == Player.STATE_ENDED && waveState != null) {
                        viewModelScope.launch {
                            refillWave()
                            controller?.play()
                        }
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
                allSongsById = allSongs.associateBy { it.id }

                val allTracksFolder = Folder(getApplication<Application>().getString(R.string.all_tracks), allSongs)
                _folders.value = listOf(allTracksFolder) + result

                rebuildPlaylists() // Favorites is derived from allSongs, which just changed.
                // Before the first pass, so that what this device asks for is named by its sound
                // and not only by its spelling.
                refreshSoundPrints()
                refreshSmartAlbums()
                refreshDailyMix()
                refreshVectorHousekeeping()
                // The earliest moment anything from the other device can be placed: before the
                // scan there is nothing to match a listen against. A device coming back after
                // being away catches up here, without being asked.
                launch { syncPass(force = true, takeIn = true) }
                controller?.let { syncCurrentMediaItem(it) }
                restoreWave()
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

    fun playSong(song: Song, playlist: List<Song>, fromWave: Boolean = false) {
        // Picking a track by hand ends the stream: it is no longer the wave's queue.
        if (!fromWave) stopWave()
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

    /**
     * Plays a track the queue already holds, by moving to it instead of rebuilding the playlist.
     *
     * Tapping ahead in the queue is skipping, not choosing something else, so a running wave
     * carries on from there. Going through [playSong] set the same tracks again as a new playlist,
     * which counted as picking by hand and silently ended the stream.
     */
    fun playFromQueue(song: Song) {
        val controller = controller ?: return
        val index = (0 until controller.mediaItemCount)
            .firstOrNull { controller.getMediaItemAt(it).mediaId.toLongOrNull() == song.id }
            ?: return playSong(song, _queue.value)
        controller.seekTo(index, 0L)
        controller.play()
    }

    fun pause() {
        controller?.pause()
        saveCurrentQueue(immediate = true)
    }

    fun resume() {
        controller?.play()
    }

    fun next() {
        val controller = controller ?: return
        // At the end of the queue seekToNext does nothing at all, which during a wave looks like
        // the player ignoring the button. A wave has no end, so queue more and then move on.
        if (waveState != null && !controller.hasNextMediaItem()) {
            viewModelScope.launch {
                refillWave()
                this@MusicViewModel.controller?.seekToNext()
            }
            return
        }
        controller.seekToNext()
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
        // The wave orders its own queue by the same similarity; the two reordering each other's
        // tracks produced a queue nobody could predict.
        if (waveState != null) return
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
                                syncQueueFromController()
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
        syncQueueFromController()
        saveCurrentQueue()
    }

    fun moveQueueItem(from: Int, to: Int) {
        controller?.moveMediaItem(from, to)
        syncQueueFromController()
        saveCurrentQueue()
    }

    fun addToEndOfQueue(song: Song) {
        controller?.addMediaItem(createMediaItem(song))
        syncQueueFromController()
        saveCurrentQueue()
    }

    fun playNext(song: Song) {
        val currentIndex = controller?.currentMediaItemIndex ?: -1
        val nextIndex = if (currentIndex == -1) 0 else currentIndex + 1
        controller?.addMediaItem(nextIndex, createMediaItem(song))
        syncQueueFromController()
        saveCurrentQueue()
    }

    /** Adds many songs in one controller call instead of one round trip per song. */
    fun addAllToEndOfQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        controller?.addMediaItems(songs.map { createMediaItem(it) })
        syncQueueFromController()
        saveCurrentQueue()
    }

    fun playAllNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        val currentIndex = controller?.currentMediaItemIndex ?: -1
        val nextIndex = if (currentIndex == -1) 0 else currentIndex + 1
        controller?.addMediaItems(nextIndex, songs.map { createMediaItem(it) })
        syncQueueFromController()
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
                // Parsed back here, which is the one place that needs the platform type.
                getApplication<Application>().contentResolver.delete(
                    android.net.Uri.parse(song.uri), null, null
                )
                withContext(Dispatchers.Main) { loadMusic() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete file", e)
            }
        }
    }

    private fun loadDislikes() {
        val json = prefs.getString("disliked_ids", null) ?: return
        val type = object : TypeToken<Set<Long>>() {}.type
        _dislikedSongIds.value = runCatching { gson.fromJson<Set<Long>>(json, type) }.getOrNull() ?: emptySet()
    }

    private fun loadFavorites() {
        val json = prefs.getString("favorite_ids", null) ?: return
        val type = object : TypeToken<Set<Long>>() {}.type
        _favoriteSongIds.value = runCatching { gson.fromJson<Set<Long>>(json, type) }.getOrNull() ?: emptySet()
    }

    fun toggleFavorite(songId: Long) {
        val current = _favoriteSongIds.value.toMutableSet()
        val added = !current.remove(songId)
        if (added) current.add(songId)
        _favoriteSongIds.value = current
        persistAsync("favorite_ids", current)
        rebuildPlaylists()
        allSongsById[songId]?.let { song ->
            recordPlaylistChanges(
                listOf(Change(if (added) PlaylistOp.Kind.ADD else PlaylistOp.Kind.REMOVE, track = song))
            )
        }
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
        recordPlaylistChanges(
            // Created explicitly as well as filled, so that a playlist made empty is still a
            // playlist on the other device.
            listOf(Change(PlaylistOp.Kind.CREATE, playlist = name)) +
                songs.map { Change(PlaylistOp.Kind.ADD, playlist = name, track = it) }
        )
    }

    fun addSongsToPlaylist(playlistName: String, songs: List<Song>) {
        val updated = _playlists.value.map { playlist ->
            if (playlist.name == playlistName) {
                val existing = playlist.songs.mapTo(HashSet()) { it.id }
                playlist.copy(songs = playlist.songs + songs.filter { it.id !in existing })
            } else playlist
        }
        updatePlaylists(updated)
        // Every one asked for, including any already there: replaying an addition of what is
        // already in a playlist changes nothing, and working out which were new would be a second
        // place for that rule to live.
        recordPlaylistChanges(songs.map { Change(PlaylistOp.Kind.ADD, playlist = playlistName, track = it) })
    }

    fun overwritePlaylist(playlistName: String, songs: List<Song>) {
        val before = _playlists.value.find { it.name == playlistName }?.songs.orEmpty()
        updatePlaylists(_playlists.value.map { if (it.name == playlistName) it.copy(songs = songs) else it })
        // Replacing what is in a playlist is a removal of what left and an addition of what
        // arrived. Said that way it merges with the other device's changes instead of overruling
        // them, which is the whole reason these are changes and not a new list.
        val keeping = songs.mapTo(HashSet()) { it.id }
        val gone = before.filter { it.id !in keeping }
        val arrived = songs.filter { fresh -> before.none { it.id == fresh.id } }
        recordPlaylistChanges(
            gone.map { Change(PlaylistOp.Kind.REMOVE, playlist = playlistName, track = it) } +
                arrived.map { Change(PlaylistOp.Kind.ADD, playlist = playlistName, track = it) }
        )
    }

    fun addSongToPlaylist(playlistName: String, song: Song) {
        addSongsToPlaylist(playlistName, listOf(song))
    }

    /**
     * Sets the order of a playlist, as the owner has just dragged it.
     *
     * The whole list travels, not the move, because a position means nothing on a device whose
     * copy of the playlist has changed since. Replay then puts what it is given first and
     * anything it does not mention after, so a track that arrived while the list was being
     * dragged about is at the end rather than gone.
     *
     * Which is also why what is shown here is not simply [songs]: the re-derivation that follows
     * the write brings back anything that arrived meanwhile. Showing [songs] alone would drop it
     * until the next pass.
     */
    fun reorderPlaylist(playlistName: String, songs: List<Song>) {
        // The favourites are rebuilt from a set of ids and shown in library order, so there is no
        // order of theirs to set; the screen does not offer it there either.
        if (playlistName == getApplication<Application>().getString(R.string.favorites)) return
        val before = _playlists.value.find { it.name == playlistName } ?: return
        if (before.songs.map { it.id } == songs.map { it.id }) return
        updatePlaylists(_playlists.value.map {
            if (it.name == playlistName) it.copy(songs = songs) else it
        })
        recordPlaylistChanges(
            listOf(Change(PlaylistOp.Kind.ORDER, playlist = playlistName, tracks = songs))
        )
    }

    fun removeSongFromPlaylist(playlistName: String, song: Song) {
        // Favourites are not a stored playlist but a set of ids rebuilt into one, so taking a
        // track out of them is unhearting it. Done through [updatePlaylists] it would look like
        // it worked and be back at the next rebuild, because the ids would not have changed.
        if (playlistName == getApplication<Application>().getString(R.string.favorites)) {
            if (song.id in _favoriteSongIds.value) toggleFavorite(song.id)
            return
        }
        val updated = _playlists.value.map { playlist ->
            if (playlist.name == playlistName) {
                playlist.copy(songs = playlist.songs.filterNot { it.id == song.id })
            } else playlist
        }
        updatePlaylists(updated)
        // Recorded whether or not it was there, for the same reason an addition is: replaying the
        // removal of what a playlist does not hold changes nothing, and deciding that here would
        // be a second place for the rule to live.
        recordPlaylistChanges(listOf(Change(PlaylistOp.Kind.REMOVE, playlist = playlistName, track = song)))
    }

    // ------------------------------------------------------------------ playlists that travel
    //
    // The visible state stays where it was — a set of ids and a preferences blob — and every change
    // to it is also written into the log as an operation, so it can travel. A pass then replays the
    // whole log, its own changes among them, and replaces what is shown with the result. So a change
    // made here is visible at once and agreed on with the other device shortly after.

    /** One change, before it is given a device, a counter and a time. */
    private data class Change(
        val kind: PlaylistOp.Kind,
        val playlist: String? = null,
        val track: Song? = null,
        val newName: String? = null,
        /** The whole playlist, for [PlaylistOp.Kind.ORDER]. */
        val tracks: List<Song> = emptyList()
    )

    /**
     * Writes changes into the log, which numbers them as it writes.
     *
     * Numbering is the log's to do and not this method's: taking a counter and writing with it are
     * two steps, and a sync pass can write between them.
     */
    private fun recordPlaylistChanges(changes: List<Change>) {
        if (changes.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val deviceId = syncDeviceId() ?: return@launch
            val at = System.currentTimeMillis()
            playlistLog.appendOwn { firstSeq ->
                changes.mapIndexed { index, change ->
                    PlaylistOp(
                        kind = change.kind,
                        seq = firstSeq + index,
                        deviceId = deviceId,
                        at = at,
                        playlist = change.playlist,
                        track = change.track?.let { PlaylistShelf.keyOf(it) },
                        newName = change.newName,
                        tracks = change.tracks.map { PlaylistShelf.keyOf(it) }
                    )
                }
            }
            // What is shown is the replay of the log, so it is re-derived here rather than left as
            // whatever the mutation did to it. Two things come out of that: a pass can no longer
            // take away a change made here, and a change the log refused does not appear to have
            // worked and then vanish at the next pass.
            runCatching { coordinator()?.showPlaylists() }
            settlePlaylists()
        }
    }

    /**
     * Sends playlist changes a few seconds after the last of them.
     *
     * A heart tapped is a deliberate act and its absence on the other device is noticed — which is
     * how this came to light: a track hearted here and one there, and neither moved, because a
     * change by itself caused no pass and the next one was whenever the app happened to be opened
     * again. A few seconds rather than at once so that hearting a dozen tracks is one write and not
     * a dozen; each change restarts the wait.
     */
    private fun settlePlaylists() {
        playlistSettling?.cancel()
        playlistSettling = viewModelScope.launch {
            delay(PLAYLIST_SETTLE_MS)
            syncPass(force = true, takeIn = false)
        }
    }

    /**
     * Replaces what this device shows with what the log says, once a pass has merged it.
     *
     * The favourites entry is virtual and rebuilt from the ids, so a playlist of that name is not
     * kept among the stored ones — the same rule [updatePlaylists] follows.
     */
    private suspend fun showMergedPlaylists(favourites: List<Long>, playlists: Map<String, List<Long>>) {
        val favouritesName = getApplication<Application>().getString(R.string.favorites)
        val byId = allSongsById
        val merged = playlists
            .filterKeys { it != favouritesName }
            .map { (name, ids) -> Playlist(name, ids.mapNotNull { byId[it] }) }
        val favouriteIds = favourites.toSet()
        withContext(Dispatchers.Main) {
            _favoriteSongIds.value = favouriteIds
            savedPlaylists = merged
            rebuildPlaylists()
        }
        persistAsync("favorite_ids", favouriteIds)
        persistAsync("playlists", merged)
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
        // The favourites entry is virtual and comes back the moment anything is hearted, so there
        // is nothing to tell the other device about.
        if (playlist.name != getApplication<Application>().getString(R.string.favorites)) {
            recordPlaylistChanges(listOf(Change(PlaylistOp.Kind.DELETE, playlist = playlist.name)))
        }
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
            restoreWave()
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
            pendingBytes = modelRepository.pendingDownloadBytes(needed),
            russianReady = modelRepository.isAvailable(ModelAsset.forRussianText),
            russianBundled = modelRepository.isBundled(ModelAsset.RU_TEXT_MODEL),
            russianPendingBytes = modelRepository.pendingDownloadBytes(ModelAsset.forRussianText)
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

    /**
     * Fetches the Russian encoder, on its own.
     *
     * Separate from [downloadModels] on purpose: 30 MB is not much, but it is pointless for someone
     * who never types Cyrillic, and nobody should pay for it by accident.
     */
    fun downloadRussianModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            modelRepository.ensure(ModelAsset.forRussianText)
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
            // Two counters and not one: a vector arriving and a track being analysed are both
            // progress, but they are counted differently — arrivals are the whole table, analysis
            // starts from zero each scan. Sharing one high-water mark would mean the first
            // analysed track sits below it and nothing is refreshed for the rest of the scan.
            var analysedAt = 0
            var arrivedAt = 0
            AiScanState.stage.collect { stage ->
                applyScanStage(stage)
                when (stage) {
                    is ScanStage.Scanning ->
                        if (stage.done - analysedAt >= SCAN_REFRESH_EVERY) {
                            analysedAt = stage.done
                            refreshScannedIds()
                        }
                    // Refreshed while they arrive as well. Four thousand vectors can land in half
                    // a minute, and a screen that only catches up at the end is a screen that
                    // spent that half minute saying something untrue.
                    is ScanStage.Receiving ->
                        if (stage.held - arrivedAt >= SCAN_REFRESH_EVERY) {
                            arrivedAt = stage.held
                            refreshScannedIds()
                        }
                    else -> Unit
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
                    refreshVectorHousekeeping()
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

            is ScanStage.Receiving -> {
                // Said as what it is. Nothing is being analysed here: the other device already
                // did it, and this is the handover.
                //
                // Clamped, because the table can hold rows for tracks the library no longer has —
                // a file deleted between one scan and the next — and a bar past its end is a bug
                // the eye reads before any number does. The counts themselves are left as they
                // are: if they disagree with the library, that is worth seeing.
                _aiScanProgress.value =
                    if (stage.library == 0) 0f
                    else (stage.held.toFloat() / stage.library).coerceIn(0f, 1f)
                _aiScanStatus.value =
                    context.getString(R.string.ai_scan_receiving, stage.held, stage.library)
            }

            is ScanStage.Scanning -> {
                _aiScanProgress.value =
                    if (stage.total == 0) 0f
                    else (stage.done.toFloat() / stage.total).coerceIn(0f, 1f)
                val progress = context.getString(
                    R.string.scan_progress, stage.done, stage.total, formatEtr(stage.etrSeconds)
                )
                _aiScanStatus.value =
                    "${context.getString(R.string.scanning_track, stage.title)}\n$progress"
            }

            is ScanStage.Finished -> {
                _aiScanProgress.value = 1f
                _aiScanStatus.value = context.getString(R.string.ai_scan_complete)
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

    /**
     * Takes the prints of files that have none, or whose file has changed since.
     *
     * Not part of the scan and not part of a pass: a print is what makes a track nameable at all,
     * and the tracks it matters most for are the ones with no vector yet — those are precisely
     * what this device asks its peer about. Cheap enough for the whole library: a stat and a
     * quarter of a megabyte each, 0.19 ms a file on a desktop and a couple of milliseconds on a
     * phone, so four and a half thousand is seconds. Hashing whole files would have been minutes,
     * and decoding each excerpt first, which was the other candidate, far longer than that.
     */
    private fun refreshSoundPrints() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val library = allSongs.associate { it.id to it.path }
                if (library.isEmpty()) return@launch
                val taken = soundPrints.refresh(library)
                if (taken > 0) Log.i(TAG, "Took $taken sound prints")
                soundPrints.keepOnly(library.keys)
                // Always, and not only when prints were taken: the copies may have been waiting
                // since before this device knew what their sound was.
                if (fillTwinVectors() > 0) {
                    refreshScannedIds()
                    refreshVectorHousekeeping()
                    refreshSmartAlbums()
                    refreshDailyMix()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not take the sound prints", e)
            }
        }
    }

    /**
     * Gives a track the vector of another copy of the same sound.
     *
     * No model and no network: the same sound has the same vector, which is what a print means.
     * One library holds the same recording twice more often than one would think — 186 sounds of
     * 4371 here — and every copy used to be analysed again from scratch. A copy could not even be
     * filled by sync: a peer served the vector, this device recognised the sound as one it already
     * had and dropped it as nothing new, so 99 tracks sat empty with the answer in the same table.
     */
    private suspend fun fillTwinVectors(): Int {
        val prints = soundPrints.all()
        if (prints.isEmpty()) return 0
        val twins = SoundPrint.twins(prints, embeddings.ids())
        if (twins.isEmpty()) return 0
        val held = embeddings.all()
        val copies = HashMap<Long, FloatArray>(twins.size * 2)
        for ((empty, holder) in twins) {
            copies[empty] = held[holder] ?: continue
        }
        if (copies.isEmpty()) return 0
        val stored = embeddings.putAll(copies)
        if (stored > 0) Log.i(TAG, "Filled $stored vectors from another copy of the same sound")
        return stored
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
     * Counts the unreadable files and the vectors with no track.
     *
     * Not called while a scan is running: it reads the whole history for the tracks it mentions,
     * and the answer cannot change under a scan in any way worth showing a second sooner.
     */
    private fun refreshVectorHousekeeping() {
        viewModelScope.launch {
            try {
                val library = allSongsById.keys
                // Before the library has been read, everything would look orphaned. Say nothing
                // rather than something alarming and wrong.
                if (library.isEmpty()) {
                    _vectorHousekeeping.value = VectorHousekeeping()
                    return@launch
                }
                val refusals = embeddings.refusals()
                val analysed = embeddings.ids()
                val held = analysed + refusals.keys
                val orphaned = held.filterTo(HashSet()) { it !in library }
                val mentioned = playHistory.tracksMentioned()
                _vectorHousekeeping.value = VectorHousekeeping(
                    analysed = analysed.count { it in library },
                    unreadable = refusals.keys.count { it in library },
                    orphaned = orphaned.size,
                    // A history that could not be read counts as mentioning all of them, so the
                    // button offers nothing rather than offering to delete what it cannot check.
                    orphanedInHistory = if (mentioned == null) orphaned.size
                    else orphaned.count { it in mentioned }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not count the vectors with no track", e)
            }
        }
    }

    /**
     * Drops the vectors whose track is gone *and* which the history does not mention.
     *
     * The history is the whole reason this is not simply "delete what has no track". A listen to a
     * track that was deleted still shapes the taste profile and the daily playlist, and its vector
     * is the only record of what it sounded like; the wave would be reading a row it cannot place
     * without it. So those stay, however many there are, and what goes is the rest: ids MediaStore
     * no longer issues, which nothing can ever ask about again.
     */
    fun clearOrphanedVectors() {
        viewModelScope.launch {
            try {
                val library = allSongsById.keys
                if (library.isEmpty()) return@launch
                val mentioned = playHistory.tracksMentioned() ?: return@launch
                val refusals = embeddings.refusals()
                val held = embeddings.ids() + refusals.keys
                val gone = held.filter { it !in library && it !in mentioned }
                if (gone.isEmpty()) return@launch
                embeddings.remove(gone)
                Log.i(TAG, "Dropped ${gone.size} vectors whose track is gone")
                refreshScannedIds()
                refreshVectorHousekeeping()
                _aiScanStatus.value = getApplication<Application>()
                    .getString(R.string.orphaned_vectors_cleared, gone.size)
            } catch (e: Exception) {
                Log.e(TAG, "Could not drop the vectors with no track", e)
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

    // ------------------------------------------------------------------ the wave

    /**
     * Starts an endless stream steered by what happens next: a track heard out pulls it closer, a
     * skip pushes it away. Unlike the daily mix, nothing is decided in advance — the queue is kept
     * a few tracks deep and rebuilt whenever the listener says something.
     *
     * @param query only for [WaveMode.DESCRIPTION]: what the stream should sound like.
     */
    /**
     * @param label what to show as the wave's subtitle, when it reads better than [query] — a mood
     *   button passes the mood's name while sending CLAP the English prompt behind it.
     */
    fun startWave(mode: WaveMode, query: String? = null, label: String? = null) {
        waveJob?.cancel()
        _waveStarting.value = true
        waveJob = viewModelScope.launch {
            try {
                val vectors = embeddings.all()
                val library = allSongs.filter { it.id in vectors }.distinctBy { it.id }
                if (library.size < MIN_WAVE_LIBRARY) {
                    _waveMessage.value = getApplication<Application>().getString(R.string.wave_needs_analysis)
                    return@launch
                }
                _waveMessage.value = null
                val seed = waveSeed(mode, query, vectors) ?: return@launch

                waveTracks = waveLibrary(library, vectors)
                waveTrackById = waveTracks.associateBy { it.track }
                val songsById = library.associateBy { it.id }

                val state = Wave.start(seed, waveTracks, after = _currentSong.value?.id?.let { waveTrackById[it] })
                val (first, queued) = Wave.next(
                    state = state,
                    tracks = waveTracks,
                    count = QUEUE_AHEAD + 1,
                    excluded = _dislikedSongIds.value,
                    novelty = _waveNovelty.value
                )
                if (first.isEmpty()) return@launch
                waveState = queued
                _waveMode.value = mode
                _waveDescription.value = when (mode) {
                    WaveMode.DESCRIPTION -> label ?: query
                    WaveMode.SIMILAR -> _currentSong.value?.title
                    WaveMode.TASTE -> null
                }
                val songs = first.mapNotNull { songsById[it.track] }
                playSong(songs.first(), songs, fromWave = true)
                observeWave()
                persistWave()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start the wave", e)
            } finally {
                _waveStarting.value = false
            }
        }
    }

    /**
     * The library as the wave sees it: analysed tracks, each with the act to keep off repeat and
     * when it was last heard.
     */
    private suspend fun waveLibrary(library: List<Song>, vectors: Map<Long, FloatArray>): List<MixTrack<Long>> {
        val lastPlayed = playHistory.lastPlayed()
        return library.map { song ->
            MixTrack<Long>(
                track = song.id,
                vector = vectors.getValue(song.id),
                group = TrackGroups.of(song.artist, song.title, song.folderName),
                lastPlayedAt = lastPlayed[song.id]
            )
        }
    }

    /** The direction the stream sets off in. */
    private suspend fun waveSeed(mode: WaveMode, query: String?, vectors: Map<Long, FloatArray>): FloatArray? =
        when (mode) {
            WaveMode.SIMILAR -> vectors[_currentSong.value?.id] ?: vectors.values.randomOrNull()

            WaveMode.DESCRIPTION -> {
                if (query.isNullOrBlank()) null
                else if (textEncoder.needsDownload(query)) {
                    _waveMessage.value = getApplication<Application>().getString(
                        if (RuTextEncoder.handles(query)) R.string.ai_search_needs_russian_model
                        else R.string.ai_search_needs_model
                    )
                    null
                } else textEncoder.encode(query)
            }

            // The taste profile, which is the same signal the daily mix starts from: what has been
            // played recently, with skips counting against and favourites counting for.
            WaveMode.TASTE -> withContext(Dispatchers.Default) {
                val listens = playHistory.recent().map { MixListen<Long>(it.track, it.playedAt, it.fraction) }
                val weights = DailyMix.tasteWeights(listens, _favoriteSongIds.value, System.currentTimeMillis())
                    .filterValues { it > 0f }
                    .filterKeys { it in vectors }
                if (weights.isEmpty()) return@withContext vectors.values.randomOrNull()
                val dimensions = vectors.values.first().size
                val sum = FloatArray(dimensions)
                for ((id, weight) in weights) {
                    val vector = vectors.getValue(id)
                    for (i in 0 until minOf(dimensions, vector.size)) sum[i] += weight * vector[i]
                }
                sum
            }
        }

    /**
     * Reacts to every finished listen while the wave runs: steers, then refills the queue. A
     * rejection also throws away what was queued behind it — a skip that only took effect three
     * tracks later would not feel like being listened to.
     */
    private fun observeWave() {
        // One subscription per wave: a second one would top the queue up twice per track.
        waveEventsJob?.cancel()
        waveEventsJob = viewModelScope.launch {
            playHistory.events.collect { event ->
                val state = waveState ?: return@collect
                // Only what the wave queued steers it. Whatever was playing when the wave started
                // is closed by the same mechanism, and a wave asked to sound like that track would
                // otherwise take its own seed for a skip and head straight away from it.
                if (event.track !in state.played) return@collect
                val track = waveTrackById[event.track] ?: return@collect
                val fraction = Wave.fractionOf(event.playedMs, event.durationMs)
                waveState = Wave.react(state, track, fraction)
                persistWave()
                refillWave(replaceTail = fraction < SKIP_FRACTION)
            }
        }
    }

    /**
     * Keeps [QUEUE_AHEAD] tracks waiting behind the one playing.
     *
     * @param replaceTail throw away what was queued and put fresh tracks there instead, which is
     *   what a skip asks for: a rejection taking effect three tracks later would not feel like
     *   being listened to.
     *
     * The replacements are chosen first and swapped in afterwards, in one call. Removing the tail
     * before having anything to put in its place left the player with an empty queue for as long
     * as the scoring took, and a track ending inside that gap stopped playback — which is what
     * fast skipping ran into.
     */
    private suspend fun refillWave(replaceTail: Boolean = false) = waveLock.withLock {
        val state = waveState ?: return@withLock
        val controller = controller ?: return@withLock
        val from = controller.currentMediaItemIndex + 1
        val queued = controller.mediaItemCount - from
        val needed = if (replaceTail) QUEUE_AHEAD else QUEUE_AHEAD - queued
        if (needed <= 0) return@withLock

        val current = waveTrackById[_currentSong.value?.id]
        // Scoring the whole library is a few million multiplications; off the main thread, so a
        // track change never costs a dropped frame.
        val (picked, walked) = withContext(Dispatchers.Default) {
            Wave.next(
                state = state,
                tracks = waveTracks,
                count = needed,
                current = current,
                excluded = _dislikedSongIds.value,
                novelty = _waveNovelty.value
            )
        }
        val songs = picked.mapNotNull { allSongsById[it.track] }
        if (songs.isEmpty()) return@withLock
        waveState = walked

        val items = songs.map { createMediaItem(it) }
        if (replaceTail && queued > 0) {
            controller.replaceMediaItems(from, controller.mediaItemCount, items)
        } else {
            controller.addMediaItems(items)
        }
        syncQueueFromController()
        saveCurrentQueue()
        persistWave()
    }

    /**
     * Rebuilds the shown queue from what the player actually holds.
     *
     * The queue used to be kept twice — once in the player, once as a list for the UI — so anything
     * that reordered the player's items behind the list's back (smart shuffle did) left the two
     * disagreeing, which showed up as a track playing that the queue did not list.
     */
    private fun syncQueueFromController() {
        val controller = controller ?: return
        val songs = (0 until controller.mediaItemCount).mapNotNull { index ->
            allSongsById[controller.getMediaItemAt(index).mediaId.toLongOrNull()]
        }
        _queue.value = songs
        playlistSongs = songs
    }

    /**
     * Writes the wave down so it can be picked up again after the app is unloaded.
     *
     * Called on every reaction and every refill — a few kilobytes on the IO dispatcher, at most
     * twice per track. Android reclaims the process of an app left in the background, and without
     * this the queue came back from the saved playlist while the direction behind it was gone: a
     * wave that quietly turned into an ordinary queue of the last few tracks.
     */
    private fun persistWave() {
        val state = waveState ?: return
        val mode = _waveMode.value ?: return
        val session = WaveSession(mode.name, _waveDescription.value, state, System.currentTimeMillis())
        val file = waveSessionFile()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Identities as text, which is what version 2 of the file writes; on this port a
                    // track is a MediaStore id. See WaveSessions.
                    file.outputStream().use { WaveSessions.write(it, session) { id -> id.toString() } }
            } catch (e: Exception) {
                Log.w(TAG, "Could not save the wave", e)
            }
        }
    }

    /**
     * Picks a wave back up where it was left.
     *
     * Attempted from both ends — when the library finishes loading and when the queue is restored —
     * because resuming needs the analysed library and also needs to know what is playing, and which
     * of the two arrives last depends on whether the playback service outlived the app.
     */
    private fun restoreWave() {
        if (waveState != null || waveRestoreJob?.isActive == true) return
        val playing = _currentSong.value?.id ?: return
        if (allSongs.isEmpty()) return
        waveRestoreJob = viewModelScope.launch {
            val file = waveSessionFile()
            val session = withContext(Dispatchers.IO) {
                if (!file.exists()) null
                else runCatching {
                    file.inputStream().use { WaveSessions.read(it) { text -> text.toLongOrNull() } }
                }.getOrNull()
            } ?: return@launch

            val mode = runCatching { WaveMode.valueOf(session.mode) }.getOrNull()
            // Playing something the wave never queued means the listener moved on; a wave older
            // than half a day is not the mood it was. Either way, let it go.
            if (mode == null || playing !in session.state.played ||
                System.currentTimeMillis() - session.savedAt > WAVE_MAX_AGE_MS
            ) {
                forgetWave()
                return@launch
            }

            val vectors = embeddings.all()
            val library = allSongs.filter { it.id in vectors }.distinctBy { it.id }
            if (library.size < MIN_WAVE_LIBRARY) return@launch
            waveTracks = waveLibrary(library, vectors)
            waveTrackById = waveTracks.associateBy { it.track }
            waveState = session.state
            _waveMode.value = mode
            _waveDescription.value = session.description
            observeWave()
            Log.i(TAG, "wave resumed after ${session.state.steps} tracks")
            // The restored queue is as long as it was saved; top it back up to the usual depth.
            refillWave()
        }
    }

    private fun forgetWave() {
        val file = waveSessionFile()
        viewModelScope.launch(Dispatchers.IO) { runCatching { file.delete() } }
    }

    private fun waveSessionFile() = File(getApplication<Application>().filesDir, "wave_session.bin")

    /** Takes effect from the next track the wave queues, not only on the next wave. */
    fun setWaveNovelty(value: Float) {
        val snapped = (value.coerceIn(0f, 1f) * 20).roundToInt() / 20f
        if (snapped == _waveNovelty.value) return
        _waveNovelty.value = snapped
        prefs.edit().putFloat(KEY_WAVE_NOVELTY, snapped).apply()
    }

    fun clearWaveMessage() {
        _waveMessage.value = null
    }

    fun stopWave() {
        waveJob?.cancel()
        waveJob = null
        waveEventsJob?.cancel()
        waveEventsJob = null
        waveRestoreJob?.cancel()
        waveRestoreJob = null
        waveState = null
        forgetWave()
        waveTracks = emptyList()
        waveTrackById = emptyMap()
        _waveMode.value = null
        _waveDescription.value = null
    }

    /**
     * Rejects the playing track: it is remembered, the wave is steered away from it, and playback
     * moves on. Works outside a wave too — the daily mix leaves rejected tracks out as well.
     */
    fun dislikeCurrent() {
        val song = _currentSong.value ?: return
        val disliked = _dislikedSongIds.value + song.id
        _dislikedSongIds.value = disliked
        persistAsync("disliked_ids", disliked)
        if (_favoriteSongIds.value.contains(song.id)) toggleFavorite(song.id)

        val state = waveState
        val track = waveTrackById[song.id]
        if (state == null || track == null) {
            next()
            return
        }
        waveState = Wave.react(state, track, fraction = 0f, disliked = true)
        persistWave()
        viewModelScope.launch {
            // The tail is swapped out before moving on, so there is always something to move on to.
            refillWave(replaceTail = true)
            next()
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
            refreshVectorHousekeeping()
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
        if (textEncoder.needsDownload(query)) {
            return AiSearchResult(
                emptyList(),
                needsModel = true,
                needsRussianModel = RuTextEncoder.handles(query)
            )
        }
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

    // ---- Carrying listening history to and from the other device -------------------------------
    //
    // The state this holds is declared above `init`, not here with its functions: Kotlin
    // initialises properties in declaration order and runs `init` among them, so a flag declared
    // below the init block that called for it is still null when it is read. That is how this
    // arrived the first time — the app would not open at all.

    /** The folder itself, rebuilt whenever the choice changes and null until one is made. */
    /** The phrase in use and what was made from it, so a key is derived once and not per pass. */
    private var sealingInUse: Pair<String, Sealing>? = null

    /**
     * What seals what this device writes and opens what it reads, or null while no phrase is set.
     *
     * Asked on every pass rather than held, because the owner can set, correct or clear the phrase
     * while the app is running — and because what is cached is the expensive part: deriving the
     * key takes two hundred thousand rounds of PBKDF2, and that must not happen twice.
     */
    private fun syncSealing(): Sealing? {
        val phrase = phrases.phrase()?.takeIf { it.isNotEmpty() } ?: return null
        sealingInUse?.let { (held, sealing) -> if (held == phrase) return sealing }
        return GcmSealing(phrase).also { sealingInUse = phrase to it }
    }

    /**
     * Sets the phrase, or clears it when given nothing.
     *
     * Trimmed at both ends, because the same phrase typed on two devices is the whole mechanism
     * and a trailing space is invisible. The pass that follows writes under the new phrase and
     * reads the folder again from the beginning: everything the other device sealed was stepped
     * over while this one could not open it.
     */
    fun setSyncPhrase(text: String) {
        val phrase = text.trim()
        if (!phrases.keep(phrase)) {
            Log.w(TAG, "The phrase could not be put in the keystore; it is in the preferences")
        }
        sealingInUse = null
        _syncSummary.value = _syncSummary.value.copy(sealed = phrase.isNotEmpty())
        viewModelScope.launch { syncPass(force = true, takeIn = true) }
    }

    /**
     * Where batches are written and read: the app's own directory when this device carries sync
     * over the network, and the folder the owner picked when something else carries it.
     *
     * One at a time, and the owner chooses. Both at once would need two of everything that
     * remembers how far a file was read, for no gain: the two transports carry the same records.
     */
    private fun syncFolder(): SyncFolder? {
        val deviceId = syncDeviceId() ?: return null
        if (prefs.getBoolean(KEY_SYNC_DIRECT, false)) {
            return FileSyncFolder({ localSyncRoot.also { if (!it.isDirectory) it.mkdirs() } }, deviceId)
        }
        val uri = prefs.getString(KEY_SYNC_FOLDER, null)?.toUri() ?: return null
        return SafSyncFolder.fromTree(getApplication(), uri, deviceId)
    }

    /**
     * Where this device's records go, as the state names it; see [SyncState.transport].
     *
     * Null when there is nowhere: no folder chosen and the network transport off. That is not the
     * same as "a folder whose path happens to be empty", and the difference matters — a device that
     * wrote a destination it has not got would record progress and outbox marks against it, and
     * every real destination afterwards would look like a change of mind.
     *
     * The folder is named and not merely called "a folder", so that choosing a *different* folder
     * counts as a change: it is an empty destination whose files share the old one's names, which
     * is the same situation as switching transports and was invisible while this said "folder".
     */
    private fun destination(): String? = SyncState.destinationOf(
        direct = prefs.getBoolean(KEY_SYNC_DIRECT, false),
        folder = prefs.getString(KEY_SYNC_FOLDER, null)
    )

    /**
     * Turns the network transport on or off.
     *
     * The coordinator is dropped rather than kept, because the folder it was built around has
     * changed; the next pass builds one against the new one and reads it from the top.
     */
    fun enableDirectSync(on: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_DIRECT, on).apply()
        syncCoordinator = null
        if (on) startPeerListener() else {
            peerPollJob?.cancel()
            peerTransport?.stop()
        }
        refreshSyncSummary()
        viewModelScope.launch { syncPass(force = true, takeIn = true) }
    }

    /** The address of the other device, for a network where broadcasts do not arrive. */
    fun setPeerAddress(text: String) {
        prefs.edit().putString(KEY_SYNC_PEER, text.trim()).apply()
        refreshSyncSummary()
        viewModelScope.launch { syncPass(force = true, takeIn = true) }
    }

    /**
     * Starts listening, if the owner asked for the network transport.
     *
     * While this ViewModel lives, which is while the app is on the screen and a little after. That
     * is the limit a phone should have: a socket that outlives the app wants a foreground service
     * and a permanent notification, and a desktop that listens all day covers the common pair
     * without either.
     */
    private fun startPeerListener() {
        if (!prefs.getBoolean(KEY_SYNC_DIRECT, false)) return
        val transport = peerTransport ?: return
        viewModelScope.launch(Dispatchers.IO) {
            transport.setPhrase(phrases.phrase())
            if (!transport.start()) Log.w(TAG, "Could not listen for the other device")
            refreshSyncSummary()
        }
        startPeerPolling()
    }

    /**
     * One exchange with whoever is reachable, which is what the folder's writer and reader would
     * otherwise be waiting on Syncthing for.
     *
     * Called twice in a pass on purpose: once before the readers run, so they see what the other
     * device has written, and once after the writers, so what this pass produced does not sit here
     * until the next one. Each is two round trips when there is nothing to move.
     */
    private suspend fun peerExchange(): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.getBoolean(KEY_SYNC_DIRECT, false)) return@withContext false
        val transport = peerTransport ?: return@withContext false
        transport.setPhrase(phrases.phrase())
        if (!transport.state.listening) transport.start()
        val outcomes = runCatching { transport.exchange(prefs.getString(KEY_SYNC_PEER, null)) }
            .onFailure { Log.e(TAG, "Could not talk to the other device", it) }
            .getOrDefault(emptyList())
        noticeVectorsTraded(outcomes)
        refreshSyncSummary()
        // True when this exchange *brought something in*, which is the thing worth coming straight
        // back for. Not "moved something": what this device handed over is no reason to look again,
        // and counting it would have two devices politely passing their own output back and forth.
        outcomes.any { it.bytesTaken > 0L || it.vectorsTaken > 0 }
    }

    /**
     * Puts the vectors a conversation moved into the card, and rebuilds what they change.
     *
     * Over a socket they no longer arrive inside a batch, so the pass that used to notice them
     * never sees them. Without this the card would say nothing was taken in while the analysis of
     * a whole library arrived, which is exactly how "it will not take any more" looked on a phone
     * that was in fact filling up.
     */
    private fun noticeVectorsTraded(outcomes: List<PeerClient.Outcome>) {
        val asked = outcomes.sumOf { it.vectorsTaken }
        val served = outcomes.sumOf { it.vectorsGiven }
        if (asked == 0 && served == 0) return
        Log.i(TAG, "Vectors by name: $asked taken in, $served handed over")
        _syncSummary.value = _syncSummary.value.let {
            it.copy(
                vectorsTakenIn = it.vectorsTakenIn + asked,
                vectorsSent = it.vectorsSent + served
            )
        }
        if (asked > 0) {
            // Tracks that have become analysed without being scanned, which is what the smart
            // albums and the playlist of the day are built from.
            refreshScannedIds()
            refreshVectorHousekeeping()
            refreshSmartAlbums()
            refreshDailyMix()
        }
    }

    /**
     * Removes the vectors files of a socket's directory tree, this device's own and the copies
     * taken from a peer.
     *
     * They are a cache for a fetch that will not happen: over a socket a peer asks by name. Left
     * where they are they would be served for ever to a peer that has to download them to find out
     * it holds every one — which is what they were. The progress through them goes with them, since
     * a line count against a file that is gone is a claim about nothing.
     */
    private fun dropPublishedVectors(state: SyncState) {
        val root = File(localSyncRoot, "aura/v1")
        var removed = 0
        var bytes = 0L
        for (device in root.listFiles().orEmpty()) {
            val vectors = File(device, VectorWriter.DIRECTORY)
            if (!vectors.isDirectory) continue
            for (file in vectors.listFiles().orEmpty()) {
                val size = file.length()
                if (file.delete()) {
                    removed++
                    bytes += size
                }
            }
            vectors.delete()
        }
        val forgotten = state.forgetVectorProgress()
        if (removed > 0 || forgotten) {
            state.save()
            Log.i(TAG, "Vectors travel by name now: dropped $removed published file(s), $bytes bytes")
        }
    }

    /**
     * Dials the other device every so often while the network transport is on.
     *
     * Needed because reachability is often one-way. A desktop behind a firewall — Fedora's default
     * zone allows ssh and mDNS and nothing else — can dial a phone perfectly well and cannot be
     * dialled or even heard announcing itself, so whichever side *can* reach the other has to try
     * on its own rather than waiting to be called. Two round trips when there is nothing to move,
     * and only while the app is open.
     */
    private fun startPeerPolling() {
        peerPollJob?.cancel()
        peerPollJob = viewModelScope.launch {
            while (isActive) {
                delay(PEER_POLL_MS)
                if (!prefs.getBoolean(KEY_SYNC_DIRECT, false)) continue
                // Guarded, because the loop is the only thing that makes sync happen at all.
                // An exception thrown anywhere in a pass used to end it for the rest of the run:
                // the app went on playing music, the port went on listening, and nothing was ever
                // carried again until somebody restarted it — which is exactly what was being
                // restarted, several times, before this was understood. A failure is worth one
                // tick, not the loop.
                try {
                    // A full pass when something arrived — reading the folder for nothing is the
                    // expensive half — or when the last pass said it had stopped short, since then
                    // what is left to read is already here and nothing else will come to fetch it.
                    if (peerExchange() || _syncSummary.value.vectorsWaiting) drainPasses()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "The pass between passes failed", e)
                }
            }
        }
    }

    private fun syncDeviceId(): String? =
        SyncIdentity.loadOrCreate(File(syncDirectory, "device-id"))

    private fun coordinator(): SyncCoordinator<Long>? {
        syncCoordinator?.let { return it }
        val deviceId = syncDeviceId() ?: return null
        // Nowhere to send and nowhere to read: no coordinator, and nothing written down about a
        // destination this device has not got.
        val transport = destination() ?: return null
        val state = SyncState.load(File(syncDirectory, "state.json")).also { syncState = it }
        // The two transports have folders of their own whose files carry the same relative paths,
        // so progress made through one says nothing about the other — and believing it would step
        // over everything the other device has written. See [SyncState.transport].
        if (state.transport != transport) {
            if (state.transport != null) {
                Log.i(TAG, "Records now go to $transport; reading it again from the top")
            }
            state.startOutboxAgain()
            state.transport = transport
            state.save()
        }
        // A folder that has never had this device's vectors gets them once. Not on every change —
        // eleven megabytes for this library — but a folder that never receives them can never hand
        // them back, which is most of what a folder is for.
        //
        // A socket needs none of that. There is somebody on the other end to ask, so a device says
        // which tracks it is missing and is served those by name, and the copy a folder has to hold
        // is held instead by the peer's own store. See [PeerRecords].
        if (transport == SyncState.DIRECT) {
            dropPublishedVectors(state)
        } else if (state.fillVectorsFor(transport)) {
            viewModelScope.launch {
                val waiting = embeddings.offerEverythingAgain()
                Log.i(TAG, "Vectors have not been to $transport yet: offering $waiting again")
                state.save()
                syncPass(force = true, takeIn = false, allVectors = true)
            }
        }
        return SyncCoordinator(
            deviceId = deviceId,
            state = state,
            store = PlayHistoryStore(playHistory, { allSongs }, { soundPrints.all() }),
            folder = { syncFolder() },
            keepDays = PlayHistory.KEEP_DAYS,
            note = { Log.i(TAG, it) },
            vectors = EmbeddingVectorStore(embeddings, modelRepository, soundPrints) { allSongs },
            playlists = PlaylistShelf(
                songs = { allSongs },
                favouriteIds = { _favoriteSongIds.value },
                storedPlaylists = { savedPlaylists.map { it.name to it.songs.map { song -> song.id } } },
                onShow = { favourites, playlists -> showMergedPlaylists(favourites, playlists) }
            ),
            playlistLog = playlistLog,
            sealing = { syncSealing() },
            mix = MixShelf(
                builder = dailyMixBuilder,
                songs = { allSongs },
                onShow = { songs -> withContext(Dispatchers.Main) { _dailyMix.value = songs } }
            )
        ).also { syncCoordinator = it }
    }

    /**
     * The two moments worth reacting to.
     *
     * Every finished listen is offered to the outbox, which writes only when it judges it worth
     * touching the folder — a couple of times an hour rather than once a track, because each write
     * is a radio wake-up on a phone. And when playback stops, whatever is waiting goes out at
     * once: that is the end of a listening session, and the next flush might otherwise be a long
     * way off.
     */
    private fun observeSync() {
        viewModelScope.launch {
            playHistory.events.collect { syncPass(force = false, takeIn = false) }
        }
        viewModelScope.launch {
            isPlaying.collect { playing ->
                // The end of a listening session: say what was heard, and take in what the other
                // device has said since, in time for the next wave to steer on it.
                if (!playing) syncPass(force = true, takeIn = true, throttled = true)
            }
        }
        viewModelScope.launch {
            // The analysis finishing is the one moment there is certainly something new to offer,
            // and the only one that does not depend on somebody touching the app. Without it an
            // hour of analysis sits unshared until the owner happens to come back to the screen.
            var wasScanning = false
            isAiScanning.collect { scanning ->
                // Whether it ran out of tracks or the owner stopped it, everything analysed goes
                // out as one piece. Five hundred at a time is right for a pass nobody asked for;
                // here the work has just been done, the device is already busy, and holding the
                // rest back until somebody happens to open the app is what makes this feel jerky.
                if (wasScanning && !scanning) {
                    syncPass(force = true, takeIn = true, allVectors = true)
                }
                // And the moment it starts, because the other device may already hold what this
                // one is about to spend an hour computing. The scan waits for whatever is on its
                // way; see AiScanner.letVectorsArrive.
                if (!wasScanning && scanning) {
                    syncPass(force = true, takeIn = true)
                }
                wasScanning = scanning
            }
        }
        viewModelScope.launch {
            // While the app is in front of the owner, the folder is read every so often.
            //
            // Every other moment that reads it is something the owner did — stopped playback, came
            // back to the app — and there is one case none of them covers: looking at the screen.
            // A playlist open on this device while it is changed on the other would sit there
            // unchanged, because watching it causes nothing. Found exactly that way.
            //
            // Only in the foreground, so a phone in a pocket does nothing. The interval is the
            // governor rather than the throttle, which is why these passes are not throttled.
            foreground.collectLatest { inFront ->
                while (inFront) {
                    delay(FOREGROUND_POLL_MS)
                    try {
                        drainPasses()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // See the loop between passes: a failure costs a tick, not the loop.
                        Log.e(TAG, "A pass while the app is in front failed", e)
                    }
                }
            }
        }
        refreshSyncSummary()
    }

    /**
     * One pass over the folder: what is waiting goes out, and what has arrived comes in.
     *
     * Behind a lock, because the moments that ask for a pass can arrive together — a listen
     * finishing is also often playback stopping — and two passes at once would read the same files
     * twice and write the state from under each other.
     *
     * @param takeIn false for a pass that only has something to say. A listen finishing is one of
     *   those: it comes every few minutes, and reading the whole folder each time would be work
     *   for nothing.
     * @param allVectors for the end of an analysis: everything just scanned goes out at once
     *   instead of five hundred at a time. See [SyncCoordinator.pushVectors].
     * @param throttled for the passes a person causes without meaning to — pausing, coming back to
     *   the app — which arrive in flurries. See [PULL_THROTTLE_MS]. A pass the owner asked for,
     *   and the one after a scan, are never throttled.
     */
    private suspend fun syncPass(
        force: Boolean,
        takeIn: Boolean,
        throttled: Boolean = false,
        allVectors: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val coordinator = coordinator() ?: return@withContext false
        syncGate.withLock {
            // Before the readers: bring in whatever the other device has written since.
            // Kept, because an exchange that brought vectors in is the clearest sign there are
            // more behind them. Vectors no longer arrive inside a batch, so the reader's own "more
            // waiting" says nothing about them, and without this the next conversation waited for
            // the timer between passes — a library came over in batches with half-minute silences
            // in between, and whoever was watching restarted the app to hurry it along.
            var broughtIn = if (takeIn) peerExchange() else false
            val now = System.currentTimeMillis()
            val sent = runCatching { coordinator.push(now, force).written }
                .getOrElse { Log.e(TAG, "Could not write the history out", it); 0 }
            // A pass with nothing to take in happens often — a listen finishing is one — and
            // reading the whole folder each time would be work for nothing. A pass before the
            // library is scanned is turned away by the coordinator itself.
            val tooSoon = throttled && now - lastPullAt < PULL_THROTTLE_MS
            val pulled = if (!takeIn || tooSoon) null else
                // Marked as read only when the folder really was read: the coordinator turns a
                // pass away when there is no library yet, and counting that as a read would make
                // the next real one wait out the throttle for nothing.
                runCatching { coordinator.pull(now).also { if (it.read != null) lastPullAt = now } }
                    .getOrElse { Log.e(TAG, "Could not read the history in", it); null }

            // Vectors on the same moments as a full pass, and never on a finished listen: there is
            // nothing new to publish then, and reading a library's worth of them would be work for
            // nothing. During a backfill this is the expensive part of a pass — a few hundred file
            // reads to name their excerpts — and in the steady state it costs one empty query.
            //
            // A pass asked for every vector is such a moment by itself, even when it takes nothing
            // in. The first pass to a new destination is exactly that — hand it the library, and do
            // not read a folder that has only just been named — and gating it on having read
            // something first made it a pass that published no vectors at all, which was the whole
            // of what it was for. Found by a phone whose analysis the desktop could not hand back.
            // Nothing is published to a socket: a peer asks for the tracks it is missing and is
            // served them by name, so a library's worth written into a day file is seventeen
            // megabytes nobody will ever fetch. Measured before this was true: a phone whose
            // analysis had been cleared three times had written 59 197 708 bytes of vectors for a
            // desktop that held every one of them already.
            val sentVectors = if (prefs.getBoolean(KEY_SYNC_DIRECT, false) ||
                (pulled == null && !allVectors)
            ) null else
                runCatching {
                    // Without a limit when it is not the end of an analysis, so the coordinator's
                    // own choice stands: naming the default here would quietly override whatever a
                    // platform decided for itself.
                    if (allVectors) coordinator.pushVectors(now, SyncCoordinator.PUBLISH_EVERYTHING)
                    else coordinator.pushVectors(now)
                }.getOrElse { Log.e(TAG, "Could not write the vectors out", it); null }
            val tookVectors = if (pulled == null) null else
                runCatching { coordinator.pullVectors(now) }
                    .getOrElse { Log.e(TAG, "Could not read the vectors in", it); null }
            sentVectors?.let {
                if (it.written + it.couldNotTravel + it.gone > 0) {
                    Log.i(
                        TAG,
                        "Vectors out: ${it.written} written, ${it.couldNotTravel} cannot travel, " +
                            "${it.gone} with nothing to name them by"
                    )
                }
            }
            // The whole outcome, not only the refusals: "nothing was refused" says very little on
            // its own, since a vector for a track already analysed here never gets as far as being
            // judged. These four numbers are what a pass actually did.
            tookVectors?.let {
                val seen = it.takenIn + it.alreadyHeld + it.unmatched + it.refused.values.sum()
                if (seen > 0) {
                    Log.i(
                        TAG,
                        "Vectors: $seen seen, ${it.takenIn} taken in, ${it.alreadyHeld} already " +
                            "held, ${it.unmatched} for tracks we lack, refused ${it.refused}" +
                            if (it.more) ", more waiting" else ""
                    )
                }
            }

            // On every pass, with no condition at all. A change the owner made has to be able
            // to go out on its own, and when there is nothing to send this costs a lookup: the
            // outbox is asked for its own changes past a watermark and answers with none. Tying it
            // to the kinds of pass that also read the folder was wrong twice over — it left a
            // hearted track sitting until the app was next opened, and then left it sitting
            // through the opening too, because coming back to the app forces nothing.
            val sentChanges =
                runCatching { coordinator.pushPlaylists(now) }
                    .getOrElse { Log.e(TAG, "Could not write the playlist changes out", it); null }
            val tookChanges = if (pulled == null) null else
                runCatching { coordinator.pullPlaylists(now) }
                    .getOrElse { Log.e(TAG, "Could not read the playlist changes in", it); null }
            sentChanges?.let {
                if (it.written + it.seeded > 0) {
                    Log.i(TAG, "Playlists out: ${it.written} written, ${it.seeded} seeded from what was here")
                }
            }
            tookChanges?.let {
                if (it.takenIn > 0 || it.unmatched > 0) {
                    Log.i(
                        TAG,
                        "Playlists in: ${it.takenIn} changes, ${it.favourites} favourites, " +
                            "${it.playlists} playlists, ${it.unmatched} tracks not here"
                    )
                }
            }

            // The playlist of the day, taken in before it is offered: a device that is about to
            // adopt the other's mix should not first publish one of its own that is going to lose.
            val tookMix = if (pulled == null) null else
                runCatching { coordinator.pullMix(now) }
                    .getOrElse { Log.e(TAG, "Could not read the playlist of the day in", it); null }
            val sentMix =
                runCatching { coordinator.pushMix(now) }
                    .getOrElse { Log.e(TAG, "Could not write the playlist of the day out", it); null }
            tookMix?.let {
                if (it.adopted || it.unmatched > 0) {
                    Log.i(
                        TAG,
                        "Daily mix in: ${if (it.adopted) "adopted" else "kept ours"} for ${it.day}, " +
                            "${it.tracks} tracks here, ${it.unmatched} not"
                    )
                }
            }
            sentMix?.let {
                if (it.written > 0) Log.i(TAG, "Daily mix out: ${it.day}, attempt ${it.attempt}")
            }

            // After the writers: hand over what this pass produced, rather than leaving it here
            // until the next one. Nothing to do when the folder transport is in use.
            broughtIn = peerExchange() || broughtIn

            _syncSummary.value = _syncSummary.value.let {
                it.copy(
                    // Totals since this run started, not of the last pass: most passes happen on
                    // their own now, and a card that read "sent 0" after each of them would say
                    // nothing was working.
                    sent = it.sent + sent,
                    takenIn = it.takenIn + (pulled?.takenIn ?: 0),
                    waiting = pulled?.waiting ?: it.waiting,
                    vectorsSent = it.vectorsSent + (sentVectors?.written ?: 0),
                    vectorsTakenIn = it.vectorsTakenIn + (tookVectors?.takenIn ?: 0),
                    vectorsWaiting = tookVectors?.more ?: it.vectorsWaiting,
                    changesSent = it.changesSent + (sentChanges?.written ?: 0),
                    changesTakenIn = it.changesTakenIn + (tookChanges?.takenIn ?: 0),
                    mixSent = it.mixSent + (sentMix?.written ?: 0),
                    mixTakenIn = it.mixTakenIn + (if (tookMix?.adopted == true) 1 else 0),
                    unopened = coordinator.unopenedBatches,
                    lastRunAt = now
                ).withTransport()
            }
            // Listens taken in change what counts as recently played, which the daily mix uses.
            if ((pulled?.takenIn ?: 0) > 0) refreshDailyMix()
            // Vectors taken in are tracks that have become analysed without being scanned, which
            // is what the smart albums and the daily mix are built from.
            if ((tookVectors?.takenIn ?: 0) > 0) {
                refreshScannedIds()
                refreshSmartAlbums()
                refreshDailyMix()
                // These are the numbers the AI card shows, and nothing but what the owner did here
                // used to recompute them: while the desktop handed a cleared phone its analysis
                // back, the card sat at the count from the moment the window was opened — 491 of
                // them — which read as "it will not take any more".
                refreshVectorHousekeeping()
            }
            // A vector that arrived may be the answer for another copy of the same sound, and
            // this is the only moment that can be known: the pass that takes vectors in is not the
            // pass that read the library. Without it the copies waited for a restart — measured
            // here, a machine restored 4351 of 4548 and stopped, and the 197 left were almost
            // exactly its own duplicate copies.
            if (fillTwinVectors() > 0) {
                refreshScannedIds()
                refreshVectorHousekeeping()
                refreshSmartAlbums()
                refreshDailyMix()
            }
            // True when a reader stopped short of the end: it takes in only so much at a time
            // and says so. Whoever drives the passes can then come straight back instead of
            // waiting for the next arrival — see [drainPasses].
            //
            // It has to have got through something as well. A pass that reports more and sees
            // nothing must not become a loop.
            tookVectors?.let { it.more && it.takenIn + it.alreadyHeld + it.unmatched > 0 } == true ||
                broughtIn
        }
    }

    /**
     * Passes until nothing is left waiting.
     *
     * A reader takes in only so much at a time and says when it stopped short, and leaving the
     * rest for the next pass is right as long as passes keep coming. The pass that *brought* the
     * file is often the last one for a while, though: nothing more arrives, so nothing more is
     * read, and the device sits on a complete file with part of it unread. Found exactly that way
     * — a phone holding all seventeen megabytes of the desktop's vectors with sixty of the
     * ninety-one batches read, and no reason left to look at the rest.
     *
     * Bounded, and only while the passes are getting through it.
     */
    private suspend fun drainPasses(limit: Int = 500) {
        var passes = 0
        while (passes++ < limit && syncPass(force = false, takeIn = true)) { /* more waiting */ }
    }

    /** Takes the folder the owner picked, for good: the grant has to outlive this process. */
    fun setSyncFolder(uri: Uri?) {
        if (uri == null) return
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not keep the permission for the sync folder", e)
            return
        }
        prefs.edit().putString(KEY_SYNC_FOLDER, uri.toString()).apply()
        syncCoordinator = null
        refreshSyncSummary()
        syncNow()
    }

    fun forgetSyncFolder() {
        prefs.getString(KEY_SYNC_FOLDER, null)?.toUri()?.let { uri ->
            runCatching {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit().remove(KEY_SYNC_FOLDER).apply()
        syncCoordinator = null
        refreshSyncSummary()
    }

    /**
     * The app came to the front. Read the folder, so that what the other device heard is already
     * counted by the time the owner starts a wave or opens a playlist — which is the whole of why
     * this moment is worth reacting to. Nothing is forced out: coming back to the app says nothing
     * new about what was played.
     */
    fun syncOnOpen() {
        foreground.value = true
        viewModelScope.launch { syncPass(force = false, takeIn = true, throttled = true) }
    }

    /** The app is no longer in front of the owner, so the slow reading of the folder stops. */
    fun syncOnLeave() {
        foreground.value = false
    }

    /** A pass the owner asked for, which is the same pass with a spinner on it. */
    fun syncNow() {
        if (_syncSummary.value.busy) return
        _syncSummary.value = _syncSummary.value.copy(busy = true)
        viewModelScope.launch {
            syncPass(force = true, takeIn = true)
            _syncSummary.value = _syncSummary.value.copy(busy = false)
        }
    }

    /**
     * Fills in what the network transport is doing, so the two places that build a summary say the
     * same thing about it.
     */
    private fun SyncSummary.withTransport(): SyncSummary {
        val direct = prefs.getBoolean(KEY_SYNC_DIRECT, false)
        val state = peerTransport?.state
        return copy(
            direct = direct,
            listening = direct && state?.listening == true,
            port = state?.port ?: -1,
            addresses = if (direct) state?.addresses.orEmpty() else emptyList(),
            peers = if (!direct) emptyList() else state?.peers.orEmpty().map {
                "${it.deviceId.take(8)} · ${it.address}"
            },
            peerTrouble = if (!direct) null else state?.trouble ?: state?.last?.trouble,
            peerAddressInUse = if (direct) prefs.getString(KEY_SYNC_PEER, null).orEmpty() else "",
            served = state?.served ?: 0,
            tunnelled = direct && state?.tunnelled == true
        )
    }

    private fun refreshSyncSummary() {
        val uri = prefs.getString(KEY_SYNC_FOLDER, null)
        _syncSummary.value = _syncSummary.value.withTransport().copy(
            folder = uri?.let { readableFolderName(it) },
            deviceId = if (uri == null) "" else syncDeviceId().orEmpty().take(8),
            sealed = !phrases.phrase().isNullOrEmpty(),
            phraseKept = phrases.where
        )
    }

    /**
     * Something a person can recognise out of a document tree uri, whose own form is
     * `content://…/tree/primary%3ASync%2Fmusic`.
     */
    private fun readableFolderName(uri: String): String {
        val tail = uri.toUri().lastPathSegment ?: return uri
        return Uri.decode(tail).substringAfterLast(':').ifEmpty { Uri.decode(tail) }
    }

    override fun onCleared() {
        // The listener goes with the app on a phone, deliberately: see [startPeerListener].
        peerPollJob?.cancel()
        peerTransport?.stop()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        stopProgressUpdate()
        textEncoder.release()
        super.onCleared()
    }

    // Not private only so that the instrumented test can ask for the same preference key the app
    // writes under, rather than repeat the string and drift from it.
    internal companion object {
        const val TAG = "MusicViewModel"
        const val PROGRESS_INTERVAL_MS = 500L
        const val GLOBAL_PLAIN_RESULTS = 20
        const val PERSIST_DEBOUNCE_MS = 400L
        const val SCAN_REFRESH_EVERY = 5

        /** Tracks kept queued behind the one playing while a wave runs. */
        const val QUEUE_AHEAD = 4

        /** Below this much of a track, the wave treats it as a rejection and rebuilds its queue. */
        const val SKIP_FRACTION = 0.3f

        /** Fewer analysed tracks than this and a wave would circle immediately. */
        const val MIN_WAVE_LIBRARY = 40

        /**
         * How long a saved wave is worth resuming.
         *
         * A wave is about the moment. Coming back to the app the same evening should carry on where
         * it stopped; coming back two days later should not resurrect a direction that was steered
         * by a mood nobody remembers.
         */
        const val WAVE_MAX_AGE_MS = 12L * 60 * 60 * 1000
        const val KEY_SMART_ALBUMS_EPS_SCALE = "smart_albums_eps_scale"
        const val KEY_WAVE_NOVELTY = "wave_novelty"
        const val KEY_SYNC_FOLDER = "sync_folder"

        /**
         * The phrase the folder is sealed under.
         *
         * In the app's own preferences, which no other app can read. The Android keystore would
         * be better — hardware-backed and non-exportable — but the phrase itself has to be kept,
         * not only a key made from it: a key is derived per device id, so meeting a new device
         * means deriving a new key from the phrase again. That trade belongs with pairing, when a
         * key is exchanged between devices instead of typed by a person.
         */

        /** Whether this device carries sync itself over the network; see [PeerTransport]. */
        const val KEY_SYNC_DIRECT = "sync_direct"

        /** An address typed by the owner, for a network that does not carry broadcasts. */
        const val KEY_SYNC_PEER = "sync_peer_address"

        /**
         * How often this device dials the other while the network transport is on.
         *
         * Half a minute: a beacon stays fresh for one, so this also keeps this device findable,
         * and it is the cadence at which a listen made on one device shows up on the other.
         */
        const val PEER_POLL_MS = 30_000L

        /**
         * How soon after one read the folder may be read again by a pass nobody asked for.
         *
         * The moments that ask for a read are moments a person creates — a pause, a glance at
         * another app and back — and they arrive in flurries. Reading is cheap but not free: on a
         * phone it is storage access framework calls and, with the folder on a share, a wake-up. A
         * minute is the shortest gap worth having, and nothing a person is waiting for is behind
         * it: what they will look at is already in the database from the read a moment ago.
         */
        const val PULL_THROTTLE_MS = 60_000L

        /**
         * How long a playlist change waits for others to join it before being sent.
         *
         * Short, because the owner is standing there: hearting a track and switching to the other
         * device is a thing people do in one movement. Long enough that a dozen hearts in a row are
         * one write.
         */
        const val PLAYLIST_SETTLE_MS = 5_000L

        /**
         * How often the folder is read while the app is in front of the owner.
         *
         * Fifteen seconds is the compromise: a change made on the other device shows up while the
         * owner is still looking at the screen, and a phone with the app open does four folder
         * reads a minute — which through the storage access framework is a directory listing and,
         * when something has changed, one small file. Nothing at all when the app is away.
         */
        const val FOREGROUND_POLL_MS = 15_000L

        /** Below this a track is not a match for the query under any reading. */
        const val MIN_SEARCH_SIMILARITY = 0.15f

        /** Where a match is unambiguous, and the displayed score saturates. */
        const val STRONG_SIMILARITY = 0.45f
    }
}
