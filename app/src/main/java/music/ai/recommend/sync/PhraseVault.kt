package music.ai.recommend.sync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the owner's phrase is kept, which is the one secret this whole arrangement has.
 *
 * Everything rests on it: the key that seals a batch is PBKDF2 of the phrase salted with the
 * writing device's id, and the key that opens a conversation is PBKDF2 of the same phrase under a
 * salt of its own. A device that holds the phrase is one of the owner's devices — that *is* the
 * trust model — so where the phrase sits is where the trust sits, and until now it sat in plain
 * preferences.
 *
 * Here it is wrapped with a key the app never sees: AES-256-GCM in the Android keystore, generated
 * once, non-exportable, and on hardware that has a secure element it never leaves it. What is
 * stored beside it is the nonce and the ciphertext, which are no use to anybody without the
 * keystore, and the keystore is no use outside this app on this device.
 *
 * The phrase itself, and not a key derived from it, because reading what another device wrote needs
 * PBKDF2 of the phrase salted with *that* device's id: a device met for the first time has to be
 * readable, so the secret kept has to be the one all the derivations start from.
 *
 * Nothing here can lose a phrase. The plain one is removed only after the wrapped one has been read
 * back and found to say the same thing, and a keystore that refuses to work at all leaves the
 * phrase where it was and says so, because a phone that cannot sync is better than a phone whose
 * library has quietly stopped being readable by the other device.
 */
class PhraseVault(
    context: Context,
    /**
     * Which preferences file and which keystore key, so that a test on a real device can prove
     * this works without touching the owner's own phrase or deleting the key that wraps it. The
     * app always uses the defaults; nothing else may.
     */
    private val prefsName: String = PREFS,
    private val alias: String = ALIAS
) {

    /** How well the phrase is kept, for the line the settings screen shows. */
    enum class Where {
        /** Wrapped by a key that lives in the device's secure element. */
        HARDWARE,

        /** Wrapped by a keystore key, which this device keeps outside the app's own storage. */
        KEYSTORE,

        /** In the app's own preferences, because the keystore would not have it. */
        PLAIN
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Volatile
    private var held: Where = Where.PLAIN

    /** Where the phrase is kept as things stand; meaningful after the first read or write. */
    val where: Where get() = held

    /** The phrase, or null when the owner has not set one. */
    fun phrase(): String? {
        val wrapped = prefs.getString(KEY_WRAPPED, null)
        if (wrapped != null) {
            unwrap(wrapped)?.let {
                held = security()
                return it.takeIf { text -> text.isNotEmpty() }
            }
            // Wrapped and unreadable: the keystore key has gone — a restore onto another phone, a
            // factory reset of the secure element, the app's data moved. The owner has to type the
            // phrase again, which is the honest answer, and the unreadable bytes go so that this
            // is asked once rather than on every pass.
            Log.w(TAG, "The kept phrase will not open; the owner will have to type it again")
            prefs.edit().remove(KEY_WRAPPED).apply()
        }
        val plain = prefs.getString(KEY_PLAIN, null) ?: return null
        // Moved in on first sight, and the plain one goes only once the wrapped one reads back the
        // same. Until then nothing is lost either way.
        if (plain.isNotEmpty() && keep(plain)) {
            Log.i(TAG, "The phrase now lives in the keystore")
        }
        return plain.takeIf { it.isNotEmpty() }
    }

    /**
     * Keeps the phrase, or forgets it when given nothing.
     *
     * @return true when it was kept where this class means to keep it. False means it is in the
     *   preferences instead, which the owner is told about rather than left to assume.
     */
    fun keep(phrase: String): Boolean {
        if (phrase.isEmpty()) {
            forget()
            return true
        }
        val wrapped = wrap(phrase)
        if (wrapped == null) {
            // The keystore would not do it. Writing the phrase plainly is worse than this class
            // promises and better than a device that cannot sync at all, and the screen says which
            // of the two happened.
            prefs.edit().putString(KEY_PLAIN, phrase).remove(KEY_WRAPPED).apply()
            held = Where.PLAIN
            return false
        }
        prefs.edit().putString(KEY_WRAPPED, wrapped).apply()
        // Read back before the plain one is let go of: a keystore that encrypts and then cannot
        // decrypt is a thing that happens on real phones, and finding out later would mean a
        // library that has silently stopped being readable by the other device.
        if (unwrap(wrapped) != phrase) {
            Log.e(TAG, "The keystore encrypted the phrase and would not give it back; keeping it plainly")
            prefs.edit().remove(KEY_WRAPPED).putString(KEY_PLAIN, phrase).apply()
            held = Where.PLAIN
            return false
        }
        prefs.edit().remove(KEY_PLAIN).apply()
        held = security()
        return true
    }

    private fun forget() {
        prefs.edit().remove(KEY_WRAPPED).remove(KEY_PLAIN).apply()
        runCatching { keystore()?.deleteEntry(alias) }
        held = Where.PLAIN
    }

    private fun wrap(phrase: String): String? {
        val key = key() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val sealed = cipher.doFinal(phrase.toByteArray(Charsets.UTF_8))
            base64(cipher.iv) + SEPARATOR + base64(sealed)
        } catch (e: Exception) {
            Log.e(TAG, "Could not keep the phrase in the keystore", e)
            null
        }
    }

    private fun unwrap(stored: String): String? {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) return null
        val key = key() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes(parts[0])))
            String(cipher.doFinal(bytes(parts[1])), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun keystore(): KeyStore? = try {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }
    } catch (e: Exception) {
        Log.e(TAG, "No keystore on this device", e)
        null
    }

    /** The wrapping key, made once and then found again by name. */
    private fun key(): SecretKey? {
        val store = keystore() ?: return null
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            ?: generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey? = try {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // No screen lock required: sync runs on a pass, and a phrase that could only be read
            // while the owner is looking at the phone would make this a feature that works when
            // nobody needs it.
            .setUserAuthenticationRequired(false)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
    } catch (e: StrongBoxUnavailableException) {
        // Asked for the secure element and this phone has none. Said quietly: the keystore key is
        // still outside the app's storage, which is the point.
        null
    } catch (e: Exception) {
        Log.e(TAG, "Could not make a wrapping key" + if (strongBox) " in the secure element" else "", e)
        null
    }

    /** Whether the key that wraps the phrase sits in hardware, as far as this device will say. */
    private fun security(): Where = try {
        val store = keystore() ?: return Where.KEYSTORE
        val key = (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: return Where.KEYSTORE
        val factory = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE
        } else {
            @Suppress("DEPRECATION")
            info.isInsideSecureHardware
        }
        if (hardware) Where.HARDWARE else Where.KEYSTORE
    } catch (e: Exception) {
        Where.KEYSTORE
    }

    private fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun bytes(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val TAG = "PhraseVault"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "aura-sync-phrase"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val SEPARATOR = ":"

        /**
         * The app's own preferences file, and the same key name the plain phrase had, so that a
         * phone updating into this build finds its phrase where it left it.
         */
        const val PREFS = "music_prefs"
        const val KEY_PLAIN = "sync_phrase"
        const val KEY_WRAPPED = "sync_phrase_kept"
    }
}
