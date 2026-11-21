package music.ai.recommend.platform

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a desktop keeps secrets, as little of it as keeping one phrase needs.
 *
 * An interface rather than the calls themselves so that what [PhraseVault] does when this is
 * absent, locked or broken can be tested at all: the real one talks to the owner's actual keyring,
 * and a test that exercised the fallbacks against it would be writing into their passwords on every
 * build.
 */
interface Keyring {

    /** What the keyring had to say when asked. */
    sealed interface Kept {

        /** The phrase itself. */
        data class Phrase(val text: String) : Kept

        /** Nothing stored, no service, or a service that will not open. */
        data object None : Kept

        /**
         * Something is stored here and this process may not read it.
         *
         * Told apart from [None] because the two call for opposite behaviour. Nothing stored
         * means the owner never set a phrase, and then writing in the clear is their choice.
         * Locked means they did set one, and writing in the clear would publish into the sync
         * folder exactly what they asked to have sealed — a locked keyring must stop sync, not
         * downgrade it.
         */
        data object Locked : Kept
    }

    /** What is in the keyring, as far as this process is allowed to know. */
    fun read(): Kept

    /** @return true when the keyring took it. */
    fun write(phrase: String): Boolean

    /** Takes it out, if it is there. */
    fun forget()
}

/**
 * The Secret Service over the session bus: gnome-keyring or KWallet, whichever the desktop runs.
 *
 * The same service the browser keeps its passwords in, and the app already speaks D-Bus — that is
 * how the media keys work — so this needs no new dependency.
 *
 * Everything degrades quietly. There is no session bus on a server, and none on Windows or macOS
 * where this same build runs, and a missing keyring is not an error but an answer: see
 * [PhraseVault], which then keeps the phrase in a file only the owner can read.
 */
class SecretServiceKeyring : Keyring {

    /**
     * Whether the owner has already closed the prompt in this run.
     *
     * One no is a no. Without this, every sync pass that wanted the phrase would raise the dialog
     * again, and a refusal would become a thing to keep refusing.
     */
    @Volatile
    private var refused = false

    override fun read(): Keyring.Kept = inSession { service, connection, session ->
        val found = service.SearchItems(ATTRIBUTES)
        var item = found.unlocked.firstOrNull()
        if (item == null && found.locked.isNotEmpty()) {
            // A locked keyring is answered by asking the owner, which is what every other client
            // on this desktop does — the browser included. It is worth asking because the vault
            // keeps what it reads for the life of the process, so this is one dialog when the app
            // starts and not one per sync pass.
            if (unlock(service, connection, found.locked)) {
                item = service.SearchItems(ATTRIBUTES).unlocked.firstOrNull()
            }
            if (item == null) {
                println("Sync: the phrase is in the keyring, and it stayed locked")
                // Not "no phrase". The caller must be able to tell the difference, because one of
                // the two answers means "write in the clear" and the other means "do not write".
                return@inSession Keyring.Kept.Locked
            }
        }
        val path = item ?: return@inSession Keyring.Kept.None
        val secret = connection.getRemoteObject(SECRETS, path.path, SecretItem::class.java)
            .GetSecret(session)
        val text = String(secret.value, Charsets.UTF_8)
        if (text.isEmpty()) Keyring.Kept.None else Keyring.Kept.Phrase(text)
    } ?: Keyring.Kept.None

    override fun write(phrase: String): Boolean = inSession { service, connection, session ->
        val collection = connection.getRemoteObject(
            SECRETS, DEFAULT_COLLECTION, SecretCollection::class.java
        )
        val made = collection.CreateItem(
            mapOf(
                "org.freedesktop.Secret.Item.Label" to Variant(LABEL),
                "org.freedesktop.Secret.Item.Attributes" to Variant(ATTRIBUTES, "a{ss}")
            ),
            Secret(session, ByteArray(0), phrase.toByteArray(Charsets.UTF_8), CONTENT_TYPE),
            true
        )
        // A prompt instead of an item means a locked collection. Answering it is what completes
        // the creation, so the owner sees one dialog and the phrase lands where they put their
        // other secrets — rather than quietly falling back to a file beside the music.
        if (made.item.path != "/") true
        else made.prompt.path != "/" && answer(connection, made.prompt)
    } ?: false

    override fun forget() {
        inSession { service, connection, _ ->
            val found = service.SearchItems(ATTRIBUTES)
            // Asked for here too: forgetting is something the owner just chose to do, and a
            // phrase that stayed in a locked keyring because nobody asked is a phrase they believe
            // they deleted.
            if (found.locked.isNotEmpty()) unlock(service, connection, found.locked)
            for (item in service.SearchItems(ATTRIBUTES).unlocked) {
                runCatching {
                    connection.getRemoteObject(SECRETS, item.path, SecretItem::class.java).Delete()
                }
            }
        }
    }

    /**
     * Unlocks what the search could not open, asking the owner if the service wants it asked.
     *
     * The service answers in one of two ways: the objects are already open, or here is a prompt to
     * put in front of the owner. Only the second needs anything of us, and what it needs is to be
     * shown and then waited for — the dialog belongs to the keyring, not to this app, so no
     * password ever passes through here.
     *
     * @return true when something was unlocked.
     */
    private fun unlock(
        service: SecretService,
        connection: DBusConnection,
        locked: List<DBusPath>
    ): Boolean {
        if (refused) return false
        val outcome = runCatching { service.Unlock(locked) }.getOrNull() ?: return false
        if (outcome.unlocked.isNotEmpty()) return true
        if (outcome.prompt.path == "/") return false
        return answer(connection, outcome.prompt)
    }

    /**
     * Shows one prompt and waits for the owner to deal with it.
     *
     * Waited for by the signal the service promises rather than by polling, and bounded, because
     * a dialog nobody is sitting in front of would otherwise hold this thread for the life of the
     * app. A dismissal is remembered for the rest of the process: somebody who closes the dialog
     * has said no, and asking again on the next pass would be nagging rather than asking.
     */
    private fun answer(connection: DBusConnection, prompt: DBusPath): Boolean {
        val done = CountDownLatch(1)
        val dismissed = AtomicBoolean(false)
        val handler = DBusSigHandler<SecretPrompt.Completed> { signal ->
            if (signal.path == prompt.path) {
                dismissed.set(signal.dismissed)
                done.countDown()
            }
        }
        return try {
            connection.addSigHandler(SecretPrompt.Completed::class.java, handler)
            connection.getRemoteObject(SECRETS, prompt.path, SecretPrompt::class.java).Prompt("")
            when {
                !done.await(PROMPT_WAIT_SECONDS, TimeUnit.SECONDS) -> {
                    // Nobody answered. Dismissed rather than left open, so the keyring is not left
                    // holding a dialog this app has stopped waiting for.
                    runCatching {
                        connection.getRemoteObject(SECRETS, prompt.path, SecretPrompt::class.java)
                            .Dismiss()
                    }
                    println("Sync: nobody answered the keyring prompt")
                    false
                }
                dismissed.get() -> {
                    refused = true
                    println("Sync: the keyring prompt was dismissed; the phrase stays locked")
                    false
                }
                else -> true
            }
        } catch (e: Exception) {
            false
        } finally {
            runCatching {
                connection.removeSigHandler(SecretPrompt.Completed::class.java, handler)
            }
        }
    }

    private fun <T> inSession(
        body: (SecretService, org.freedesktop.dbus.connections.impl.DBusConnection, DBusPath) -> T?
    ): T? = try {
        DBusConnectionBuilder.forSessionBus().build().use { connection ->
            val service = connection.getRemoteObject(SECRETS, SERVICE_PATH, SecretService::class.java)
            val opened = service.OpenSession(PLAIN, Variant(""))
            body(service, connection, opened.result)
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SECRETS = "org.freedesktop.secrets"
        const val SERVICE_PATH = "/org/freedesktop/secrets"
        const val DEFAULT_COLLECTION = "/org/freedesktop/secrets/aliases/default"
        const val LABEL = "AiMusic sync phrase"
        const val CONTENT_TYPE = "text/plain; charset=utf8"

        /**
         * The session is opened as `plain`, so the bytes are not encrypted between this app and the
         * keyring. That is what the session bus is: a socket in the owner's own runtime directory
         * that only they can open. The alternative the spec offers is a Diffie-Hellman exchange
         * against a daemon already running as the same user, which would be ceremony and not
         * security.
         */
        const val PLAIN = "plain"

        /**
         * How long one prompt is waited for.
         *
         * Two minutes: long enough that somebody who stepped away from the keyboard can still come
         * back and type their password, short enough that a dialog raised on a machine with nobody
         * at it does not hold a thread for the rest of the day.
         */
        const val PROMPT_WAIT_SECONDS = 120L

        /**
         * What the item is called in the keyring. Two attributes rather than one because that is
         * how every other client does it, and because it leaves room for a second secret later
         * without the first having to be found by guesswork.
         */
        val ATTRIBUTES = mapOf("service" to "aimusic", "key" to "sync-phrase")
    }
}

/** The Secret Service, as much of it as keeping one phrase needs. */
@DBusInterfaceName("org.freedesktop.Secret.Service")
interface SecretService : DBusInterface {
    fun OpenSession(algorithm: String, input: Variant<*>): OpenedSession
    fun SearchItems(attributes: Map<String, String>): FoundItems

    /**
     * @return what it opened without asking, and a prompt for the rest — `/` when there is none.
     */
    fun Unlock(objects: List<DBusPath>): UnlockOutcome
}

/**
 * A dialog the keyring puts in front of the owner, which this app can only show and await.
 *
 * The password goes from the owner to the keyring and is never seen here, which is the whole
 * reason to use the service rather than ask for a password ourselves.
 */
@DBusInterfaceName("org.freedesktop.Secret.Prompt")
interface SecretPrompt : DBusInterface {

    /** Shows it. The empty string means this app has no window to attach it to. */
    fun Prompt(windowId: String)

    /** Takes it away again, for a prompt nobody answered. */
    fun Dismiss()

    /** Raised once the owner has dealt with it, one way or the other. */
    class Completed(
        path: String,
        val dismissed: Boolean,
        val result: Variant<*>
    ) : DBusSignal(path, dismissed, result)
}

@DBusInterfaceName("org.freedesktop.Secret.Collection")
interface SecretCollection : DBusInterface {
    fun CreateItem(
        properties: Map<String, Variant<*>>,
        secret: Secret,
        replace: Boolean
    ): CreatedItem
}

@DBusInterfaceName("org.freedesktop.Secret.Item")
interface SecretItem : DBusInterface {
    fun GetSecret(session: DBusPath): Secret
    fun Delete(): DBusPath
}

/** The session, and whatever the service wants to say about how it was opened. */
class OpenedSession(
    @Position(0) val output: Variant<*>,
    @Position(1) val result: DBusPath
) : Tuple()

/** What a search found, and what it found but could not open. */
class FoundItems(
    @Position(0) val unlocked: List<DBusPath>,
    @Position(1) val locked: List<DBusPath>
) : Tuple()

/** The item, and the prompt that would have to be answered first — `/` when there is none. */
class CreatedItem(
    @Position(0) val item: DBusPath,
    @Position(1) val prompt: DBusPath
) : Tuple()

/** What was opened without asking, and the prompt for whatever was not. */
class UnlockOutcome(
    @Position(0) val unlocked: List<DBusPath>,
    @Position(1) val prompt: DBusPath
) : Tuple()

/** A secret on the wire: which session it belongs to, and the bytes. */
class Secret(
    @Position(0) val session: DBusPath,
    @Position(1) val parameters: ByteArray,
    @Position(2) val value: ByteArray,
    @Position(3) val contentType: String
) : Struct()
