package music.ai.recommend.ai

/**
 * Whether a query should go through the Russian encoder rather than CLAP's own text tower.
 *
 * Any Cyrillic at all is enough. A mixed query ("Мельница remix") goes there too: a fifth of the
 * student's training fed it the English side precisely so that it stays usable for English, while
 * CLAP would make nothing of the Cyrillic half.
 *
 * Here rather than beside either encoder because both ports ask the same question and have to
 * answer it the same way. A phone that sends a Russian query to the student while the desktop sends
 * it to CLAP does not fail — it answers, with something plausible and meaningless, and the two
 * devices quietly disagree about what the library contains.
 */
object RussianText {
    fun handles(text: String): Boolean =
        text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
}
