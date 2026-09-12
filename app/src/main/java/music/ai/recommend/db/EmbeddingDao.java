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

    @Query("DELETE FROM embeddings")
    void deleteAll();
}
