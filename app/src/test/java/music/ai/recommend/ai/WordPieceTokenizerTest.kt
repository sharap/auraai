package music.ai.recommend.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin tokenizer against the Python one the model was trained with.
 *
 * This is the test that matters most in the Russian search: a tokenizer that splits a word even
 * slightly differently hands the model ids it has never seen in that order, and the result is an
 * embedding that looks perfectly normal — unit length, in the right part of the space — and means
 * nothing. The reference was produced by `export.py` from `transformers`' own tokenizer.
 */
class WordPieceTokenizerTest {

    private val vocab = File("src/main/assets/ru_vocab.txt")
    private val reference = File("src/test/resources/ru_tokenizer_reference.json")

    private fun tokenizer() = WordPieceTokenizer { vocab.inputStream() }

    private fun expected(): Map<String, List<Int>> {
        val type = object : TypeToken<Map<String, List<Int>>>() {}.type
        return Gson().fromJson(reference.readText(), type)
    }

    @Test
    fun itAgreesWithTheTokenizerTheModelWasTrainedWith() {
        assumeTrue("the vocabulary ships in assets", vocab.exists())
        val tokenizer = tokenizer()
        for ((text, ids) in expected()) {
            assertEquals(
                "tokens for $text",
                ids,
                tokenizer.tokenize(text).map { it.toInt() }
            )
        }
    }

    /** Punctuation is cut off into tokens of its own, which is where a hand-written split goes wrong. */
    @Test
    fun punctuationAndDigitsSplitTheWayTheReferenceDoes() {
        assumeTrue(vocab.exists())
        val tokenizer = tokenizer()
        assertEquals(expected()["музыка 90-х"], tokenizer.tokenize("музыка 90-х").map { it.toInt() })
        assertEquals(expected()["что-нибудь для бега"], tokenizer.tokenize("что-нибудь для бега").map { it.toInt() })
    }

    @Test
    fun theLengthLimitKeepsTheSpecialTokens() {
        assumeTrue(vocab.exists())
        val ids = tokenizer().tokenize("спокойное пианино " .repeat(40), maxLength = 12)
        assertEquals(12, ids.size)
        assertEquals(2L, ids.first())
        assertEquals(3L, ids.last())
    }

    @Test
    fun emptyTextIsJustTheSpecialTokens() {
        assumeTrue(vocab.exists())
        assertEquals(listOf(2L, 3L), tokenizer().tokenize("   ").toList())
    }

    /** A non-breaking space is a space: pasted from a web page, it must not glue two words together. */
    @Test
    fun aNonBreakingSpaceSeparatesWords() {
        assumeTrue(vocab.exists())
        val tokenizer = tokenizer()
        assertEquals(
            tokenizer.tokenize("спокойное пианино").toList(),
            tokenizer.tokenize("спокойное пианино").toList()
        )
    }
}
