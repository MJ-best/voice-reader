package app.voicereader.korean

private val QUOTES = mapOf('"' to '"', '“' to '”', '\'' to '\'', '‘' to '’')
private const val SENTENCE_END = ".?!…"

/** True for a line of dialogue as produced by [splitDialogue]: it starts with an opening quote. */
fun isDialogue(text: String): Boolean = text.trimStart().firstOrNull() in QUOTES

/**
 * Splits a paragraph into [sentences], except that a quote opening a sentence ("어디 가니?" 엄마가 물었다.) becomes one
 * piece of its own, so it can be read in another voice. Quotes inside a sentence (그는 "네"라고 했다) stay put:
 * cutting those out would break the sentence's intonation. A multi-sentence quote stays one piece.
 */
fun splitDialogue(text: String, sentences: (String) -> List<IntRange>): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var narrationStart = 0
    var i = 0
    while (i < text.length) {
        val close = QUOTES[text[i]]
        val end = if (close != null && startsSentence(text, i)) closingQuote(text, i, close) else -1
        if (end < 0) {
            i++
            continue
        }
        ranges += narration(text, narrationStart, i, sentences)
        ranges += i..end
        narrationStart = end + 1
        i = end + 1
    }
    ranges += narration(text, narrationStart, text.length, sentences)
    return ranges
}

/**
 * Joins runs of short sentences into pieces of at least [min] characters (at most [max], where Supertonic would cut
 * a Korean text in two anyway). Synthesized one by one, each short sentence starts its intonation from scratch and its
 * pitch is drawn anew, so a run of them sounds like voices taking turns. [apart] keeps two neighbors separate
 * (dialogue and narration read in different voices).
 */
fun joinShortSentences(
    ranges: List<IntRange>,
    min: Int = 40,
    max: Int = 120,
    apart: (IntRange, IntRange) -> Boolean = { _, _ -> false },
): List<IntRange> {
    val joined = mutableListOf<IntRange>()
    for (range in ranges) {
        val last = joined.lastOrNull()
        if (last != null && last.last - last.first + 1 < min && range.last - last.first + 1 <= max && !apart(last, range)) {
            joined[joined.lastIndex] = last.first..range.last
        } else {
            joined += range
        }
    }
    return joined
}

/** At the paragraph start, after a sentence end, or right after another quote ("안녕." "응."). */
private fun startsSentence(text: String, i: Int): Boolean {
    var j = i - 1
    while (j >= 0 && text[j].isWhitespace()) j--
    return j < 0 || text[j] in SENTENCE_END || text[j] in QUOTES.values
}

/** Straight quotes double as apostrophes ('I don't know,' she said), so their closer must not be glued to a letter. */
private fun closingQuote(text: String, open: Int, close: Char): Int {
    var k = text.indexOf(close, open + 1)
    if (close == '\'' || close == '"') {
        while (k >= 0 && k + 1 < text.length && text[k + 1].isLetter()) k = text.indexOf(close, k + 1)
    }
    return k
}

private fun narration(text: String, from: Int, until: Int, sentences: (String) -> List<IntRange>): List<IntRange> =
    sentences(text.substring(from, until)).mapNotNull { range ->
        var first = range.first + from
        var last = range.last + from
        while (first <= last && text[first].isWhitespace()) first++
        while (last >= first && text[last].isWhitespace()) last--
        if (first <= last) first..last else null
    }
