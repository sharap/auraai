package music.ai.recommend.platform

import java.io.File

/**
 * Where this machine turned out to be able to keep the phrase.
 *
 * Shown to the owner, because the promise is otherwise unverifiable from the outside and because
 * the last of the three is one they may want to do something about.
 */
enum class PhraseKept {
    /** In the desktop's own secret service — the same place the browser keeps its passwords. */
    KEYRING,

    /** In a file of this app's own that only the owner can read. */
    FILE,

    /** Still in the settings file, because neither of the above would take it. */
    PLAIN
}

/**
 * Keeps the owner's phrase — the one secret this whole arrangement has — wherever the platform
 * keeps secrets. See the implementation for what that means here.
 */
expect class PhraseVault(directory: File) {

    /** Where the phrase is kept as things stand; meaningful after the first read or write. */
    val where: PhraseKept

    /**
     * True when a phrase is kept here that this process was not allowed to read — a keyring the
     * owner left locked, and a prompt they closed.
     *
     * Its own answer, and not folded into [phrase] returning null, because the two call for
     * opposite behaviour. No phrase means the owner never set one, and sync then writes in the
     * clear as it always has. Locked means they did set one, and writing in the clear would put
     * into the folder exactly what they asked to have sealed.
     */
    val locked: Boolean

    /** The phrase, or null when the owner has not set one — or when it is [locked]. */
    fun phrase(): String?

    /** Keeps it, or forgets it when given nothing. @return true when the keyring took it. */
    fun keep(phrase: String): Boolean
}
