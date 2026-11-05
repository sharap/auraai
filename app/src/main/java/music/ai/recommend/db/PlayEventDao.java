package music.ai.recommend.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PlayEventDao {
    @Insert
    void insert(PlayEventEntity event);

    /** Several at once, which is how listens arrive from another device: one write, not twenty. */
    @Insert
    void insertAll(List<PlayEventEntity> events);

    /** Newest first; the taste profile weights recent listens more heavily. */
    @Query("SELECT * FROM play_events WHERE playedAt >= :since ORDER BY playedAt DESC")
    List<PlayEventEntity> since(long since);

    /**
     * Last time each song was played, for keeping the daily playlist off repeats. Every device's
     * listens count: a track heard on the phone this morning is not new to the desktop tonight.
     */
    @Query("SELECT songId, MAX(playedAt) AS playedAt, 0 AS id, 0 AS playedMs, 0 AS durationMs,"
            + " '' AS deviceId FROM play_events GROUP BY songId")
    List<PlayEventEntity> lastPlayedPerSong();

    /**
     * This device's own listens that have not been written to the sync folder yet, oldest first.
     *
     * <p>By id rather than by a flag on the row: ids rise with insertion, so one number remembers
     * where the outbox got to, and a batch that was prepared but never written is simply offered
     * again on the next run.
     */
    @Query("SELECT * FROM play_events WHERE deviceId = '' AND id > :afterId ORDER BY id LIMIT :limit")
    List<PlayEventEntity> notYetExported(long afterId, int limit);

    /** The highest id in the table, so a fresh outbox can start from the present. */
    @Query("SELECT IFNULL(MAX(id), 0) FROM play_events")
    long newestId();

    /** Listens taken in from other devices, which is what the merge needs to recognise its own. */
    @Query("SELECT * FROM play_events WHERE deviceId != '' AND playedAt >= :since")
    List<PlayEventEntity> fromOtherDevicesSince(long since);

    @Query("DELETE FROM play_events WHERE playedAt < :before")
    void deleteOlderThan(long before);

    /** Forgets everything one device told us, for when it is no longer one of ours. */
    @Query("DELETE FROM play_events WHERE deviceId = :deviceId")
    void deleteFromDevice(String deviceId);

    @Query("SELECT COUNT(*) FROM play_events")
    int count();

    @Query("DELETE FROM play_events")
    void deleteAll();
}
