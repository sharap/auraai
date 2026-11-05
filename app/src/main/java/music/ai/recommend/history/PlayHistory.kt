package music.ai.recommend.history

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import music.ai.recommend.db.AppDatabase
import music.ai.recommend.db.PlayEventDao
import music.ai.recommend.db.PlayEventEntity

/** One finished listen. [fraction] is how much of the track was reached, 0..1. */
data class PlayEvent(
    val songId: Long,
    val playedAt: Long,
    val playedMs: Long,
    val durationMs: Long
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (playedMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * The listening history, written by the playback service and read when the daily playlist is
 * built. Everything stays on the device; nothing here is ever sent anywhere.
 */
class PlayHistory private constructor(context: Context) {

    private val dao: PlayEventDao = AppDatabase.getDatabase(context.applicationContext).playEventDao()

    private val _events = MutableSharedFlow<PlayEvent>(extraBufferCapacity = 16)

    /**
     * Listens as they finish, for anything that has to react at once rather than read the history
     * later — the wave steers on these. The playback service and the UI live in the same process,
     * so this is a direct hand-off and not a trip through the database.
     */
    val events: SharedFlow<PlayEvent> = _events.asSharedFlow()

    /**
     * Hands the event to whoever is listening now, and keeps it if it was long enough to be a
     * listen. A track left after a second says plenty about the moment — the wave reacts to it —
     * but it is the player settling as often as it is a verdict, so the history does not keep it.
     */
    suspend fun record(event: PlayEvent): Unit = withContext(Dispatchers.IO) {
        _events.tryEmit(event)
        if (event.playedMs < MIN_LISTEN_MS) return@withContext
        try {
            dao.insert(PlayEventEntity(event.songId, event.playedAt, event.playedMs, event.durationMs, ""))
        } catch (e: Exception) {
            Log.e(TAG, "Could not record a listen", e)
        }
    }

    /**
     * Listens heard on another device, already matched to tracks in this library.
     *
     * Kept apart from [record] on purpose: these do not go to [events], because the wave steers on
     * what is playing here and now, and a listen from this morning's commute is history rather
     * than a verdict. They are written in one go, since they arrive in batches.
     */
    suspend fun recordFromOtherDevice(deviceId: String, events: List<PlayEvent>): Int =
        withContext(Dispatchers.IO) {
            if (deviceId.isEmpty() || events.isEmpty()) return@withContext 0
            try {
                dao.insertAll(events.map {
                    PlayEventEntity(it.songId, it.playedAt, it.playedMs, it.durationMs, deviceId)
                })
                events.size
            } catch (e: Exception) {
                Log.e(TAG, "Could not take in ${events.size} listens from $deviceId", e)
                0
            }
        }

    /** One of this device's own listens, with the row id that says how far the outbox has got. */
    data class Exportable(val rowId: Long, val event: PlayEvent)

    /** This device's own listens past [afterId], oldest first, for the sync outbox. */
    suspend fun notYetExported(afterId: Long, limit: Int): List<Exportable> =
        withContext(Dispatchers.IO) {
            try {
                dao.notYetExported(afterId, limit).map {
                    Exportable(it.id, PlayEvent(it.songId, it.playedAt, it.playedMs, it.durationMs))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not read what is left to export", e)
                emptyList()
            }
        }

    /** The newest row id, so an outbox starting for the first time starts from the present. */
    suspend fun newestEventId(): Long = withContext(Dispatchers.IO) {
        try {
            dao.newestId()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Which listens from other devices this one already holds, as device and instant — what the
     * merge turns away so that reading a file twice costs nothing.
     */
    suspend fun fromOtherDevices(days: Int = KEEP_DAYS, now: Long = System.currentTimeMillis()):
        List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        try {
            dao.fromOtherDevicesSince(now - days * DAY_MS).map { it.deviceId to it.playedAt }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read what came from elsewhere", e)
            emptyList()
        }
    }

    /** Events from the last [days] days, newest first. */
    suspend fun recent(days: Int = PROFILE_DAYS, now: Long = System.currentTimeMillis()): List<PlayEvent> =
        withContext(Dispatchers.IO) {
            try {
                dao.since(now - days * DAY_MS).map {
                    PlayEvent(it.songId, it.playedAt, it.playedMs, it.durationMs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not read the history", e)
                emptyList()
            }
        }

    /** When each song was last played, for keeping the daily playlist off recent repeats. */
    suspend fun lastPlayed(): Map<Long, Long> = withContext(Dispatchers.IO) {
        try {
            dao.lastPlayedPerSong().associate { it.songId to it.playedAt }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read the history", e)
            emptyMap()
        }
    }

    /** Drops events older than [KEEP_DAYS]; they no longer carry weight in the profile. */
    suspend fun prune(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        try {
            dao.deleteOlderThan(now - KEEP_DAYS * DAY_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Could not prune the history", e)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            dao.deleteAll()
        } catch (e: Exception) {
            Log.e(TAG, "Could not clear the history", e)
        }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        try {
            dao.count()
        } catch (e: Exception) {
            0
        }
    }

    companion object {
        private const val TAG = "PlayHistory"
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** Shorter than this is not kept as a listen; see [record]. */
        const val MIN_LISTEN_MS = 3_000L

        /** How far back the taste profile looks. */
        const val PROFILE_DAYS = 90

        /** How long events are kept at all. */
        const val KEEP_DAYS = 365

        @Volatile
        private var instance: PlayHistory? = null

        fun getInstance(context: Context): PlayHistory =
            instance ?: synchronized(this) {
                instance ?: PlayHistory(context).also { instance = it }
            }
    }
}
