package music.ai.recommend.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface EmbeddingDao {
    @Query("SELECT * FROM embeddings")
    List<EmbeddingEntity> getAll();

    /**
     * Ids only. Callers that just need to know which songs are analysed (the sparkle badges, the
     * scan progress counter) should use this instead of pulling every vector off disk.
     */
    @Query("SELECT songId FROM embeddings")
    List<Long> getAllIds();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(EmbeddingEntity embedding);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<EmbeddingEntity> embeddings);

    @Query("SELECT COUNT(*) FROM embeddings")
    int getCount();

    /**
     * Vectors not yet offered to the other devices, oldest row first.
     *
     * Ordered by songId only so that the answer is stable: a pass that keeps being interrupted
     * should keep making progress through the library rather than revisiting the same corner.
     */
    @Query("SELECT * FROM embeddings WHERE publishedAt = 0 ORDER BY songId LIMIT :limit")
    List<EmbeddingEntity> notPublished(int limit);

    @Query("UPDATE embeddings SET publishedAt = :at WHERE songId IN (:songIds)")
    void markPublished(List<Long> songIds, long at);

    @Query("SELECT COUNT(*) FROM embeddings WHERE publishedAt = 0")
    int countNotPublished();

    @Query("DELETE FROM embeddings")
    void deleteAll();

    @Query("DELETE FROM embeddings WHERE songId IN (:songIds)")
    void delete(List<Long> songIds);
}
