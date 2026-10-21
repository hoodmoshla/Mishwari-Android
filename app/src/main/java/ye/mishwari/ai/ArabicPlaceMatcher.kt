package ye.mishwari.ai

import java.text.Normalizer

/** Conservative Arabic normalization for comparison only; it must not be used to auto-select a place. */
object ArabicPlaceMatcher {
    /** Removes tashkeel/tatweel and folds common alef/maqsura variants; keeps ة distinct from ه. */
    fun normalize(value: String): String = normalizeName(value)

    fun normalizeName(value: String): String {
        val noMarks = Normalizer.normalize(value, Normalizer.Form.NFC)
            .filterNot { it == '\u0640' || it in '\u064B'..'\u065F' || it == '\u0670' }
        return buildString(noMarks.length) {
            for (char in noMarks) append(when (char) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                else -> char
            })
        }.replace(WHITESPACE, " ").trim()
    }

    private val WHITESPACE = Regex("\\s+")
}
