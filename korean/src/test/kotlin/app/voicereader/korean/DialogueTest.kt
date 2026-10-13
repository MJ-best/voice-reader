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
