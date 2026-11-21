package music.ai.recommend.ai

import java.io.InputStream

/**
 * WordPiece tokenizer for the Russian query encoder.
 *
 * [BpeTokenizer] cannot be reused: CLAP's text tower is RoBERTa and reads byte-level BPE, while the
 * Russian encoder is a BERT (`cointegrated/rubert-tiny2`) and reads WordPiece. The two disagree
 * about everything — the vocabulary, how a word is split, even the special tokens.
 *
 * Follows `BertTokenizer` as the reference implementation does, with the settings this particular
 * checkpoint ships: text cleaned of control characters, Chinese characters spaced out, case and
 * accents left alone (`do_lower_case: false`, `strip_accents: null`), `##` for a continued word.
 * `ru_tokenizer_reference.json` holds what the Python tokenizer produces for a handful of queries,
 * and a unit test checks this against it — a tokenizer that disagrees with the one the model was
 * trained with produces embeddings that look plausible and mean nothing.
 *
 * The vocabulary is passed as a stream rather than a [ModelRepository], so the test can run on the
 * JVM against the file in `assets/` without an Android context.
 */
class WordPieceTokenizer(private val openVocab: () -> InputStream) {

    /** Token to id. One token per line, the line number being the id, as `export.py` writes it. */
    private val vocab: Map<String, Int> by lazy {
        val map = HashMap<String, Int>(VOCAB_HINT)
        openVocab().bufferedReader().use { reader ->
            var id = 0
            reader.forEachLine { line -> map[line] = id++ }
        }
        map
    }

    private val unk: Int by lazy { vocab[UNK] ?: 1 }
    private val cls: Long by lazy { (vocab[CLS] ?: 2).toLong() }
    private val sep: Long by lazy { (vocab[SEP] ?: 3).toLong() }

    /**
     * @return `[CLS] … [SEP]` token ids, at most [maxLength] of them.
     *
     * No padding: the graph takes a dynamic token count, and one query is one row.
     */
    fun tokenize(text: String, maxLength: Int = MAX_TOKENS): LongArray {
        val ids = ArrayList<Long>(minOf(maxLength, 16))
        ids.add(cls)
        outer@ for (word in split(clean(text))) {
            for (id in pieces(word)) {
                if (ids.size >= maxLength - 1) break@outer
                ids.add(id.toLong())
            }
        }
        ids.add(sep)
        return LongArray(ids.size) { ids[it] }
    }

    /**
     * BertNormalizer: control characters and the replacement character dropped, every kind of
     * whitespace folded to a plain space, Chinese characters given space of their own so each
     * becomes a token.
     */
    private fun clean(text: String): String = buildString(text.length) {
        for (c in text) {
            when {
                c.code == 0 || c.code == 0xFFFD || isControl(c) -> Unit
                isSpace(c) -> append(' ')
                isChinese(c) -> append(' ').append(c).append(' ')
                else -> append(c)
            }
        }
    }

    /**
     * BertPreTokenizer: split on whitespace, then cut punctuation off into tokens of its own, so
     * "90-х" becomes "90", "-", "х" — which is what the model was trained on.
     */
    private fun split(text: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                words.add(current.toString())
                current.setLength(0)
            }
        }
        for (c in text) {
            when {
                c == ' ' -> flush()
                isPunctuation(c) -> {
                    flush()
                    words.add(c.toString())
                }
                else -> current.append(c)
            }
        }
        flush()
        return words
    }

    /**
     * Greedy longest-match-first, the reference algorithm: take the longest prefix that is in the
     * vocabulary, then continue with `##` in front of what is left. A word with any part that
     * cannot be matched is one `[UNK]` in full, not a half-read word.
     */
    private fun pieces(word: String): List<Int> {
        if (word.length > MAX_WORD_CHARS) return listOf(unk)
        val out = ArrayList<Int>(4)
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (start < end) {
                val piece = if (start > 0) CONTINUED + word.substring(start, end) else word.substring(start, end)
                val id = vocab[piece]
                if (id != null) {
                    found = id
                    break
                }
                end--
            }
            if (found < 0) return listOf(unk)
            out.add(found)
            start = end
        }
        return out
    }

    /**
     * Whitespace as the reference tokenizer sees it, which is Unicode's White_Space property.
     *
     * Java's own [Char.isWhitespace] says no to the non-breaking spaces, so a query pasted from a
     * web page would keep a U+00A0 inside a word and tokenize to something else entirely.
     */
    private fun isSpace(c: Char): Boolean =
        c.isWhitespace() || c == '\u0085' || Character.getType(c).toByte() == Character.SPACE_SEPARATOR

    private fun isControl(c: Char): Boolean {
        if (c == '\t' || c == '\n' || c == '\r') return false
        return when (Character.getType(c).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE,
            Character.UNASSIGNED -> true
            else -> false
        }
    }

    /** The ASCII punctuation BERT adds to the Unicode categories, which `isPunctuation` misses. */
    private fun isPunctuation(c: Char): Boolean {
        val code = c.code
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private fun isChinese(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
    }

    companion object {
        /** What the student was trained on; the graph itself takes any length. */
        const val MAX_TOKENS = 48

        private const val CLS = "[CLS]"
        private const val SEP = "[SEP]"
        private const val UNK = "[UNK]"
        private const val CONTINUED = "##"

        /** The reference tokenizer's `max_input_chars_per_word`. */
        private const val MAX_WORD_CHARS = 100
        private const val VOCAB_HINT = 131_072
    }
}
