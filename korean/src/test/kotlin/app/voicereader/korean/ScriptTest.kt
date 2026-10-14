package app.voicereader.korean

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTest {

    @Test fun englishSentencesAreLatinOnly() {
        assertTrue(isLatinOnly("In 1865 the war ended, and thousands of soldiers walked home."))
        assertTrue(isLatinOnly("\"Call me Ishmael.\""))
    }

    @Test fun koreanWithEnglishWordsIsNot() {
        assertFalse(isLatinOnly("오늘은 The Great Gatsby를 다시 읽었다."))
        assertFalse(isLatinOnly("그 책의 원제는 To Kill a Mockingbird이다."))
    }

    @Test fun noLettersIsNot() {
        assertFalse(isLatinOnly("1865."))
        assertFalse(isLatinOnly("그는 앉았다."))
    }
}
