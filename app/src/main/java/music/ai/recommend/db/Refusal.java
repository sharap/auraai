package music.ai.recommend.db;

/**
 * A track the analysis tried and could not read, with the stamp of the file it gave up on.
 *
 * Its own class because the scan wants two columns of a row whose third column is a vector:
 * reading whole rows to find out which handful of files are broken would pull the whole table —
 * several megabytes of floats — off disk to answer a question about five of them.
 */
public class Refusal {
    public long songId;
    public String stamp;
}
