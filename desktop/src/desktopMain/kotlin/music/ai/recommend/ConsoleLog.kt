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
 */
object ConsoleLog {

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

        override fun write(byte: Int) {
            console.write(byte)
            runCatching { file.write(byte) }
        }

        override fun write(bytes: ByteArray, from: Int, length: Int) {
            console.write(bytes, from, length)
            runCatching { file.write(bytes, from, length) }
        }

        override fun flush() {
            console.flush()
            runCatching { file.flush() }
        }
    }
}
