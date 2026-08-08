package music.ai.recommend.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

class BpeTokenizer(context: Context) {
    private val vocab: Map<String, Int>
    private val merges: List<Pair<String, String>>
    private val byteEncoder = bytesToUnicode()
    private val bpeCache = mutableMapOf<String, String>()

    init {
        val vocabJson = context.assets.open("vocab.json").bufferedReader().use { it.readText() }
        val type = object : TypeToken<Map<String, Int>>() {}.type
        vocab = Gson().fromJson(vocabJson, type)

        merges = context.assets.open("merges.txt").bufferedReader().use { reader ->
            reader.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { 
                    val parts = it.split(" ")
                    parts[0] to parts[1]
                }
                .toList()
        }
    }

    fun tokenize(text: String, maxLength: Int = 77): LongArray {
        val tokens = mutableListOf<Long>()
        // CLIP/CLAP often use 49406 and 49407 for start/end
        tokens.add(vocab["<|startoftext|>"]?.toLong() ?: 49406L)

        val cleanText = text.lowercase().replace(Regex("\\s+"), " ").trim()
        val words = cleanText.split(" ")
        
        for (word in words) {
            val encodedWord = encodeWord(word)
            val subTokens = encodedWord.split(" ")
            for (subToken in subTokens) {
                vocab[subToken]?.let { tokens.add(it.toLong()) }
            }
        }

        tokens.add(vocab["<|endoftext|>"]?.toLong() ?: 49407L)
        
        val result = LongArray(maxLength) { 49407L } // Pad with end token
        for (i in 0 until minOf(tokens.size, maxLength)) {
            result[i] = tokens[i]
        }
        return result
    }

    private fun encodeWord(word: String): String {
        if (word in bpeCache) return bpeCache[word]!!
        
        var chars = word.map { byteEncoder[it.code.toByte()] ?: "" }.toMutableList()
        if (chars.isEmpty()) return ""

        while (chars.size > 1) {
            val pairs = getPairs(chars)
            val bigram = merges.firstOrNull { it in pairs } ?: break
            
            val newChars = mutableListOf<String>()
            var i = 0
            while (i < chars.size) {
                if (i < chars.size - 1 && chars[i] == bigram.first && chars[i+1] == bigram.second) {
                    newChars.add(bigram.first + bigram.second)
                    i += 2
                } else {
                    newChars.add(chars[i])
                    i += 1
                }
            }
            chars = newChars
        }

        val result = chars.joinToString(" ")
        bpeCache[word] = result
        return result
    }

    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        val pairs = mutableSetOf<Pair<String, String>>()
        for (i in 0 until word.size - 1) {
            pairs.add(word[i] to word[i+1])
        }
        return pairs
    }

    private fun bytesToUnicode(): Map<Byte, String> {
        val bs = mutableListOf<Int>()
        for (b in '!'.code..'~'.code) bs.add(b)
        for (b in '¡'.code..'¬'.code) bs.add(b)
        for (b in '®'.code..'ÿ'.code) bs.add(b)
        
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        return bs.zip(cs.map { it.toChar().toString() }).toMap().mapKeys { it.key.toByte() }
    }
}
