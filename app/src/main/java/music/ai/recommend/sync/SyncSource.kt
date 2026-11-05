package music.ai.recommend.sync

/**
 * The other side of [SyncSink]: the sync folder as something to read, named by what a reader needs
 * rather than by how a platform provides it. Android reaches the folder through the storage access
 * framework and the desktop through plain files, and neither detail belongs in the deciding.
 *
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
interface SyncSource {

    /**
     * The device directories in the folder, by the id each is named after. A directory whose name
     * is not an id is somebody else's business and is left alone.
     */
    fun devices(): List<String>

    /**
     * Relative paths of one device's files, as [HistoryWriter.dayFile] spells them — forward
     * slashes, and either `history/2026-11-05.jsonl` or, where appending is not available,
     * `history/2026-11-05/0003.jsonl`.
     */
    fun files(deviceId: String): List<String>

    /**
     * Every line of one file.
     *
     * @return null when the file cannot be read right now — mid-copy, permission withdrawn, device
     *   unplugged. That is not an error to report but a reason to try again later, and the reader
     *   treats it as such: nothing about the file is marked as consumed.
     */
    fun lines(deviceId: String, relativePath: String): List<String>?
}
