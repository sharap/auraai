package music.ai.recommend.db;

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

    public PlayEventEntity(long songId, long playedAt, long playedMs, long durationMs) {
        this.songId = songId;
        this.playedAt = playedAt;
        this.playedMs = playedMs;
        this.durationMs = durationMs;
    }
}
