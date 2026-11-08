package music.ai.recommend.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * The phrase in the device's own keystore, on the device, because that is the only place the
 * keystore exists: there is no Android keystore on a JVM, and a fake one would prove nothing about
 * the thing this class is for.
 *
 * Run against a preferences file and a key of this test's own. The app's phrase is the owner's
 * actual secret and the key that wraps it is the only way back to their analysed library — a test
 * that wrote over either would break their sync to prove a point.
 */
@RunWith(AndroidJUnit4::class)
class PhraseVaultTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val prefsName = "phrase_vault_test_prefs"
    private val alias = "aura-sync-phrase-test"

    private fun vault() = PhraseVault(context, prefsName, alias)

    @Before
    @After
    fun forgetEverythingOfOurOwn() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }

    @Test
    fun aPhraseIsKeptWhereTheAppItselfCannotReadIt() {
        assertTrue("keystore не принял фразу", vault().keep("sync early up"))

        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val stored = prefs.all.values.map { it.toString() }
        // Nothing stored says the phrase. What is there is a nonce and a ciphertext, which are no
        // use without a key this app cannot export.
        for (value in stored) assertNotEquals("sync early up", value)
        assertTrue("ничего не записано", stored.isNotEmpty())
        assertNull("открытая фраза осталась", prefs.getString("sync_phrase", null))

        // And it comes back, which is the other half of being kept at all.
        assertEquals("sync early up", vault().phrase())
    }

    @Test
    fun aPhraseInPlainPreferencesIsMovedInOnFirstSight() {
        // What a phone updating into this build has: the phrase where it always was.
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString("sync_phrase", "sync early up").commit()

        // Reading it is what moves it, so nothing has to be asked of the owner.
        assertEquals("sync early up", vault().phrase())

        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        assertNull("открытая фраза должна уйти", prefs.getString("sync_phrase", null))
        assertEquals("sync early up", vault().phrase())
    }

    @Test
    fun theKeptPhraseIsWhereTheDeviceSaysItIs() {
        vault().keep("sync early up")
        val where = vault().also { it.phrase() }.where
        // Hardware on a phone with a secure element, the keystore on one without; never the
        // preferences, which is what this whole class is for.
        assertNotEquals(PhraseVault.Where.PLAIN, where)
    }

    @Test
    fun forgettingLeavesNothingBehind() {
        vault().keep("sync early up")
        assertTrue(vault().keep(""))

        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        assertTrue("что-то осталось: ${prefs.all}", prefs.all.isEmpty())
        assertNull(vault().phrase())
    }

    @Test
    fun aPhraseWrappedByAKeyThatIsGoneIsAskedForAgainRatherThanGuessedAt() {
        vault().keep("sync early up")
        // What a restore onto another phone looks like, or a factory reset of the secure element:
        // the wrapped bytes are still there and the key is not.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

        assertNull("нечитаемое не должно выдаваться за фразу", vault().phrase())
        // Asked once rather than on every pass: the unreadable bytes are let go of.
        assertTrue(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all.isEmpty()
        )
    }
}
