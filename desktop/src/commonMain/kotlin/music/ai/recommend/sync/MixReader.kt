package music.ai.recommend.sync

/**
 * Reads the other devices' playlists of the day out of the sync folder.
 *
 * Nothing is kept: unlike a playlist change, a mix has no history worth replaying — only today's
 * matters, and a record for an older day is of no use to anybody the moment that day is over. So
 * what comes back is whatever was in the files this pass, and the caller picks the one that stands.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class MixReader(
    private val ownDeviceId: String,
    private val source: SyncSource,
    private val progress: Map<String, Int>,
    /** What can open a sealed batch; see [Sealing]. */
    private val sealing: () -> Sealing? = { null }
) {

    data class Outcome(
        val records: List<MixRecord>,
        val filesRead: Int,
        val filesUnavailable: Int,
        /** Lines that would not decode, the half-written tails among them. */
        val linesUnreadable: Int,
        /** Of those, the ones that are sealed and did not open; see [BatchReader.Outcome]. */
        val linesSealed: Int,
        /** How many lines of each file this pass consumed, for the caller to record afterwards. */
        val advanced: Map<String, Int>
    )

    fun read(maxRecords: Int = MAX_RECORDS): Outcome {
        val outcome = BatchReader(ownDeviceId, source, progress, sealing)
            .read(inside = MixWriter.DIRECTORY, maxRecords = maxRecords)

        val records = ArrayList<MixRecord>()
        var unreadable = outcome.linesUnreadable
        for (batch in outcome.batches) {
            // Own records are skipped: this device knows what it built. A batch is attributed by
            // the name inside it rather than by the directory it sits in, so that a device able to
            // see two others can pass their files along.
            if (batch.deviceId == ownDeviceId) continue
            for (json in batch.records) {
                val record = MixRecord.fromJson(json)
                if (record == null) unreadable++ else records.add(record)
            }
        }

        return Outcome(
            records = records,
            filesRead = outcome.filesRead,
            filesUnavailable = outcome.filesUnavailable,
            linesUnreadable = unreadable,
            linesSealed = outcome.linesSealed,
            advanced = outcome.advanced
        )
    }

    companion object {

        /**
         * How many mixes one pass takes. Generous against what is there: one record per device per
         * build per day, a few dozen tracks each.
         */
        const val MAX_RECORDS = 200
    }
}
