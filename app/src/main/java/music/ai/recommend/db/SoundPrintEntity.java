package music.ai.recommend.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * What one file's sound hashes to, and what the file looked like when it was hashed.
 *
 * <p>A table of its own rather than a column on the embeddings, because the tracks this is most
 * needed for are precisely the ones with no embedding yet: a device asks its peer for the vectors
 * of what it is missing, and naming those by sound rather than by spelling is the whole point. It
 * also has to outlive an analysis version change, which throws every vector away — the sound of a
 * file does not depend on which model read it.
 *
 * <p>The stamp is the file's length and modification time, exactly as
 * {@link music.ai.recommend.ai.AnalysisBacklog#stamp} writes it, and it is the one reason a print
 * is ever recomputed. The same rule as a refusal's, deliberately: two notions of "this file has
 * changed" would be two things to get wrong.
 */
@Entity(tableName = "sound_prints")
public class SoundPrintEntity {
    @PrimaryKey
    public long songId;

    /** "length:modifiedAt" when the print was taken; a different one means a different file. */
    public String stamp;

    /** sha256 of the sound, lowercase hex; see {@link music.ai.recommend.ai.SoundPrint}. */
    public String print;

    public SoundPrintEntity() {
    }

    public SoundPrintEntity(long songId, String stamp, String print) {
        this.songId = songId;
        this.stamp = stamp;
        this.print = print;
    }
}
