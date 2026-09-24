package music.ai.recommend.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PlayEventDao {
    @Insert
    void insert(PlayEventEntity event);

    /** Newest first; the taste profile weights recent listens more heavily. */
    @Query("SELECT * FROM play_events WHERE playedAt >= :since ORDER BY playedAt DESC")
    List<PlayEventEntity> since(long since);

    /** Last time each song was played, for keeping the daily playlist off repeats. */
    @Query("SELECT songId, MAX(playedAt) AS playedAt, 0 AS id, 0 AS playedMs, 0 AS durationMs"
            + " FROM play_events GROUP BY songId")
    List<PlayEventEntity> lastPlayedPerSong();

    @Query("DELETE FROM play_events WHERE playedAt < :before")
    void deleteOlderThan(long before);

    @Query("SELECT COUNT(*) FROM play_events")
    int count();

    @Query("DELETE FROM play_events")
    void deleteAll();
}
