package music.ai.recommend.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the tokenizer to the reference RoBERTa ByteLevel BPE.
 *
 * Expected ids were produced by the Hugging Face fast tokenizer shipped with
 * Xenova/larger_clap_music_and_speech (tokenizer.json), which vocab.json and merges.txt match
 * byte for byte. Needs the vocab in assets or already downloaded.
 */
@RunWith(AndroidJUnit4::class)
class BpeTokenizerTest {

    private lateinit var tokenizer: BpeTokenizer

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelRepository.getInstance(context)
        assertTrue(
            "vocab.json / merges.txt must be present to run this test",
            models.isAvailable(listOf(ModelAsset.VOCAB, ModelAsset.MERGES))
        )
        tokenizer = BpeTokenizer(models)
    }

    @Test
    fun matchesReferenceTokenization() {
        val expected = mapOf(
            "chill piano" to longArrayOf(0, 611, 1873, 13305, 2),
            "upbeat electronic dance music" to longArrayOf(0, 658, 13825, 5175, 3836, 930, 2),
            "sad acoustic guitar" to longArrayOf(0, 29, 625, 21979, 8669, 2),
            "heavy metal guitar riff" to longArrayOf(0, 18888, 4204, 8669, 31457, 2)
        )
        for ((text, ids) in expected) {
            assertArrayEquals(text, ids, tokenizer.tokenize(text))
        }
    }

    /** `<s>` = 0 and `</s>` = 2 come from the vocab, not from CLIP's 49406/49407. */
    @Test
    fun wrapsInRobertaSpecialTokens() {
        val ids = tokenizer.tokenize("guitar")
        assertEquals(0L, ids.first())
        assertEquals(2L, ids.last())
    }

    /** The graph has no attention_mask, so the sequence must not be padded out to a fixed length. */
    @Test
    fun doesNotPadShortInput() {
        assertEquals(3, tokenizer.tokenize("music").size) // <s> music </s>
        assertEquals(4, tokenizer.tokenize("jazz").size)  // <s> j azz </s>
    }

    @Test
    fun truncatesToMaxLength() {
        val long = List(200) { "music" }.joinToString(" ")
        assertEquals(77, tokenizer.tokenize(long, 77).size)
    }

    /** Casing is significant for RoBERTa; the old tokenizer lowercased everything. */
    @Test
    fun preservesCase() {
        assertFalse(tokenizer.tokenize("Guitar").contentEquals(tokenizer.tokenize("guitar")))
    }
}
