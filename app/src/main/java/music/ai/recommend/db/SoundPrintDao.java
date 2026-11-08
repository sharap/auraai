package music.ai.recommend.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SoundPrintDao {

    @Query("SELECT * FROM sound_prints")
    List<SoundPrintEntity> getAll();

    /** Replaces, because a file that changed has one print and not two. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<SoundPrintEntity> prints);

    /**
     * Drops the prints of tracks the library no longer holds.
     *
     * Not urgent — a stale print costs a row — but a library that was reorganised would otherwise
     * keep every id it ever had.
     */
    @Query("DELETE FROM sound_prints WHERE songId NOT IN (:songIds)")
    void keepOnly(List<Long> songIds);

    @Query("SELECT COUNT(*) FROM sound_prints")
    int count();
}
