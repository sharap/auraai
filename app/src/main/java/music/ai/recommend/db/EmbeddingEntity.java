package music.ai.recommend.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * One CLAP audio embedding (512 floats), stored as a little-endian float BLOB.
 *
 * <p>Version 1 kept the vector as a Gson-encoded JSON array, which cost roughly 5.6 KB and a full
 * JSON parse per song every time the table was read — and the table is read on every keystroke of
 * AI search, every track change under AI shuffle, and every "play similar". The BLOB is 2 KB and
 * needs no parsing.
 */
@Entity(tableName = "embeddings")
public class EmbeddingEntity {
    @PrimaryKey
    public long songId;
    public byte[] vector;

    /**
     * When this vector was offered to the other devices, or 0 for one that has not been.
     *
     * Here rather than in a table of its own because it belongs to the row it describes and goes
     * when the row goes: an analysis version change throws every vector away, and the question of
     * whether the old ones were published goes with them.
     *
     * Set for vectors that could not travel at all — a format with no shared decoder — as well as
     * for ones that did. The answer for those will not change while the analysis stays the same,
     * and reconsidering them on every pass would be a full table scan for nothing.
     */
    public long publishedAt;

    public EmbeddingEntity(long songId, byte[] vector) {
        this.songId = songId;
        this.vector = vector;
    }
}
