package dev.lelonio.square.backend.lyrics

import android.os.Build

/**
 * A song's lines in Latin letters: pinyin for Chinese, the romanised Korean,
 * the kana of a Japanese song spelt out, Cyrillic and Greek and the rest in
 * their usual transcription. What is sung, written so it can be sung along by
 * someone who does not read the script; the translation is the other thing,
 * what it means.
 *
 * Done on the phone with the platform's own ICU, so it needs no network and
 * answers at once. ICU's transliterator is in Android from 10 up; below that
 * the switch is not offered.
 */
object Romanize {

    /** Whether this phone can do it at all. */
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Whether any of these lines is in a script other than Latin. */
    fun wanted(lines: List<String>): Boolean = lines.any { line -> line.any(::foreign) }

    /**
     * One line of Latin letters per line of [texts], in the same order. A line
     * with nothing to spell out is empty rather than a copy of itself.
     */
    fun lines(texts: List<String>): List<String> {
        if (!available) return texts.map { "" }
        val transliterator = android.icu.text.Transliterator.getInstance(schemeFor(texts))
        return texts.map { line ->
            if (line.none(::foreign)) {
                ""
            } else {
                val spelt = transliterator.transliterate(line).trim()
                if (spelt == line.trim()) "" else spelt
            }
        }
    }

    /**
     * Which transliteration, chosen for the whole song.
     *
     * A song with kana in it is Japanese, and its kanji are left as they are:
     * ICU reads every Han character as Mandarin, and a Japanese line spelt out
     * in pinyin is a line nobody sings. Any other song goes through ICU's
     * general rule, which is pinyin for Han, Revised Romanisation for Hangul,
     * and the standard transcription for everything else.
     */
    internal fun schemeFor(texts: List<String>): String =
        if (texts.any { line -> line.any(::kana) }) JAPANESE else ANY

    private fun kana(c: Char): Boolean = c in '぀'..'ヿ' || c in 'ㇰ'..'ㇿ'

    /** A letter, and not a Latin one; digits, spaces and punctuation are not counted. */
    private fun foreign(c: Char): Boolean =
        Character.isLetter(c) && Character.UnicodeScript.of(c.code) != Character.UnicodeScript.LATIN

    internal const val JAPANESE = "Hiragana-Latin; Katakana-Latin; Hangul-Latin"
    internal const val ANY = "Any-Latin"
}
