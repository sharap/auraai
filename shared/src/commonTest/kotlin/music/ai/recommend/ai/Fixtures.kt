package music.ai.recommend.ai

import java.io.File

/**
 * The committed audio fixtures, handed over as files.
 *
 * The decoders take a [File] — they seek within it — so a stream off the classpath will not do, and
 * the tests used to name the fixture by a path into the source tree:
 * `File("src/test/resources/mp3_excerpt_fixture.mp3")`. That is relative to whatever directory the
 * test task happens to run in, and it survived only because there was one such task per copy of the
 * test. There is one copy now, in a module with a target per platform, and a fixture found by one
 * run and not by another would be a difference between the platforms invented by the test harness.
 *
 * So the bytes come off the classpath, where the build puts them for every target, and land in a
 * temporary file once per name. Which also means the fixtures exist once in the repository: they
 * were committed twice, 1.5 MB of identical audio, because each side needed its own copy beside
 * its own tests.
 */
internal object Fixtures {

    private val directory: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "aura-fixtures-${ProcessHandle.current().pid()}")
            .apply { mkdirs() }
    }

    private val taken = HashMap<String, File>()

    /** The fixture of this name, as a file on disk. */
    @Synchronized
    fun of(name: String): File = taken.getOrPut(name) {
        val out = File(directory, name)
        if (!out.isFile) {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/$name")) {
                "$name is missing from the test resources"
            }.use { it.readBytes() }
            out.writeBytes(bytes)
        }
        out
    }

    /**
     * A path that is not there, and is meant not to be.
     *
     * Its own function because "a file that is not there" is a case the decoders are tested on, and
     * writing it as a path into the source tree made it look like a fixture somebody had forgotten
     * to commit.
     */
    fun missing(name: String): File = File(directory, "missing-$name")
}
