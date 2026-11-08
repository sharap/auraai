package music.ai.recommend.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {EmbeddingEntity.class, PlayEventEntity.class}, version = 8, exportSchema = false)
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

    /**
     * Records which device heard each listen, so that sync can tell its own from the rest.
     *
     * <p>A column with a default is the safest change SQLite has, and every row already there is
     * this device's, which is exactly what the default says. Months of listening ride on this
     * going well: analysis can be redone in an evening, history cannot be redone at all.
     */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE play_events ADD COLUMN deviceId TEXT NOT NULL DEFAULT ''");
        }
    };

    /**
     * Version 6 records whether a vector has been offered to the other devices.
     *
     * Zero for every row already there, which says "not offered yet" — true by construction, since
     * before this version there was nothing to offer them to. The first sync pass after an upgrade
     * therefore walks the whole library, a few hundred vectors at a time, which is the intended
     * behaviour and the only way a device that was analysed first can hand its work to a second.
     */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE embeddings ADD COLUMN publishedAt INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * Version 7 reconsiders every vector, because the column's meaning changed.
     *
     * It used to mean "dealt with", and a vector that arrived from another device was marked on
     * arrival and deliberately never passed on: with two devices there is nobody to relay to, and
     * it kept the folder from carrying everything twice. The cost of that turned out to be the
     * folder being a cache a device can only contribute to and never be restored from — if the
     * device that analysed a track loses its vectors, the one holding a copy will not offer it
     * back. So it now means "this device has offered it", every device offers what it holds, and
     * the folder carries each vector once per device. Zeroing the column makes the vectors already
     * here go out once under the new meaning.
     */
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("UPDATE embeddings SET publishedAt = 0");
        }
    };

    /**
     * Version 8 tells a vector apart from a refusal, and recovers the refusals already stored.
     *
     * Until now a file that would not decode was stored as 512 zeros, which kept the scan from
     * retrying it for ever but made it indistinguishable from an analysed track: it counted as
     * analysed, it went out to the other devices as though it were a vector, and nothing could
     * ever reconsider it — so a download that stopped halfway would stay unanalysed even after the
     * owner finished it.
     *
     * <p>The UPDATE finds those rows by their contents rather than by a list, because that is the
     * only record of them there is: a blob of nothing but zero bytes, whatever its length, is one.
     * They are given an empty stamp, which no present file can equal, so each gets exactly one
     * more attempt and then a stamp of its own — and the zeros themselves go, since a refusal has
     * no vector and 2 KB of nothing per broken file is 2 KB too many.
     */
    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE embeddings ADD COLUMN refusedStamp TEXT");
            db.execSQL("UPDATE embeddings SET refusedStamp = '', vector = NULL"
                    + " WHERE vector IS NOT NULL AND length(vector) > 0"
                    + " AND vector = zeroblob(length(vector))");
        }
    };

    /**
     * Every migration this build knows, in the order they apply.
     *
     * Visible beyond this class so that a test can open an older file through exactly the path the
     * app uses. A test that registered a list of its own would be testing its own list.
     */
    public static final Migration[] MIGRATIONS = {
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
    };

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "music_database")
                            .addMigrations(MIGRATIONS)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
