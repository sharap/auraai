package music.ai.recommend.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which door a query goes through.
 *
 * Worth a test of its own because both ports ask it and the answer has to be the same on both: a
 * phone that sends a query to the student while the desktop sends it to CLAP does not fail, it
 * answers differently, and the two devices quietly disagree about what the library holds.
 */
class RussianTextTest {

    @Test
    fun `cyrillic goes to the student`() {
        assertTrue(RussianText.handles("спокойное пианино"))
        assertTrue(RussianText.handles("Мельница"))
        assertTrue(RussianText.handles("ёлка"))
        assertTrue(RussianText.handles("Ёлка"))
    }

    @Test
    fun `plain english does not`() {
        assertFalse(RussianText.handles("calm piano"))
        assertFalse(RussianText.handles(""))
        assertFalse(RussianText.handles("drum & bass 2024"))
    }

    /** A mixed query goes to the student: a fifth of its training was the English side. */
    @Test
    fun `one cyrillic letter is enough`() {
        assertTrue(RussianText.handles("Мельница remix"))
        assertTrue(RussianText.handles("live at Зелёный театр"))
    }
}
