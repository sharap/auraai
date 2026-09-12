package music.ai.recommend.db;

import android.database.Cursor;

import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the v1 {@code embeddings_legacy} table, whose vectors are Gson JSON arrays.
 *
 * <p>Goes through the raw {@link SupportSQLiteDatabase} rather than a Room DAO because the table is
 * deliberately not part of the v2 schema — Room would reject a {@code @Query} against it at compile
 * time. Used once, to carry an existing scan across the JSON-to-BLOB migration.
 */
public final class LegacyEmbeddings {

    private LegacyEmbeddings() {
    }

    public static boolean exists(AppDatabase database) {
        SupportSQLiteDatabase db = database.getOpenHelper().getWritableDatabase();
        try (Cursor cursor = db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='embeddings_legacy'")) {
            return cursor.moveToFirst() && cursor.getInt(0) > 0;
        }
    }

    /** @return song id to JSON vector, for every legacy row. */
    public static List<Row> readAll(AppDatabase database) {
        SupportSQLiteDatabase db = database.getOpenHelper().getWritableDatabase();
        List<Row> rows = new ArrayList<>();
        try (Cursor cursor = db.query("SELECT songId, vector FROM embeddings_legacy")) {
            while (cursor.moveToNext()) {
                rows.add(new Row(cursor.getLong(0), cursor.isNull(1) ? null : cursor.getString(1)));
            }
        }
        return rows;
    }

    public static void drop(AppDatabase database) {
        database.getOpenHelper().getWritableDatabase().execSQL("DROP TABLE IF EXISTS embeddings_legacy");
    }

    public static final class Row {
        public final long songId;
        public final String vector;

        Row(long songId, String vector) {
            this.songId = songId;
            this.vector = vector;
        }
    }
}
