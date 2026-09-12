package music.ai.recommend.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {EmbeddingEntity.class}, version = 2, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract EmbeddingDao embeddingDao();

    private static volatile AppDatabase INSTANCE;

    /**
     * Moves the v1 JSON vectors aside instead of dropping them, so a library that took hours to
     * scan is not thrown away. {@link music.ai.recommend.ai.EmbeddingStore} converts the rows to
     * BLOBs on the next read and then drops the legacy table.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE embeddings RENAME TO embeddings_legacy");
            db.execSQL("CREATE TABLE IF NOT EXISTS embeddings ("
                    + "songId INTEGER NOT NULL, vector BLOB, PRIMARY KEY(songId))");
        }
    };

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "music_database")
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
