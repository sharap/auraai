package music.ai.recommend.ai

import music.ai.recommend.platform.BundledResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * That this port ships the vocabulary the Russian encoder was trained with.
 *
 * The model is downloaded but its WordPiece vocabulary travels inside the jar, so the two can drift
 * apart in a way nothing else would notice: a tokenizer that disagrees with the one the model was
 * trained with produces embeddings that look plausible and mean nothing, and a search just quietly
 * returns the wrong tracks.
 *
 * The expected ids come from `app/src/test/resources/ru_tokenizer_reference.json`, which `export.py`
 * wrote from `transformers`' own tokenizer; the phone's WordPieceTokenizerTest checks the whole file
 * against its copy of the vocabulary. The question here is narrower — whether the copy on this
 * port's classpath is that same vocabulary.
 */
class RuVocabularyTest {

    private val cases = mapOf(
        "спокойное пианино" to listOf(2L, 32007L, 626L, 69432L, 3L),
        "грустная скрипка" to listOf(2L, 35613L, 18355L, 38349L, 872L, 3L),
        "что-нибудь для бега" to listOf(2L, 1046L, 17L, 30027L, 871L, 34357L, 3L),
        "drum and bass" to listOf(2L, 12009L, 537L, 4350L, 3L)
    )

    @Test
    fun `the shipped vocabulary tokenizes the way the reference does`() {
        assertNotNull(BundledResource.open(VOCAB), "$VOCAB is not on the classpath").close()
        val tokenizer = WordPieceTokenizer { checkNotNull(BundledResource.open(VOCAB)) }
        for ((text, expected) in cases) {
            assertEquals(expected, tokenizer.tokenize(text).toList(), "tokens for «$text»")
        }
    }

    private companion object {
        const val VOCAB = "ru_vocab.txt"
    }
}
