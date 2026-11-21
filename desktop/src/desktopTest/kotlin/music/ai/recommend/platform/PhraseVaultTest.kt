package music.ai.recommend.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What happens to the one secret this arrangement has when the desktop cannot keep it properly.
 *
 * The keyring is handed in rather than used, deliberately: the real one is the owner's own, and a
 * test that exercised these paths against it would write into their passwords on every build. What
 * is being proved is the rule that matters — a place is trusted only after it has given the phrase
 * back — and the three ways a desktop can fail to have a keyring at all.
 */
class PhraseVaultTest {

    private lateinit var directory: File

    @BeforeTest
    fun emptyDirectory() {
        directory = Files.createTempDirectory("aimusic-phrase").toFile()
    }

    @AfterTest
    fun tidyUp() {
        directory.deleteRecursively()
    }

    private fun phraseFile() = File(directory, "phrase")

    /** A keyring that works, and counts what it was asked. */
    private class Working : Keyring {
        var held: String? = null
        var reads = 0
        override fun read(): Keyring.Kept {
            reads++
            return held?.let { Keyring.Kept.Phrase(it) } ?: Keyring.Kept.None
        }
        override fun write(phrase: String): Boolean {
            held = phrase
            return true
        }
        override fun forget() {
            held = null
        }
    }

    /** No keyring at all: a server, a minimal desktop, Windows, macOS. */
    private class Absent : Keyring {
        override fun read(): Keyring.Kept = Keyring.Kept.None
        override fun write(phrase: String) = false
        override fun forget() = Unit
    }

    /**
     * Holds a phrase and will not show it: a keyring the owner left locked, and a prompt they
     * closed. The one answer that must not be read as "no phrase".
     */
    private class Locked : Keyring {
        var writes = 0
        override fun read(): Keyring.Kept = Keyring.Kept.Locked
        override fun write(phrase: String): Boolean {
            writes++
            return false
        }
        override fun forget() = Unit
    }

    /** Takes the secret and will not give it back, which is the dangerous one. */
    private class Forgetful : Keyring {
        var writes = 0
        override fun read(): Keyring.Kept = Keyring.Kept.None
        override fun write(phrase: String): Boolean {
            writes++
            return true
        }
        override fun forget() = Unit
    }

    @Test
    fun aKeyringThatWorksKeepsThePhraseAndTheFileGoes() {
        val keyring = Working()
        val vault = PhraseVault(directory, keyring)
        // A phrase that was in a file before — what a machine updating into this build has.
        phraseFile().writeText("было в файле")

        assertTrue(vault.keep("sync early up"))
        assertEquals(PhraseKept.KEYRING, vault.where)
        assertEquals("sync early up", keyring.held)
        assertFalse(phraseFile().exists(), "файл должен уйти, когда связка приняла фразу")
        assertEquals("sync early up", vault.phrase())
    }

    @Test
    fun withoutAKeyringThePhraseGoesIntoAFileOnlyItsOwnerCanRead() {
        val vault = PhraseVault(directory, Absent())

        assertFalse(vault.keep("sync early up"), "без связки это не keyring")
        assertEquals(PhraseKept.FILE, vault.where)
        assertEquals("sync early up", phraseFile().readText())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(phraseFile().toPath()),
            "права должны быть только у владельца"
        )
        // And a fresh vault over the same directory finds it, which is what a restart is.
        assertEquals("sync early up", PhraseVault(directory, Absent()).phrase())
    }

    @Test
    fun aKeyringThatWillNotGiveThePhraseBackIsNotTrustedWithIt() {
        val keyring = Forgetful()
        val vault = PhraseVault(directory, keyring)

        assertFalse(vault.keep("sync early up"))
        assertEquals(1, keyring.writes)
        // The whole rule: it was offered, it was read back, the answer was wrong, so the phrase
        // went somewhere this device can actually read it again.
        assertEquals(PhraseKept.FILE, vault.where)
        assertEquals("sync early up", phraseFile().readText())
        assertEquals("sync early up", vault.phrase())
    }

    @Test
    fun forgettingTakesItOutOfBoth() {
        val keyring = Working()
        val vault = PhraseVault(directory, keyring)
        vault.keep("sync early up")
        // A file left over from before the keyring took it.
        phraseFile().writeText("sync early up")

        assertTrue(vault.keep(""))

        assertNull(keyring.held)
        assertFalse(phraseFile().exists())
        assertEquals(PhraseKept.PLAIN, vault.where)
        assertNull(vault.phrase())
    }

    @Test
    fun whatTheKeyringHoldsWinsOverAFileLeftBehind() {
        val keyring = Working().also { it.held = "в связке" }
        phraseFile().writeText("в файле")

        val vault = PhraseVault(directory, keyring)

        assertEquals("в связке", vault.phrase())
        assertEquals(PhraseKept.KEYRING, vault.where)
        // Asked once and then remembered: this is read on every pass, and each read would
        // otherwise be a round trip over the session bus.
        assertEquals("в связке", vault.phrase())
        assertEquals(1, keyring.reads)
    }

    @Test
    fun aLockedKeyringIsNotTheSameAsNoPhrase() {
        val keyring = Locked()
        // A file left over from an older build, and settings that may still hold a copy. Neither
        // may be reached for: the owner put the phrase in the keyring and locked it, and answering
        // that with a weaker copy — or with nothing, which means writing in the clear — is to
        // answer a question nobody asked.
        phraseFile().writeText("в файле")

        val vault = PhraseVault(directory, keyring)

        assertNull(vault.phrase())
        assertTrue(vault.locked, "заперто, а сказано иначе")
        assertEquals(PhraseKept.KEYRING, vault.where)
        // And nothing was written over it.
        assertEquals(0, keyring.writes)
    }

    @Test
    fun anUnlockedKeyringIsNotLocked() {
        val vault = PhraseVault(directory, Working().also { it.held = "sync early up" })

        assertEquals("sync early up", vault.phrase())
        assertFalse(vault.locked)
    }

    @Test
    fun noKeyringAtAllIsNotLockedEither() {
        // The difference that matters: a machine with no keyring has no phrase to be denied, so
        // sync carries on in the clear exactly as it did before any of this existed.
        val vault = PhraseVault(directory, Absent())

        assertNull(vault.phrase())
        assertFalse(vault.locked)
    }
}
