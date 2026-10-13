package app.voicereader.korean

/** What to read aloud besides the plain text. The listener picks these in the reader's "음성과 발음" sheet. */
data class ReadingOptions(
    /** Asides in ( ), [ ], （ ）. Off: skipped. */
    val parentheses: Boolean = true,
    /** Footnote marks such as ¹, [1], *, †. Off: skipped. */
    val footnoteMarks: Boolean = false,
    /** Korean only: spell out acronyms letter by letter (AI → 에이아이). Off: left to the voice. */
    val spellAcronyms: Boolean = true,
)

private val BRACKETED = Regex("""\s*(\([^()]*\)|\[[^\[\]]*]|（[^（）]*）)""")
private val FOOTNOTE_MARKS = Regex("""[¹²³⁰⁴⁵⁶⁷⁸⁹]+|\[\d{1,3}]|[*†‡]+""")

/** Drops what [options] say not to read; language independent. Nested brackets go innermost first. */
fun applyReadingOptions(text: String, options: ReadingOptions): String {
    var result = if (options.footnoteMarks) text else text.replace(FOOTNOTE_MARKS, "")
    if (!options.parentheses) {
        while (true) {
            val next = result.replace(BRACKETED, "")
            if (next == result) break
            result = next
        }
    }
    return result
}
