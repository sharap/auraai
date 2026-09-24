package music.ai.recommend.history

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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

    suspend fun record(event: PlayEvent) = withContext(Dispatchers.IO) {
        try {
            dao.insert(PlayEventEntity(event.songId, event.playedAt, event.playedMs, event.durationMs))
        } catch (e: Exception) {
            Log.e(TAG, "Could not record a listen", e)
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
