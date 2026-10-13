package app.voicereader.korean

import org.junit.Assert.assertEquals
import org.junit.Test

class KoreanNormalizerTest {

    private fun check(expected: String, input: String) = assertEquals(expected, KoreanNormalizer.normalize(input))

    @Test fun sino() {
        mapOf(
            0L to "영", 10L to "십", 11L to "십일", 100L to "백", 2026L to "이천이십육", 10000L to "만",
            10001L to "만일", 12345L to "만이천삼백사십오", 110000L to "십일만", 100000000L to "일억",
            1_0000_0000_0000L to "일조",
        ).forEach { (n, s) -> assertEquals(s, KoreanNormalizer.sino(n)) }
    }

    @Test fun nativeCounters() {
        check("세 개", "3개")
        check("세 개", "3 개")
        check("스무 살", "20살")
        check("스물한 명", "21명")
        check("백이십 개", "120개")
        check("첫 번째, 두 번째", "1번째, 2번째")
        check("일곱 시 삼십분", "7시 30분")
        check("오 개월", "5개월")
        check("삼백십이 권의 책", "312권의 책")
        check("제삼 권", "제3권")
    }

    @Test fun dates() {
        check("이천이십육년 시월 십삼일", "2026년 10월 13일")
        check("이천이십육년 시월 십삼일", "2026.10.13.")
        check("유월", "6월")
        check("오후 세 시 오분", "오후 3:05")
    }

    @Test fun unitsAndDecimals() {
        check("오 킬로미터", "5km")
        check("삼 점 오 킬로그램", "3.5kg")
        check("오십 퍼센트", "50%")
        check("삼 점 일사", "3.14")
        check("오 mangoes", "5 mangoes")
        check("천이백삼십사원", "1,234원")
    }

    @Test fun digitStrings() {
        check("공일공 일이삼사 오육칠팔", "010-1234-5678")
        check("공칠", "07")
    }

    @Test fun acronymsAndHanja() {
        check("에이아이가 티티에스 엔진을", "AI가 TTS 엔진을")
        check("Hello UNESCO", "Hello UNESCO")
        check("대한민국", "대한민국(大韓民國)")
        check("대한민국", "大韓民國(대한민국)")
    }

    @Test fun dictionary() {
        val dict = PronunciationDictionary(mapOf("Readium" to "리디움", "AI" to "인공지능", "값" to "갑"))
        assertEquals("리디움을 써요", dict.apply("Readium을 써요"))
        assertEquals("MAIL 인공지능", dict.apply("MAIL AI"))
        assertEquals("갑이 올랐다", dict.apply("값이 올랐다"))
        assertEquals("그대로", PronunciationDictionary(emptyMap()).apply("그대로"))
    }
}
