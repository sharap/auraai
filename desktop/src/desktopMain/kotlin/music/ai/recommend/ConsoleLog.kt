package music.ai.recommend

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Keeps a copy of everything this app prints, beside its data.
 *
 * The app says a great deal that matters — which destination the records go to, what a conversation
 * moved, why a batch would not open, the stack of anything that failed — and all of it went to
 * whatever terminal happened to start it. Four separate faults in one afternoon were diagnosed by
 * asking the owner to paste that terminal back, and the fifth could not be: the window had been
 * restarted and the output was gone. A log beside the data costs a few kilobytes and ends that.
 *
 * Both streams are still printed as before, so running it from a terminal looks exactly the same.
 * The copy in the file carries a timestamp per line, which the console copy does not — that is the
 * one difference, and it is the difference that makes the file worth keeping. Three separate
 * diagnoses in one sitting stalled on the same question: the log said a hundred and fifty dial
 * attempts and six tracks started, and nothing at all about whether that was a minute or an hour.
 * Milliseconds, because what is usually being asked is whether two things happened together.
 */
object ConsoleLog {

    private const val NEWLINE = '\n'.code

    /** Kept small on purpose: this is for the last few runs, not for history. */
    private const val MOST_BYTES = 4L * 1024 * 1024

    fun beside(directory: File) {
        try {
            if (!directory.exists()) directory.mkdirs()
            val file = File(directory, "log.txt")
            // Started afresh once it grows past a few megabytes rather than rotated: nobody is
            // going to read a tenth copy of a sync pass, and an unbounded file in a data directory
            // is its own small betrayal.
            if (file.exists() && file.length() > MOST_BYTES) file.delete()
            val sink = FileOutputStream(file, true)
            System.setOut(PrintStream(Both(System.out, sink), true))
            System.setErr(PrintStream(Both(System.err, sink), true))
            println("--- started ${java.time.LocalDateTime.now()} ---")
        } catch (e: Exception) {
            // A log that cannot be written is not a reason not to start.
            System.err.println("Could not open the log beside the data: ${e.message}")
        }
    }

    /** Writes to both, and lets the console win when the file cannot be written. */
    private class Both(
        private val console: OutputStream,
        private val file: OutputStream
    ) : OutputStream() {

        private val clock = java.time.format.DateTimeFormatter
            .ofPattern("HH:mm:ss.SSS")
            .withZone(java.time.ZoneId.systemDefault())

        private var atLineStart = true

        override fun write(byte: Int) {
            console.write(byte)
            runCatching {
                stamp()
                file.write(byte)
                if (byte == NEWLINE) atLineStart = true
            }
        }

        override fun write(bytes: ByteArray, from: Int, length: Int) {
            console.write(bytes, from, length)
            runCatching {
                // Written a line at a time into the file so each one can be stamped, while the
                // console still gets the whole array in one call — a line split across two writes
                // would otherwise collect a timestamp in its middle.
                var at = from
                val end = from + length
                while (at < end) {
                    stamp()
                    var cut = at
                    while (cut < end && bytes[cut] != NEWLINE.toByte()) cut++
                    val upTo = if (cut < end) cut + 1 else end
                    file.write(bytes, at, upTo - at)
                    if (cut < end) atLineStart = true
                    at = upTo
                }
            }
        }

        private fun stamp() {
            if (!atLineStart) return
            file.write(clock.format(java.time.Instant.now()).toByteArray())
            file.write(' '.code)
            atLineStart = false
        }

        override fun flush() {
            console.flush()
            runCatching { file.flush() }
        }
    }
}
