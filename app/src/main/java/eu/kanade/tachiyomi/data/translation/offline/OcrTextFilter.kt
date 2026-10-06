package eu.kanade.tachiyomi.data.translation.offline

/**
 * Clean-up of recognized text before it goes to the on-device translator, which is much less forgiving than an
 * LLM: ALL CAPS lettering, watermarks and stray symbols all produce bad translations.
 */
object OcrTextFilter {

    private val WATERMARK = Regex("""(?i)(www\.|https?:|\.com\b|\.net\b|\.org\b|\.co\b|\.io\b|discord|scans?\b)""")
    private val STANDALONE_I = Regex("""\bi\b""")
    private val SENTENCE_START = Regex("""([.!?]\s+)(\p{L})""")

    /** Languages written without spaces between lines of the same sentence. */
    private val NO_SPACE_LANGUAGES = setOf("ja", "zh")

    /** Joins the recognized lines of one block into a single string. */
    fun join(lines: List<String>, sourceTag: String): String {
        val separator = if (sourceTag in NO_SPACE_LANGUAGES) "" else " "
        return lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(separator)
    }

    /**
     * Comics are lettered in capitals. The translator expects normal sentences, so text with no lowercase letters
     * at all becomes sentence case. Only used for the Latin script; other scripts have no case.
     */
    fun normalizeCase(text: String, sourceTag: String): String {
        if (sourceTag != "en") return text
        val letters = text.filter { it.isLetter() }
        if (letters.length < MIN_LETTERS_FOR_CASE_FIX || letters.any { it.isLowerCase() }) return text

        var result = text.lowercase()
        result = STANDALONE_I.replace(result, "I")
        result = SENTENCE_START.replace(result) { it.groupValues[1] + it.groupValues[2].uppercase() }
        val firstLetter = result.indexOfFirst { it.isLetter() }
        if (firstLetter >= 0) {
            result = result.substring(0, firstLetter) + result[firstLetter].uppercaseChar() +
                result.substring(firstLetter + 1)
        }
        return result
    }

    /** False for page numbers, symbols, single stray letters and scanlator watermarks. */
    fun isTranslatable(text: String, sourceTag: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return false
        // A single Latin or Hangul letter is noise. A single Han or kana character can be a word.
        if (letters < 2 && sourceTag !in NO_SPACE_LANGUAGES) return false
        if (WATERMARK.containsMatchIn(text)) return false
        return true
    }

    private const val MIN_LETTERS_FOR_CASE_FIX = 4
}
