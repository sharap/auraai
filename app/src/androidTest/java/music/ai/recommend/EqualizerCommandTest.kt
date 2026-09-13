package music.ai.recommend

import android.content.ComponentName
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.MoreExecutors
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the equalizer switch the way the settings screen does — over a real session command to the
 * real service — so this covers the audio effect actually changing state, not just the flag in the
 * ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class EqualizerCommandTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var controller: MediaController
    private var originallyEnabled = true

    @Before
    fun connect() {
        val connected = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener({
                controller = future.get()
                connected.countDown()
            }, MoreExecutors.directExecutor())
        }
        assertTrue("could not reach PlaybackService", connected.await(20, TimeUnit.SECONDS))
        originallyEnabled = readParams().getBoolean("enabled", true)
    }

    @After
    fun disconnect() {
        setEnabled(originallyEnabled)
        instrumentation.runOnMainSync { controller.release() }
    }

    private fun send(action: String, args: Bundle): SessionResult {
        var result: SessionResult? = null
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val future = controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
            future.addListener({
                result = future.get()
                done.countDown()
            }, MoreExecutors.directExecutor())
        }
        assertTrue("$action timed out", done.await(10, TimeUnit.SECONDS))
        return result!!
    }

    private fun readParams(): Bundle {
        val result = send("GET_EQ_PARAMS", Bundle.EMPTY)
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        return result.extras
    }

    private fun setEnabled(enabled: Boolean) {
        val result = send(
            PlaybackService.COMMAND_SET_EQ_ENABLED,
            Bundle().apply { putBoolean("enabled", enabled) }
        )
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
    }

    private fun readAudioOptions(): Bundle {
        val result = send(PlaybackService.COMMAND_GET_AUDIO_OPTIONS, Bundle.EMPTY)
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        return result.extras
    }

    private fun setOption(command: String, enabled: Boolean) {
        val result = send(command, Bundle().apply { putBoolean("enabled", enabled) })
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
    }

    /** Both playback behaviours are settings now, so the service has to follow them at runtime. */
    @Test
    fun audioOptionsRoundTripThroughTheService() {
        val before = readAudioOptions()
        val pause = before.getBoolean(PlaybackService.KEY_PAUSE_ON_DISCONNECT, true)
        val focus = before.getBoolean(PlaybackService.KEY_HANDLE_AUDIO_FOCUS, true)
        try {
            setOption(PlaybackService.COMMAND_SET_PAUSE_ON_DISCONNECT, false)
            setOption(PlaybackService.COMMAND_SET_AUDIO_FOCUS, false)
            readAudioOptions().let {
                assertFalse(it.getBoolean(PlaybackService.KEY_PAUSE_ON_DISCONNECT, true))
                assertFalse(it.getBoolean(PlaybackService.KEY_HANDLE_AUDIO_FOCUS, true))
            }

            setOption(PlaybackService.COMMAND_SET_PAUSE_ON_DISCONNECT, true)
            setOption(PlaybackService.COMMAND_SET_AUDIO_FOCUS, true)
            readAudioOptions().let {
                assertTrue(it.getBoolean(PlaybackService.KEY_PAUSE_ON_DISCONNECT, false))
                assertTrue(it.getBoolean(PlaybackService.KEY_HANDLE_AUDIO_FOCUS, false))
            }
        } finally {
            setOption(PlaybackService.COMMAND_SET_PAUSE_ON_DISCONNECT, pause)
            setOption(PlaybackService.COMMAND_SET_AUDIO_FOCUS, focus)
        }
    }

    /**
     * Declaring the attributes is what lets the system route and mix the stream correctly, so they
     * must be set whatever the focus setting says.
     */
    @Test
    fun playerAnnouncesItselfAsMusic() {
        setOption(PlaybackService.COMMAND_SET_AUDIO_FOCUS, true)
        var attributes: androidx.media3.common.AudioAttributes? = null
        instrumentation.runOnMainSync { attributes = controller.audioAttributes }
        assertEquals(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC, attributes!!.contentType)
        assertEquals(androidx.media3.common.C.USAGE_MEDIA, attributes!!.usage)
    }

    @Test
    fun switchingTheEqualizerOffAndOnReachesTheEffect() {
        assumeTrue("no equalizer on this device", readParams().getInt("num_bands", 0) > 0)

        setEnabled(false)
        assertFalse("the effect should be off", readParams().getBoolean("enabled", true))

        setEnabled(true)
        assertTrue("the effect should be back on", readParams().getBoolean("enabled", false))
    }

    /** Switching off must not discard the curve, or turning it back on would reset the sound. */
    @Test
    fun bandLevelsSurviveBeingSwitchedOff() {
        val params = readParams()
        val bands = params.getInt("num_bands", 0)
        assumeTrue("no equalizer on this device", bands > 0)
        val original = params.getIntArray("band_levels")!!

        val max = params.getInt("max_level", 1500)
        val target = (max / 2)
        send("SET_EQ_BAND", Bundle().apply { putInt("band", 0); putInt("level", target) })
        val applied = readParams().getIntArray("band_levels")!![0]

        setEnabled(false)
        val whileOff = readParams().getIntArray("band_levels")!!
        setEnabled(true)
        val afterOn = readParams().getIntArray("band_levels")!!

        assertEquals("level should have been applied", applied, whileOff[0])
        assertArrayEquals("the curve changed across the toggle", whileOff, afterOn)

        // Put the device back the way the user had it.
        original.forEachIndexed { index, level ->
            send("SET_EQ_BAND", Bundle().apply { putInt("band", index); putInt("level", level) })
        }
    }
}
