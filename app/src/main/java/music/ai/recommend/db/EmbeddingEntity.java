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

    public EmbeddingEntity(long songId, byte[] vector) {
        this.songId = songId;
        this.vector = vector;
    }
}
