package music.ai.recommend.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {EmbeddingEntity.class, PlayEventEntity.class}, version = 4, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract EmbeddingDao embeddingDao();

    public abstract PlayEventDao playEventDao();

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

    /** Listening history, which the daily playlist is built from. */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS play_events ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + " songId INTEGER NOT NULL, playedAt INTEGER NOT NULL,"
                    + " playedMs INTEGER NOT NULL, durationMs INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_play_events_playedAt ON play_events (playedAt)");
        }
    };

    /**
     * Repairs databases created by the build whose {@code play_events} index was missing from the
     * entity. Nothing changes in the tables — the migration exists so that Room re-validates the
     * schema and stores the corrected identity hash, instead of refusing to open the file.
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_play_events_playedAt ON play_events (playedAt)");
        }
    };

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "music_database")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
