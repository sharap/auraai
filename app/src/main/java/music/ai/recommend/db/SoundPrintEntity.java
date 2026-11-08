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

    /**
     * Which millisecond of this file the analysed excerpt starts at, or null while nobody has
     * worked it out.
     *
     * Beside the print rather than beside the vector because of who needs it and when. Both
     * devices in a conversation need it for every track they talk about: the one handing a vector
     * over has to name the ten seconds it describes, and the one taking it in has to name its own
     * ten seconds to judge whether the two agree. The number that arrives in a record is therefore
     * no help to the receiver — it is the other device's answer, and comparing it with itself would
     * be no comparison at all.
     *
     * Working it out means walking the file: for a format the shared decoder handles it is the
     * decoder's own count of the samples, which is a pass over every frame. Measured on one
     * conversation of 4223 vectors, that pass was the entire cost of the exchange — six minutes
     * during which neither device said anything — and it was paid again on the next conversation,
     * and on the one after that, for the same unchanged files.
     *
     * It belongs to the file and not to the track, which is why it lives under the same stamp as
     * the print: a file that has changed has neither until somebody looks again. It also has to
     * outlive an analysis version change the same way a print does — a new model reads the same
     * ten seconds, since {@link music.ai.recommend.ai.Excerpt} picks them from the sound and not
     * from the model.
     *
     * <p>Null and not zero for "not worked out", because zero is a real answer: a recording
     * shorter than the excerpt is analysed from its very beginning, and a column that could not
     * tell the two apart would walk those files again on every conversation for ever.
     */
    public Long excerptStartMs;

    public SoundPrintEntity() {
    }

    public SoundPrintEntity(long songId, String stamp, String print) {
        this(songId, stamp, print, null);
    }

    public SoundPrintEntity(long songId, String stamp, String print, Long excerptStartMs) {
        this.songId = songId;
        this.stamp = stamp;
        this.print = print;
        this.excerptStartMs = excerptStartMs;
    }
}
