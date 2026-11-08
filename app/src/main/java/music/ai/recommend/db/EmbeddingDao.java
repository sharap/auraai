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
    @Query("SELECT songId FROM embeddings WHERE refusedStamp IS NULL")
    List<Long> getAllIds();

    /**
     * The tracks that were tried and could not be read; see {@link EmbeddingEntity#refusedStamp}.
     *
     * Kept apart from {@link #getAllIds()} rather than folded into it, because the two answer
     * different questions. A refused track is not analysed — it has no vector, the badge in the
     * list must not claim one, and a vector arriving from the other device for it is news rather
     * than a duplicate. What it is, is dealt with, and only this says so.
     */
    @Query("SELECT songId, refusedStamp AS stamp FROM embeddings WHERE refusedStamp IS NOT NULL")
    List<Refusal> getRefusals();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(EmbeddingEntity embedding);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<EmbeddingEntity> embeddings);

    @Query("SELECT COUNT(*) FROM embeddings")
    int getCount();

    /** How many files the analysis could not read. The settings screen says so, so they can be fixed. */
    @Query("SELECT COUNT(*) FROM embeddings WHERE refusedStamp IS NOT NULL")
    int countRefused();

    /**
     * Vectors not yet offered to the other devices, oldest row first.
     *
     * Refusals are left out, and that is not an optimisation. "I could not read this file" is a
     * verdict about one device's decoders; the device at the other end may read the format fine,
     * and if it took our refusal for a vector it would mark the track analysed and never look at
     * it again.
     *
     * Ordered by songId only so that the answer is stable: a pass that keeps being interrupted
     * should keep making progress through the library rather than revisiting the same corner.
     */
    @Query("SELECT * FROM embeddings WHERE publishedAt = 0 AND refusedStamp IS NULL ORDER BY songId LIMIT :limit")
    List<EmbeddingEntity> notPublished(int limit);

    @Query("UPDATE embeddings SET publishedAt = :at WHERE songId IN (:songIds)")
    void markPublished(List<Long> songIds, long at);

    /**
     * Offers every vector to the other devices again.
     *
     * Run when this device's records start going somewhere they have never been — a folder just
     * chosen, or the socket's own directory the first time it is used. The marks say "already
     * offered", which was true of the *previous* destination and says nothing about this one, and a
     * destination that never receives them can never hand them back.
     */
    @Query("UPDATE embeddings SET publishedAt = 0 WHERE refusedStamp IS NULL")
    void offerEverythingAgain();

    @Query("SELECT COUNT(*) FROM embeddings WHERE publishedAt = 0 AND refusedStamp IS NULL")
    int countNotPublished();

    @Query("DELETE FROM embeddings")
    void deleteAll();

    @Query("DELETE FROM embeddings WHERE songId IN (:songIds)")
    void delete(List<Long> songIds);
}
