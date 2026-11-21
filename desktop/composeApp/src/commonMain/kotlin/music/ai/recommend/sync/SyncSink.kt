package music.ai.recommend.sync

/**
 * Where written batches go, so that the deciding — when to write, what to call the file, how to
 * number the batch — can be shared code and tested without a folder, a phone or a sync tool.
 *
 * Lines rather than records, and a whole flush at a time rather than a line at a time, because on
 * Android the folder is reached through the storage access framework: opening a document there is
 * expensive, and appending to one is not something every provider supports. An implementation that
 * cannot append is free to write the lines as a new file inside the day's directory instead — the
 * reader takes a day as a directory or as a file, whichever it finds.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
interface SyncSink {

    /**
     * Adds [lines] to the end of [relativePath] inside this device's own directory, creating
     * whatever is missing on the way.
     *
     * @param relativePath always forward slashes, as [HistoryWriter.dayFile] gives it.
     * @return true when every line is on disk. False means the batch was not written; the writer
     *   keeps it and offers it again, so a folder that is briefly unreachable costs nothing but a
     *   delay.
     */
    fun append(relativePath: String, lines: List<String>): Boolean
}
