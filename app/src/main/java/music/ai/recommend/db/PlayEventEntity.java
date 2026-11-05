package music.ai.recommend.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One finished listen: how far into a track playback actually got.
 *
 * <p>Kept as raw events rather than a play counter because what the daily playlist needs is the
 * difference between "played to the end" and "skipped after four seconds" — the same row count
 * either way, but a counter cannot tell those apart afterwards.
 */
/*
 * The index is declared here and not only in the migration: Room compares the whole schema,
 * including indices, and refuses to open a database that carries an index the entity does not
 * declare — which takes the embeddings table down with it.
 */
@Entity(tableName = "play_events", indices = {@Index("playedAt")})
public class PlayEventEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long songId;

    /** When the listen ended, in epoch milliseconds. */
    public long playedAt;

    /** Furthest position reached, in milliseconds; pauses and repeats do not add to it. */
    public long playedMs;

    /** Track length in milliseconds, or 0 when the player never reported one. */
    public long durationMs;

    /**
     * Which device heard it: empty for this one, and a sync device id for a listen that arrived
     * from elsewhere.
     *
     * <p>The distinction is not for display. A device must export only its own listens, or the
     * ones it took in would go back out under its name and a third device would count them twice;
     * and it is what lets the merge recognise a batch it has already read. Rows written before
     * sync existed are this device's by default, which is what they are.
     */
    @NonNull
    public String deviceId;

    public PlayEventEntity(long songId, long playedAt, long playedMs, long durationMs,
                           @NonNull String deviceId) {
        this.songId = songId;
        this.playedAt = playedAt;
        this.playedMs = playedMs;
        this.durationMs = durationMs;
        this.deviceId = deviceId;
    }
}
