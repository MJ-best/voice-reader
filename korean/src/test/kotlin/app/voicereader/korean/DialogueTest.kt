package app.voicereader.korean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueTest {

    // Stand-in for ICU's sentence splitter: break after . ? ! followed by a space.
    private fun sentences(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = 0
        Regex("""[.?!](?=\s)""").findAll(text).forEach { ranges += start..it.range.last; start = it.range.last + 1 }
        if (start < text.length) ranges += start until text.length
        return ranges
    }

    private fun split(text: String) = splitDialogue(text, ::sentences).map { text.substring(it) }

    private fun join(text: String, apart: (IntRange, IntRange) -> Boolean = { _, _ -> false }) =
        joinShortSentences(sentences(text), apart = apart).map { text.substring(it).trim() }

    @Test fun shortSentencesAreReadTogether() {
        val text = "테오도로스는 자기 거처로 돌아갔다. 그는 앉았다. 마음은 여전히 달아나려 했다. 그는 시편 한 구절을 반복했다. " +
            "처음에는 입술만 움직였다. 마음은 멀리 가 있었다. 그는 다시 반복했다."
        assertEquals(
            listOf(
                "테오도로스는 자기 거처로 돌아갔다. 그는 앉았다. 마음은 여전히 달아나려 했다.",
                "그는 시편 한 구절을 반복했다. 처음에는 입술만 움직였다. 마음은 멀리 가 있었다.",
                "그는 다시 반복했다.",
            ),
            join(text),
        )
    }

    @Test fun longSentencesStayAlone() {
        val long = "낮의 열기가 돌에서 빠져나가자 몇 사람이 무덤 사이의 좁은 길을 천천히 걸었다."
        assertEquals(listOf(long, long), join("$long $long"))
    }

    @Test fun joiningStopsWhereVoicesDiffer() {
        val text = "그는 문을 열었다. \"누구세요?\" \"나야.\""
        val ranges = splitDialogue(text, ::sentences)
        val pieces = joinShortSentences(ranges) { a, b -> isDialogue(text.substring(a)) != isDialogue(text.substring(b)) }
        assertEquals(listOf("그는 문을 열었다.", "\"누구세요?\" \"나야.\""), pieces.map { text.substring(it) })
    }

    @Test fun dialogueThenAttribution() {
        assertEquals(listOf("\"어디 가니?\"", "엄마가 물었다."), split("\"어디 가니?\" 엄마가 물었다."))
        assertEquals(listOf("“내일 만나자.”", "그녀가 조용히 말했다."), split("“내일 만나자.” 그녀가 조용히 말했다."))
    }

    @Test fun multiSentenceQuoteStaysWhole() {
        assertEquals(listOf("\"정말이야? 거짓말하지 마!\"", "친구가 소리쳤다."), split("\"정말이야? 거짓말하지 마!\" 친구가 소리쳤다."))
    }

    @Test fun quoteAfterNarrationSentence() {
        assertEquals(
            listOf("그는 문을 열었다.", "\"누구세요?\"", "\"나야.\""),
            split("그는 문을 열었다. \"누구세요?\" \"나야.\""),
        )
    }

    @Test fun quotesInsideSentenceStayPut() {
        assertEquals(listOf("그녀는 \"Thank you\"라고 짧게 대답했다."), split("그녀는 \"Thank you\"라고 짧게 대답했다."))
        assertEquals(listOf("그는 '설마 그럴 리가' 하고 중얼거렸다."), split("그는 '설마 그럴 리가' 하고 중얼거렸다."))
    }

    @Test fun apostropheIsNotAClosingQuote() {
        assertEquals(listOf("'I don't know,'", "she said."), split("'I don't know,' she said."))
    }

    @Test fun unclosedQuoteFallsBackToSentences() {
        assertEquals(listOf("\"끝나지 않은 말.", "그리고 다음 문장."), split("\"끝나지 않은 말. 그리고 다음 문장."))
    }

    @Test fun plainNarration() {
        assertEquals(listOf("첫 문장.", "둘째 문장."), split("첫 문장. 둘째 문장."))
    }

    @Test fun detectsDialogue() {
        assertTrue(isDialogue("\"어디 가니?\""))
        assertTrue(isDialogue("  ‘혼잣말’"))
        assertFalse(isDialogue("엄마가 물었다."))
    }
}
