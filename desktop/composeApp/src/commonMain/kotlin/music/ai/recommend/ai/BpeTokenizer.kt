package music.ai.recommend.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Byte-level BPE tokenizer for the CLAP text encoder.
 *
 * The encoder is RoBERTa-based (`tokenizer_class: RobertaTokenizer`, 50 265 entries, `<s>`/`</s>`
 * as bos/eos, no normalizer, ByteLevel pre-tokenizer with `add_prefix_space: false`), so this
 * follows the GPT-2/RoBERTa scheme: no lowercasing, no end-of-word suffix, and a leading space
 * folded into the token as `Ġ`.
 *
 * What this replaces was written against CLIP's tokenizer and produced nonsense against a RoBERTa
 * vocabulary: `<|startoftext|>` is not in it, so bos fell back to the literal id 49406 (the token
 * `ãĢĮ`); eos resolved to 50260 rather than 2; the output was padded to 77 with a hard-coded 49407,
 * which is `Ġbehav`, so a two-word query reached the model as 72 repeats of "behav". On top of that
 * it lowercased the text, split on whitespace only — no `Ġ` prefix, so most words missed the vocab
 * and were dropped silently rather than mapped to `<unk>` — and took `Char.code.toByte()` as the
 * byte encoding, which truncates any non-Latin code point (Cyrillic all collapsed onto `?`).
 *
 * Merges are held in a rank map, so choosing the next merge costs a few hash lookups over the pairs
 * present in the word rather than a scan of the full 50 000-entry merge list per BPE iteration.
 */
class BpeTokenizer(vocabJson: String, mergesText: String) {

    private val byteEncoder = bytesToUnicode()
    private val bpeCache = HashMap<String, List<String>>()

    private val vocab: Map<String, Int> = run {
        val type = object : TypeToken<Map<String, Int>>() {}.type
        Gson().fromJson<Map<String, Int>>(vocabJson, type) ?: emptyMap()
    }

    /** Pair of sub-tokens to its position in merges.txt; lower wins. */
    private val mergeRanks: Map<String, Int> = run {
        val ranks = HashMap<String, Int>(BIG_ENOUGH)
        var rank = 0
        for (line in mergesText.lineSequence()) {
            // Only the "#version:" header is a comment. Filtering every line that starts with
            // '#' dropped eight real merges ("# #", "## ##", "# $", ...) and silently changed
            // how any text containing those characters was segmented.
            if (line.isBlank() || line.startsWith("#version")) continue
            val space = line.indexOf(' ')
            if (space <= 0) continue
            ranks[line.trimEnd()] = rank++
        }
        ranks
    }

    /**
     * GPT-2's pre-tokenizer pattern.
     *
     * Deliberately an instance `lazy` rather than a companion constant: a bad pattern in a static
     * initializer surfaces as an ExceptionInInitializerError, which is an Error and so sails past
     * the `catch (e: Exception)` in the encoder and kills the process. Built here, the same failure
     * is a PatternSyntaxException that the encoder catches, and AI search degrades to plain text
     * search.
     */
    private val splitPattern: Regex by lazy {
        Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^$WS\p{L}\p{N}]+|[$WS]+(?![^$WS])|[$WS]+""")
    }

    private val bosToken: Long = (vocab["<s>"] ?: 0).toLong()
    private val eosToken: Long = (vocab["</s>"] ?: 2).toLong()
    private val unkToken: Int = vocab["<unk>"] ?: 3

    /**
     * @return `<s> … </s>` token ids, at most [maxLength] of them.
     *
     * The length is the real token count, not a padded one: the exported graph takes a dynamic
     * `sequence_length` and has no `attention_mask` input, so padding would be attended to and
     * would skew the embedding.
     */
    fun tokenize(text: String, maxLength: Int = MAX_TOKENS): LongArray {
        val ids = ArrayList<Long>(minOf(maxLength, 32))
        ids.add(bosToken)

        outer@ for (match in splitPattern.findAll(text)) {
            val piece = match.value
            if (piece.isEmpty()) continue
            val encoded = buildString(piece.length) {
                for (byte in piece.encodeToByteArray()) {
                    append(byteEncoder[byte.toInt() and 0xFF])
                }
            }
            for (subToken in bpe(encoded)) {
                if (ids.size >= maxLength - 1) break@outer
                ids.add((vocab[subToken] ?: unkToken).toLong())
            }
        }

        ids.add(eosToken)
        return LongArray(ids.size) { ids[it] }
    }

    private fun bpe(token: String): List<String> {
        bpeCache[token]?.let { return it }
        if (token.length == 1) return listOf(token)

        var word = ArrayList<String>(token.length).apply {
            for (c in token) add(c.toString())
        }

        while (word.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIndex = -1
            for (i in 0 until word.size - 1) {
                val rank = mergeRanks[word[i] + ' ' + word[i + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = i
                }
            }
            if (bestIndex < 0) break

            val first = word[bestIndex]
            val second = word[bestIndex + 1]
            val merged = ArrayList<String>(word.size - 1)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    merged.add(first + second)
                    i += 2
                } else {
                    merged.add(word[i])
                    i++
                }
            }
            word = merged
        }

        if (bpeCache.size < CACHE_LIMIT) bpeCache[token] = word
        return word
    }

    /** GPT-2's reversible mapping from raw bytes onto printable code points. */
    private fun bytesToUnicode(): Array<String> {
        val bs = ArrayList<Int>(256)
        for (b in '!'.code..'~'.code) bs.add(b)
        for (b in '¡'.code..'¬'.code) bs.add(b)
        for (b in '®'.code..'ÿ'.code) bs.add(b)

        val cs = ArrayList(bs)
        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        val table = Array(256) { "" }
        for (i in bs.indices) table[bs[i]] = cs[i].toChar().toString()
        return table
    }

    private companion object {
        const val MAX_TOKENS = 77
        const val CACHE_LIMIT = 20_000
        const val BIG_ENOUGH = 65_536

        /**
         * Whitespace, spelled out.
         *
         * The reference tokenizer treats `\s` as Unicode, which Java's regex engine only does under
         * the `(?U)` flag. Listing the separators keeps the two in agreement without relying on a
         * flag that not every engine accepts.
         */
        // Raw strings pass \uXXXX through untouched, so the regex engine resolves the escapes.
        const val WS = """\s\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000"""
    }
}
