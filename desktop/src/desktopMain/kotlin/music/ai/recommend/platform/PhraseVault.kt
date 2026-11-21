package music.ai.recommend.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Where the owner's phrase is kept, which is the one secret this whole arrangement has.
 *
 * Everything rests on it: the key that seals a batch is PBKDF2 of the phrase salted with the
 * writing device's id, and the key that opens a conversation is PBKDF2 of the same phrase under a
 * salt of its own. A device that holds the phrase is one of the owner's devices — that *is* the
 * trust model. Until now it sat in `settings.json` in plain text, written with whatever permissions
 * the system gives by default, which on this machine is 644: readable by every user on it.
 *
 * So it goes to the desktop's own [Keyring] — the same service the browser keeps its passwords in.
 * Where there is none, and there is none on a server or on the other two platforms this build
 * targets, it goes to a file of its own that only the owner can read. That is weaker, and it is
 * said out loud rather than quietly assumed: the settings screen names which of the two happened.
 *
 * The phrase itself and not a key derived from it, because reading what another device wrote needs
 * PBKDF2 of the phrase salted with *that* device's id: a device met for the first time has to be
 * readable, so what is kept has to be what all the derivations start from.
 *
 * **Nothing here can lose a phrase.** A new place is trusted only after it has been read back and
 * found to say the same thing, and the plain copy in the settings is cleared by the caller only
 * once [where] says somewhere better has it. A machine that cannot keep a secret well is better
 * than a machine whose library has quietly stopped being readable by the other device.
 *
 * Not a copy of the phone's vault: there it is a keystore key that never leaves the secure element,
 * here it is somebody else's daemon over a socket. Same shape, nothing else in common, so neither
 * pretends to be shared code.
 */
actual class PhraseVault actual constructor(private val directory: File) {

    /**
     * Whatever this desktop keeps secrets in. Not a constructor parameter, because the one the
     * common code declares takes only the directory — and a test needs to hand in a keyring that
     * is not the owner's own; hence the second constructor below.
     */
    private var keyring: Keyring = SecretServiceKeyring()

    internal constructor(directory: File, keyring: Keyring) : this(directory) {
        this.keyring = keyring
    }

    @Volatile
    private var held: PhraseKept = PhraseKept.PLAIN

    @Volatile
    private var remembered: String? = null

    /**
     * Whether a phrase is kept here that this process could not read.
     *
     * Its own answer, because "no phrase" and "a phrase I may not have" call for opposite
     * behaviour from everything above: the first lets sync write in the clear, which is what a
     * library with no phrase has always done, and the second must stop it. See [Keyring.Kept].
     */
    @Volatile
    private var lockedAway = false

    /** Where the phrase is kept as things stand; meaningful after the first read or write. */
    actual val where: PhraseKept get() = held

    /** True when there is a phrase here and the owner has not let this process read it. */
    actual val locked: Boolean get() = lockedAway

    /**
     * The phrase, or null when the owner has not set one.
     *
     * Kept in memory once read, because this is asked on every pass and each ask would otherwise be
     * a round trip over the session bus. The process holding the secret in a variable is the one
     * allowed to read it, so that gives nothing away.
     */
    actual fun phrase(): String? {
        remembered?.let { return it.takeIf { text -> text.isNotEmpty() } }
        when (val kept = keyring.read()) {
            is Keyring.Kept.Phrase -> {
                held = PhraseKept.KEYRING
                lockedAway = false
                remembered = kept.text
                return kept.text
            }
            Keyring.Kept.Locked -> {
                // Said and then left alone: there is a phrase, it is not ours to read right now,
                // and nothing below should be tried instead. Falling through to the file would
                // answer a locked keyring with a weaker copy, and falling through to nothing would
                // answer it by publishing in the clear.
                held = PhraseKept.KEYRING
                lockedAway = true
                return null
            }
            Keyring.Kept.None -> lockedAway = false
        }
        val file = phraseFile()
        if (file.isFile) {
            val text = runCatching { file.readText().trim() }.getOrNull()
            if (!text.isNullOrEmpty()) {
                held = PhraseKept.FILE
                remembered = text
                return text
            }
        }
        return null
    }

    /**
     * Keeps the phrase, or forgets it when given nothing.
     *
     * @return true when the keyring took it and gave it back. False means the file instead, which
     *   is what the screen then says.
     */
    actual fun keep(phrase: String): Boolean {
        if (phrase.isEmpty()) {
            forget()
            return true
        }
        if (keyring.write(phrase)) {
            // Read back before anything else is let go of: a keyring that accepts a secret and
            // then cannot give it back would mean a library that has quietly stopped being
            // readable by the other device.
            if ((keyring.read() as? Keyring.Kept.Phrase)?.text == phrase) {
                held = PhraseKept.KEYRING
                remembered = phrase
                runCatching { phraseFile().delete() }
                return true
            }
            println("Sync: the keyring took the phrase and would not give it back; using a file")
        }
        remembered = phrase
        return keepInFile(phrase)
    }

    /** A file only the owner can read: weaker than the keyring, better than the settings. */
    private fun keepInFile(phrase: String): Boolean {
        return try {
            if (!directory.exists()) directory.mkdirs()
            val file = phraseFile()
            file.writeText(phrase)
            // Before anybody else can get at it. A file that is 600 only after the fact was
            // readable in between, which for the one secret here is not good enough.
            runCatching {
                Files.setPosixFilePermissions(
                    file.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                )
            }
            held = PhraseKept.FILE
            false
        } catch (e: Exception) {
            println("Sync: could not keep the phrase anywhere of its own: ${e.message}")
            held = PhraseKept.PLAIN
            false
        }
    }

    private fun forget() {
        remembered = ""
        runCatching { keyring.forget() }
        runCatching { phraseFile().delete() }
        held = PhraseKept.PLAIN
    }

    private fun phraseFile() = File(directory, "phrase")
}
