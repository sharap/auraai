package music.ai.recommend.db

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import music.ai.recommend.model.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

// ANALYSIS_VERSION and EMBEDDING_DIMENSIONS live in db/EmbeddingEntity.kt, where common
// code — sync among it — can say them too.

class DesktopMusicDao(private val baseDir: File) : MusicDao {
    private val embeddingsFile = File(baseDir, "embeddings.bin")
    private val legacyEmbeddingsFile = File(baseDir, "embeddings.json")
    private val settingsFile = File(baseDir, "settings.json")
    private val playlistsDir = File(baseDir, "playlists")
    private val favoritesFile = File(baseDir, "favorites.m3u")
    private val historyFile = File(baseDir, "play_history.bin")

    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Keyed by path, so replacing a track's embedding is a hash lookup.
     *
     * A list meant every insert scanned the whole table to drop the previous row, which over a
     * 4300-track scan is about nine million comparisons for work a map does in one step. The lock
     * is a separate object because the cache used to be a reassignable `var`, and synchronising on
     * something that gets replaced guards nothing once it has been.
     */
    private val lock = Any()
    private val historyLock = Any()
    private var countOnDisk = -1

    /** The next record number to hand out; zero until the log has been read once. */
    private var nextId = 0L

    /** Whether the log on disk is still in the shape that had neither numbers nor devices. */
    private var historyIsLegacy = false

    /**
     * Set when the log is in a format this build does not know, which makes it read-only: a newer
     * AiMusic wrote it, and anything written over it would be somebody's listening thrown away.
     */
    private var historyIsForeign = false
    private val cache = LinkedHashMap<String, EmbeddingEntity>()

    /** The envelope the pre-binary format stored embeddings in. */
    private class StoredEmbeddings(
        val analysisVersion: Int = 0,
        val embeddings: List<EmbeddingEntity>? = null
    )

    @Volatile
    private var analysisResetFlag = false

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
        if (!playlistsDir.exists()) playlistsDir.mkdirs()
        load()
    }

    private fun load() {
        if (embeddingsFile.exists()) {
            loadBinary()
            return
        }
        if (legacyEmbeddingsFile.exists()) loadLegacyJson()
    }

    private fun loadBinary() {
        try {
            DataInputStream(embeddingsFile.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) {
                    println("DesktopMusicDao: embeddings.bin is not in the expected format, ignoring it")
                    return
                }
                val formatVersion = input.readInt()
                if (formatVersion !in READABLE_FORMATS) {
                    println("DesktopMusicDao: unknown storage format $formatVersion, ignoring it")
                    return
                }
                val analysisVersion = input.readInt()
                val count = input.readInt()
                if (analysisVersion != ANALYSIS_VERSION) {
                    discardOutdatedAnalysis(analysisVersion)
                    return
                }
                val loaded = LinkedHashMap<String, EmbeddingEntity>(count * 2)
                repeat(count) {
                    val path = input.readUTF()
                    val dimension = input.readInt()
                    val vector = ArrayList<Float>(dimension)
                    repeat(dimension) { vector.add(input.readFloat()) }
                    // Version 1 had no notion of a vector having been offered to anyone, because
                    // there was nowhere to offer it to. Zero is therefore the true answer for
                    // every row in such a file, and the first sync pass walks the library.
                    val publishedAt = if (formatVersion >= 2) input.readLong() else 0L
                    // A row with no floats is a refusal and carries the stamp of the file it was
                    // refused for; version 3 is where that became a thing a row can be.
                    val stamp = if (formatVersion >= 3 && dimension == 0) input.readUTF() else null
                    loaded[path] = if (stamp != null) {
                        EmbeddingEntity(path, emptyList(), publishedAt, stamp)
                    } else if (vector.isNotEmpty() && vector.all { it == 0f }) {
                        // Before refusals had a record of their own, a file that would not decode
                        // was stored as 512 zeros: it counted as analysed, it went out to the
                        // other devices as though it were a vector, and nothing could reconsider
                        // it even after the owner replaced the file. Recovered here as a refusal
                        // with no stamp, which no present file can equal, so each gets exactly one
                        // more attempt and then a stamp of its own.
                        EmbeddingEntity(path, emptyList(), publishedAt, "")
                    } else {
                        EmbeddingEntity(path, vector, publishedAt)
                    }
                }
                synchronized(lock) {
                    cache.clear()
                    cache.putAll(loaded)
                }
            }
        } catch (e: Exception) {
            println("DesktopMusicDao: could not read embeddings.bin: ${e.message}")
        }
    }

    /**
     * One-time conversion of the JSON envelope into the binary table.
     *
     * The JSON form was pretty-printed floats — 47 MB for 4300 tracks against about 9 MB packed —
     * so it is rewritten rather than kept. The vectors themselves are unchanged, and the binary
     * file is only written once it has been read in full, so an interrupted migration costs
     * nothing.
     */
    private fun loadLegacyJson() {
        try {
            // An outdated table is thrown away whole, so the format is identified from the first
            // character rather than by parsing tens of megabytes first and deciding afterwards.
            val head = legacyEmbeddingsFile.bufferedReader().use { reader ->
                val buffer = CharArray(HEAD_CHARS)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
            }
            when (head.firstOrNull { !it.isWhitespace() }) {
                null -> return
                '[' -> {
                    discardOutdatedAnalysis(1)
                    legacyEmbeddingsFile.delete()
                    return
                }
            }

            val stored = gson.fromJson(legacyEmbeddingsFile.readText(), StoredEmbeddings::class.java) ?: return
            if (stored.analysisVersion != ANALYSIS_VERSION) {
                discardOutdatedAnalysis(stored.analysisVersion)
                legacyEmbeddingsFile.delete()
                return
            }
            synchronized(lock) {
                cache.clear()
                for (entity in stored.embeddings.orEmpty()) cache[entity.path] = entity
            }
            forceSave()
            if (embeddingsFile.exists() && embeddingsFile.length() > 0) {
                legacyEmbeddingsFile.delete()
                println("DesktopMusicDao: migrated ${cache.size} embeddings to the binary table")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Drops embeddings produced by an older analysis.
     *
     * They are not merely stale but wrong: mixing them with current ones would give a similarity
     * space where part of the library sits in the wrong place, and since the scan skips paths it
     * already has, a rescan would quietly keep every one of them.
     */
    private fun discardOutdatedAnalysis(storedVersion: Int) {
        synchronized(lock) { cache.clear() }
        analysisResetFlag = true
        println("DesktopMusicDao: discarded embeddings from analysis version $storedVersion")
        forceSave()
    }

    fun forceSave() {
        try {
            if (!baseDir.exists()) baseDir.mkdirs()
            val rows = synchronized(lock) { cache.values.toList() }
            // Written aside and renamed, so an interrupted save cannot truncate the table.
            val tmp = File(embeddingsFile.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeInt(ANALYSIS_VERSION)
                out.writeInt(rows.size)
                for (row in rows) {
                    out.writeUTF(row.path)
                    out.writeInt(row.vector.size)
                    for (x in row.vector) out.writeFloat(x)
                    out.writeLong(row.publishedAt)
                    // Only a refusal has no floats, and only a refusal has a stamp, so the size
                    // just written says whether one follows.
                    if (row.vector.isEmpty()) out.writeUTF(row.refusedStamp.orEmpty())
                }
            }
            if (!tmp.renameTo(embeddingsFile)) {
                embeddingsFile.delete()
                tmp.renameTo(embeddingsFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun getAllEmbeddings(): List<EmbeddingEntity> {
        return synchronized(lock) { cache.values.filter { it.refusedStamp == null } }
    }

    override suspend fun refusals(): Map<String, String> = synchronized(lock) {
        cache.values.mapNotNull { row -> row.refusedStamp?.let { row.path to it } }.toMap()
    }

    /**
     * The prints, read once and kept: a small file of its own beside the vectors.
     *
     * Its own file rather than a column in embeddings.bin because it outlives that file — an
     * analysis version change discards every vector, and the sound of a file is not the model's
     * opinion of it.
     */
    private val printsFile = File(baseDir, "prints.bin")

    private val printLock = Any()

    @Volatile
    private var printCache: MutableMap<String, MusicDao.FilePrint>? = null

    /** Rows held by [rememberSoundPrint] and not yet written; see that method. */
    private val unwrittenPrints = LinkedHashMap<String, MusicDao.FilePrint>()

    private fun printsLoaded(): MutableMap<String, MusicDao.FilePrint> {
        printCache?.let { return it }
        return synchronized(printLock) {
            printCache ?: run {
                val loaded = LinkedHashMap<String, MusicDao.FilePrint>()
                if (printsFile.exists()) {
                    try {
                        DataInputStream(printsFile.inputStream().buffered()).use { input ->
                            val magic = input.readInt()
                            val format = input.readInt()
                            // Format 1 is read as well as written, because the prints in it cost a
                            // pass over every file in the library and are still true: the stamp
                            // they were taken under has not changed meaning. They simply have no
                            // excerpt start, which is what null says.
                            if (magic == PRINTS_MAGIC && (format == 1 || format == PRINTS_FORMAT)) {
                                val count = input.readInt()
                                repeat(count) {
                                    val path = input.readUTF()
                                    val stamp = input.readUTF()
                                    val print = input.readUTF()
                                    val start = if (format == 1) UNKNOWN_START else input.readLong()
                                    loaded[path] = MusicDao.FilePrint(
                                        stamp = stamp,
                                        print = print.takeIf { it.isNotEmpty() },
                                        excerptStartMs = start.takeIf { it != UNKNOWN_START }
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("DesktopMusicDao: could not read prints.bin: ${e.message}")
                    }
                }
                loaded
            }.also { printCache = it }
        }
    }

    override suspend fun soundPrints(): Map<String, MusicDao.FilePrint> =
        synchronized(printLock) { LinkedHashMap(printsLoaded()) }

    override suspend fun soundPrint(path: String): MusicDao.FilePrint? =
        synchronized(printLock) { printsLoaded()[path] }

    override suspend fun rememberSoundPrint(path: String, held: MusicDao.FilePrint) {
        val due = synchronized(printLock) {
            printsLoaded()[path] = held
            unwrittenPrints[path] = held
            if (unwrittenPrints.size < PRINTS_WRITE_AT) null
            else LinkedHashMap(unwrittenPrints).also { unwrittenPrints.clear() }
        } ?: return
        writePrints(due.size)
    }

    override suspend fun rememberSoundPrints(prints: Map<String, MusicDao.FilePrint>) {
        if (prints.isEmpty()) return
        synchronized(printLock) {
            printsLoaded().putAll(prints)
            // These rows are now the written truth, so the buffer must not undo them later with
            // an older idea of the same files.
            unwrittenPrints.keys.removeAll(prints.keys)
        }
        writePrints(prints.size)
    }

    /** Writes the whole table, which is how a file of this size is kept consistent. */
    private fun writePrints(changed: Int) {
        val rows = synchronized(printLock) { printsLoaded().entries.map { it.key to it.value } }
        try {
            if (!baseDir.exists()) baseDir.mkdirs()
            val tmp = File(printsFile.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { out ->
                out.writeInt(PRINTS_MAGIC)
                out.writeInt(PRINTS_FORMAT)
                out.writeInt(rows.size)
                for ((path, held) in rows) {
                    out.writeUTF(path)
                    out.writeUTF(held.stamp)
                    out.writeUTF(held.print ?: "")
                    out.writeLong(held.excerptStartMs ?: UNKNOWN_START)
                }
            }
            if (!tmp.renameTo(printsFile)) {
                printsFile.delete()
                tmp.renameTo(printsFile)
            }
        } catch (e: Exception) {
            println("DesktopMusicDao: could not store $changed prints: ${e.message}")
        }
    }

    override fun dealtWithNow(): Int? = synchronized(lock) { cache.size }

    override suspend fun hasEmbedding(path: String): Boolean = synchronized(lock) {
        cache[path]?.vector?.isNotEmpty() == true
    }

    override suspend fun insertEmbedding(embedding: EmbeddingEntity) {
        synchronized(lock) {
            // The whole row is replaced, so storing a vector also clears a refusal recorded for it.
            cache[embedding.path] = embedding
        }
    }

    override suspend fun refuseEmbedding(path: String, stamp: String) {
        synchronized(lock) {
            cache[path] = EmbeddingEntity(path, emptyList(), refusedStamp = stamp)
        }
    }

    override suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>): Int {
        if (embeddings.isEmpty()) return 0
        synchronized(lock) {
            for (embedding in embeddings) cache[embedding.path] = embedding
        }
        // Saved at once, unlike a single insert: the scanner batches its own saves because it is
        // about to write the next one anyway, while a batch from another device may be the last
        // thing that happens for hours.
        forceSave()
        return embeddings.size
    }

    override suspend fun vectorsToPublish(limit: Int): List<EmbeddingEntity> =
        synchronized(lock) {
            // In the table's own order, which is the order they were analysed in, so a backfill
            // that keeps being interrupted keeps making progress rather than revisiting a corner.
            cache.values.asSequence()
                .filter { it.publishedAt == 0L && it.refusedStamp == null }
                .take(limit)
                .toList()
        }

    override suspend fun markVectorsPublished(paths: List<String>, at: Long) {
        if (paths.isEmpty()) return
        var changed = false
        synchronized(lock) {
            for (path in paths) {
                val row = cache[path] ?: continue
                if (row.publishedAt != 0L) continue
                cache[path] = row.copy(publishedAt = at)
                changed = true
            }
        }
        if (changed) forceSave()
    }

    override suspend fun deleteEmbedding(path: String) {
        deleteEmbeddingNoSave(path)
        forceSave()
    }

    override suspend fun offerEveryVectorAgain(): Int {
        var offered = 0
        synchronized(lock) {
            for ((path, row) in cache.entries.toList()) {
                if (row.refusedStamp != null) continue
                offered++
                if (row.publishedAt != 0L) cache[path] = row.copy(publishedAt = 0L)
            }
        }
        forceSave()
        return offered
    }

    override suspend fun deleteEmbeddings(paths: List<String>) {
        if (paths.isEmpty()) return
        synchronized(lock) {
            for (path in paths) cache.remove(path)
        }
        forceSave()
    }

    fun deleteEmbeddingNoSave(path: String) {
        synchronized(lock) {
            cache.remove(path)
        }
    }

    override suspend fun clearAllEmbeddings() {
        synchronized(lock) {
            cache.clear()
        }
        analysisResetFlag = false
        forceSave()
    }

    // ---------------------------------------------------------------- listening history

    /**
     * An append-only log: one record per finished listen, written as it happens.
     *
     * Rewriting a whole table for every track played would be wasteful, and a crash mid-write
     * would take the history with it. A truncated tail from an interrupted append costs at most
     * the last listen, because the reader stops at the first short record.
     *
     * Each record carries the device that heard it and a number of its own. The device is what
     * keeps a listen taken in from the phone from being sent straight back out as this machine's,
     * and the number is what lets the outbox remember where it got to with one value. Both arrived
     * with sync, so a log written before it is in the older shape and is converted once — see
     * [readPlayEvents].
     */
    override suspend fun appendPlayEvent(event: PlayEventEntity) {
        appendPlayEvents(listOf(event))
    }

    override suspend fun appendPlayEvents(events: List<PlayEventEntity>): Int {
        if (events.isEmpty()) return 0
        synchronized(historyLock) {
            if (nextId <= 0) historyNow()
            // A log this build must not write to: either a newer version wrote it, or it is still
            // in the older shape because converting it failed. Appending a numbered record to an
            // unnumbered file would leave a log that neither shape can read.
            if (historyIsForeign || historyIsLegacy) return 0
            try {
                if (!baseDir.exists()) baseDir.mkdirs()
                val fresh = !historyFile.exists() || historyFile.length() == 0L
                DataOutputStream(FileOutputStream(historyFile, true).buffered()).use { out ->
                    if (fresh) writeHistoryHeader(out)
                    for (event in events) writeRecord(out, event.copy(id = nextId++))
                }
                if (countOnDisk >= 0) countOnDisk += events.size
                return events.size
            } catch (e: Exception) {
                // Whatever got through is a short record at the tail at worst, which the reader
                // steps over. Saying nothing was stored is the safe answer: a batch offered twice
                // is turned away by the merge, a batch marked stored and lost is gone.
                println("DesktopMusicDao: could not record ${events.size} listens: ${e.message}")
                return 0
            }
        }
    }

    override suspend fun playEvents(): List<PlayEventEntity> = synchronized(historyLock) { historyNow() }

    override suspend fun playEventsToExport(afterId: Long, limit: Int): List<PlayEventEntity> =
        synchronized(historyLock) {
            // In the file's own order, which is the order they were appended in, so the outbox
            // describes listens oldest first and its watermark only ever moves forward.
            historyNow().asSequence()
                .filter { it.deviceId.isEmpty() && it.id > afterId }
                .take(limit)
                .toList()
        }

    override suspend fun newestPlayEventId(): Long =
        synchronized(historyLock) { historyNow().maxOfOrNull { it.id } ?: 0L }

    override suspend fun playEventsFromOtherDevices(since: Long): List<PlayEventEntity> =
        synchronized(historyLock) {
            historyNow().filter { it.deviceId.isNotEmpty() && it.playedAt >= since }
        }

    override suspend fun prunePlayEvents(cutoff: Long) {
        synchronized(historyLock) {
            val kept = historyNow().filter { it.playedAt >= cutoff }
            if (historyIsForeign || kept.size == countOnDisk) return
            writeWholeHistory(kept)
        }
    }

    /**
     * The log, and the one-time work that reading it may turn out to need.
     *
     * Must be called with [historyLock] held. Whichever history call comes first pays for the
     * conversion of an older file and for finding the next number to hand out; after that this is
     * just a read.
     *
     * It is a read of the whole file each time, where the phone asks its database for the few rows
     * it wants. A year of listening is a megabyte, a sync pass reads it a handful of times, and the
     * alternative is a second copy of the log in memory that has to be kept true — which is how a
     * log and its index come to disagree.
     */
    private fun historyNow(): List<PlayEventEntity> {
        val events = readPlayEvents()
        if (nextId <= 0) nextId = (events.maxOfOrNull { it.id } ?: 0L) + 1
        if (historyIsLegacy) {
            // Written once, so that the numbers stop depending on a record's position in the file:
            // pruning drops records from the middle, and positions would then shift under the
            // outbox's watermark and send listens a second time.
            println("DesktopMusicDao: converting ${events.size} listens to the numbered log")
            if (writeWholeHistory(events)) historyIsLegacy = false
        }
        return events
    }

    /** Must be called with [historyLock] held. Replaces the file through a temporary and a rename. */
    private fun writeWholeHistory(events: List<PlayEventEntity>): Boolean {
        if (historyIsForeign) return false
        try {
            if (!baseDir.exists()) baseDir.mkdirs()
            val tmp = File(historyFile.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                writeHistoryHeader(out)
                for (event in events) writeRecord(out, event)
            }
            if (!tmp.renameTo(historyFile)) {
                historyFile.delete()
                if (!tmp.renameTo(historyFile)) return false
            }
            countOnDisk = events.size
            return true
        } catch (e: Exception) {
            println("DesktopMusicDao: could not rewrite the history: ${e.message}")
            return false
        }
    }

    private fun writeHistoryHeader(out: DataOutputStream) {
        out.writeInt(HISTORY_MAGIC)
        out.writeInt(HISTORY_FORMAT_VERSION)
    }

    private fun writeRecord(out: DataOutputStream, event: PlayEventEntity) {
        out.writeLong(event.id)
        out.writeUTF(event.deviceId)
        out.writeUTF(event.path)
        out.writeLong(event.playedAt)
        out.writeLong(event.playedMs)
        out.writeLong(event.durationMs)
    }

    /** Which shape the log on disk is in, which decides how a record is read. */
    private enum class HistoryShape { NUMBERED, LEGACY, UNKNOWN }

    /**
     * Must be called with [historyLock] held.
     *
     * A file written before sync has no header and begins with the length of the first path, which
     * cannot be mistaken for the magic: that would be a path of some sixteen thousand bytes. A file
     * whose header says a version this build does not know is left alone entirely — a newer AiMusic
     * wrote it, and guessing at it would throw away someone's listening.
     */
    private fun historyShape(): HistoryShape = try {
        DataInputStream(historyFile.inputStream().buffered()).use { input ->
            when {
                input.readInt() != HISTORY_MAGIC -> HistoryShape.LEGACY
                input.readInt() == HISTORY_FORMAT_VERSION -> HistoryShape.NUMBERED
                else -> HistoryShape.UNKNOWN
            }
        }
    } catch (e: Exception) {
        // Too short to hold a header at all, which a file of one legacy record can be.
        HistoryShape.LEGACY
    }

    /** Must be called with [historyLock] held. Reads the log in whichever shape it is in. */
    private fun readPlayEvents(): List<PlayEventEntity> {
        historyIsLegacy = false
        if (!historyFile.exists()) {
            historyIsForeign = false
            return emptyList()
        }
        val shape = historyShape()
        historyIsForeign = shape == HistoryShape.UNKNOWN
        if (historyIsForeign) {
            println("DesktopMusicDao: play_history.bin is in a newer format, leaving it untouched")
            return emptyList()
        }
        val numbered = shape == HistoryShape.NUMBERED
        val events = ArrayList<PlayEventEntity>()
        try {
            DataInputStream(historyFile.inputStream().buffered()).use { input ->
                if (numbered) {
                    input.readInt()
                    input.readInt()
                }
                while (true) {
                    // The first field of a record is the one place the file may simply end.
                    // Anything short after it is an interrupted append, and the outer catch keeps
                    // every whole record before it.
                    val id: Long
                    val deviceId: String
                    val path: String
                    if (numbered) {
                        id = try {
                            input.readLong()
                        } catch (e: java.io.EOFException) {
                            break
                        }
                        deviceId = input.readUTF()
                        path = input.readUTF()
                    } else {
                        id = (events.size + 1).toLong()
                        deviceId = ""
                        path = try {
                            input.readUTF()
                        } catch (e: java.io.EOFException) {
                            break
                        }
                    }
                    events += PlayEventEntity(
                        path = path,
                        playedAt = input.readLong(),
                        playedMs = input.readLong(),
                        durationMs = input.readLong(),
                        deviceId = deviceId,
                        id = id
                    )
                }
            }
        } catch (e: Exception) {
            println("DesktopMusicDao: history ends in a partial record, keeping ${events.size} listens")
        }
        historyIsLegacy = !numbered && events.isNotEmpty()
        countOnDisk = events.size
        return events
    }

    override suspend fun analysisWasReset(): Boolean = analysisResetFlag

    override suspend fun acknowledgeAnalysisReset() {
        analysisResetFlag = false
    }

    private companion object {
        /** Enough of the legacy file to find its first non-blank character. */
        const val HEAD_CHARS = 64

        /** "AIME", so a file that is not this table is recognised rather than misparsed. */
        const val MAGIC = 0x41494D45

        /** `AIMP`, for the file of sound prints. */
        const val PRINTS_MAGIC = 0x41494D50

        /**
         * Format 2 added the excerpt start to each row. Format 1, which had only the stamp and
         * the print, is still read: see [printsLoaded].
         */
        const val PRINTS_FORMAT = 2

        /** Written where a row has no excerpt start, since zero is a real one. */
        const val UNKNOWN_START = Long.MIN_VALUE

        /** How many held rows are worth rewriting the file; see [MusicDao.rememberSoundPrint]. */
        const val PRINTS_WRITE_AT = 64

        /**
         * How the table is laid out, as opposed to what the vectors in it mean.
         *
         * Version 2 added, per row, when the vector was offered to the other devices. A version 1
         * file is still read — it is nine megabytes of somebody's analysis — and is written back as
         * the current version the next time the table is saved.
         *
         * Version 3 lets a row be a refusal: no floats, and the stamp of the file the analysis
         * could not read. A version 2 file holds those as 512 zeros, which is read back as a
         * refusal with no stamp — see the loader.
         */
        const val FORMAT_VERSION = 3

        /** Layouts this build can read. Writing is always the current one. */
        val READABLE_FORMATS = setOf(1, 2, 3)

        /** "AIMH", which tells a numbered listening log from the shape that came before it. */
        const val HISTORY_MAGIC = 0x41494D48

        /** Version 1 of the numbered log: id, device, path, when, how far, how long. */
        const val HISTORY_FORMAT_VERSION = 1
    }

    override suspend fun saveSettings(settings: AppSettings) {
        try {
            settingsFile.writeText(prettyGson.toJson(settings))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun loadSettings(): AppSettings? {
        if (!settingsFile.exists()) return null
        return try {
            val json = settingsFile.readText()
            gson.fromJson(json, AppSettings::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun savePlaylists(playlists: List<Playlist>) {
        try {
            // Clear existing M3U files
            playlistsDir.listFiles()?.forEach { it.delete() }
            
            playlists.forEach { playlist ->
                val m3uFile = File(playlistsDir, "${playlist.name}.m3u")
                val content = buildString {
                    appendLine("#EXTM3U")
                    playlist.songs.forEach { song ->
                        appendLine("#EXTINF:${song.duration / 1000},${song.artist} - ${song.title}")
                        appendLine(song.path)
                    }
                }
                m3uFile.writeText(content)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun loadPlaylists(allSongs: List<Song>): List<Playlist> {
        val result = mutableListOf<Playlist>()
        try {
            playlistsDir.listFiles { _, name -> name.endsWith(".m3u") }?.forEach { file ->
                val lines = file.readLines()
                val songPaths = lines.filter { it.isNotBlank() && !it.startsWith("#") }
                val playlistSongs = songPaths.mapNotNull { path ->
                    allSongs.find { it.path == path }
                }
                result.add(
                    Playlist(
                        id = file.nameWithoutExtension.hashCode().toLong(),
                        name = file.nameWithoutExtension,
                        songs = playlistSongs
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    override suspend fun saveFavorites(songPaths: Set<String>) {
        try {
            val content = buildString {
                appendLine("#EXTM3U")
                songPaths.forEach { appendLine(it) }
            }
            favoritesFile.writeText(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun loadFavorites(): Set<String> {
        if (!favoritesFile.exists()) return emptySet()
        return try {
            favoritesFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .toSet()
        } catch (e: Exception) {
            e.printStackTrace()
            emptySet()
        }
    }
}

class DesktopAppDatabase : AppDatabase {
    private val baseDir = File(System.getProperty("user.home"), ".aimusic")
    private val dao = DesktopMusicDao(baseDir)
    
    override fun musicDao(): MusicDao = dao

    override fun dataDirectory(): String = baseDir.path
}

/**
 * One database per process.
 *
 * [DesktopMusicDao] keeps the embeddings table in memory and only reads the file in its
 * constructor, so a second instance means a second cache: the scanner wrote through its own copy
 * while the ViewModel kept serving the one it had loaded at startup, which is why a finished scan
 * left "Analyzed songs" unchanged and AI search empty until the app was restarted. Worse, a save
 * from the stale cache could overwrite what the scan had just written.
 */
private val databaseInstance: AppDatabase by lazy { DesktopAppDatabase() }

actual fun getAppDatabase(): AppDatabase = databaseInstance
