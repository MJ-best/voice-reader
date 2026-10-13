package app.voicereader.korean

/**
 * Rewrites Korean text into the form a TTS engine should read aloud:
 * numbers (sino vs native by counter), dates, phone numbers, units, acronyms and parenthesized hanja.
 */
object KoreanNormalizer {

    private val SINO_DIGITS = listOf("영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    private val SMALL_UNITS = listOf("", "십", "백", "천")
    private val BIG_UNITS = listOf("", "만", "억", "조", "경")
    private val POW10 = intArrayOf(1, 10, 100, 1000)

    // Attributive native forms, as used before a counter ("한 개", "스물두 살").
    private val NATIVE_ONES = listOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")
    private val NATIVE_TENS = listOf("", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔")

    /** Counters read with native numbers. Ambiguous ones (장, 대, 주) are left out and read as sino. */
    private val NATIVE_COUNTERS = setOf(
        "개", "명", "사람", "살", "마리", "권", "잔", "병", "번", "번째", "가지", "그루", "벌", "켤레", "채",
        "척", "송이", "군데", "곳", "시", "시간", "달", "바퀴", "걸음", "끼", "줄", "쌍", "통",
    )

    /** Sino suffixes that share a prefix with a native counter, so they must win the match. 월 also maps 6/10. */
    private val SINO_SUFFIXES = setOf("개월", "번지", "채널", "월")

    private val UNITS = mapOf(
        "km" to "킬로미터", "cm" to "센티미터", "mm" to "밀리미터", "m" to "미터",
        "kg" to "킬로그램", "mg" to "밀리그램", "g" to "그램",
        "ml" to "밀리리터", "mL" to "밀리리터", "L" to "리터",
        "%" to "퍼센트", "℃" to "도", "°C" to "도",
    )

    private val LETTERS = mapOf(
        'A' to "에이", 'B' to "비", 'C' to "씨", 'D' to "디", 'E' to "이", 'F' to "에프", 'G' to "지",
        'H' to "에이치", 'I' to "아이", 'J' to "제이", 'K' to "케이", 'L' to "엘", 'M' to "엠", 'N' to "엔",
        'O' to "오", 'P' to "피", 'Q' to "큐", 'R' to "알", 'S' to "에스", 'T' to "티", 'U' to "유",
        'V' to "브이", 'W' to "더블유", 'X' to "엑스", 'Y' to "와이", 'Z' to "지",
    )

    private const val HAN = "\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF"
    private val HANJA_THEN_HANGUL = Regex("""[$HAN]+\s*\(([가-힣\s]+)\)""") // 大韓民國(대한민국)
    private val PARENTHESIZED_HANJA = Regex("""\s*\([$HAN]+\)""") // 대한민국(大韓民國)
    private val PHONE = Regex("""(?<!\d)(0\d{1,2})-(\d{3,4})-(\d{4})(?!\d)""")
    private val DATE = Regex("""(?<!\d)(\d{4})[./-](\d{1,2})[./-](\d{1,2})\.?(?!\d)""")
    private val TIME = Regex("""(오전|오후)\s?(\d{1,2}):(\d{2})(?!\d)""") // bare 3:16 is ambiguous (time, verse, score)

    /** Bible book names (개역개정) and abbreviations → full name. A verse is only recognized after one of these. */
    private val BIBLE_BOOKS: Map<String, String> = listOf(
        "창세기|창", "출애굽기|출", "레위기|레", "민수기|민", "신명기|신", "여호수아|수", "사사기|삿", "룻기|룻",
        "사무엘상|삼상", "사무엘하|삼하", "열왕기상|왕상", "열왕기하|왕하", "역대상|대상", "역대하|대하", "에스라|스",
        "느헤미야|느", "에스더|에", "욥기|욥", "시편|시", "잠언|잠", "전도서|전", "아가|아가서|아", "이사야|사",
        "예레미야|렘", "예레미야애가|애가|애", "에스겔|겔", "다니엘|단", "호세아|호", "요엘|욜", "아모스|암",
        "오바댜|옵", "요나|욘", "미가|미", "나훔|나", "하박국|합", "스바냐|습", "학개|학", "스가랴|슥", "말라기|말",
        "마태복음|마", "마가복음|막", "누가복음|눅", "요한복음|요", "사도행전|행", "로마서|롬", "고린도전서|고전",
        "고린도후서|고후", "갈라디아서|갈", "에베소서|엡", "빌립보서|빌", "골로새서|골", "데살로니가전서|살전",
        "데살로니가후서|살후", "디모데전서|딤전", "디모데후서|딤후", "디도서|딛", "빌레몬서|몬", "히브리서|히",
        "야고보서|약", "베드로전서|벧전", "베드로후서|벧후", "요한일서|요한1서|요일", "요한이서|요한2서|요이",
        "요한삼서|요한3서|요삼", "유다서|유", "요한계시록|계시록|계",
    ).flatMap { line -> line.split("|").let { names -> names.map { it to names.first() } } }.toMap()

    // 요 3:16, 요한복음3:16, 마 5:3-12, 창 1:1~2:3. The name must start a word, so "결과 3:2" stays a score.
    private val BIBLE_VERSE = run {
        val names = BIBLE_BOOKS.keys.sortedByDescending { it.length }.joinToString("|")
        Regex("""(?<![가-힣])($names)\s?(\d{1,3}):(\d{1,3})(?:\s?[-~–]\s?(?:(\d{1,3}):)?(\d{1,3}))?""")
    }

    private val ACRONYM = Regex("""(?<![A-Za-z])[A-Z]{2,4}(?![A-Za-z])""")
    private val NUMBER = run {
        val units = UNITS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val counters = (NATIVE_COUNTERS + SINO_SUFFIXES).sortedByDescending { it.length }.joinToString("|")
        Regex("""(제\s?)?(\d{1,3}(?:,\d{3})+|\d+)(\.\d+)?(?:\s?(?:($units)(?![A-Za-z])|($counters)))?""")
    }

    fun normalize(text: String): String = text
        .replace(HANJA_THEN_HANGUL, "$1")
        .replace(PARENTHESIZED_HANJA, "")
        .replace(PHONE) { m -> m.groupValues.drop(1).joinToString(" ") { digits(it, zero = "공") } }
        .replace(DATE) { m ->
            val (y, mo, d) = m.destructured
            if (mo.toInt() in 1..12 && d.toInt() in 1..31) "${y}년 ${mo.toInt()}월 ${d.toInt()}일" else m.value
        }
        .replace(TIME) { m ->
            val (half, h, min) = m.destructured
            "$half ${h}시" + if (min == "00") "" else " ${min.toInt()}분"
        }
        .replace(BIBLE_VERSE) { m ->
            val (name, chapter, verse, endChapter, endVerse) = m.destructured
            val book = BIBLE_BOOKS.getValue(name)
            val unit = if (book == "시편") "편" else "장"
            "$book $chapter$unit ${verse}절" + when {
                endVerse.isEmpty() -> ""
                endChapter.isEmpty() -> "에서 ${endVerse}절"
                else -> "에서 $endChapter$unit ${endVerse}절"
            }
        }
        .replace(NUMBER, ::readNumber)
        .replace(ACRONYM) { m -> m.value.map { LETTERS.getValue(it) }.joinToString("") }

    private fun readNumber(m: MatchResult): String {
        val (prefix, grouped, fraction, unit, counter) = m.destructured
        val raw = grouped.replace(",", "")
        val n = raw.toLongOrNull()
        val number = when {
            n == null -> digits(raw, zero = "영")
            raw.length > 1 && raw.startsWith("0") -> digits(raw, zero = "공")
            counter == "번째" && n == 1L && prefix.isEmpty() -> "첫"
            counter == "월" && n == 6L -> "유"
            counter == "월" && n == 10L -> "시"
            counter in NATIVE_COUNTERS && prefix.isEmpty() && fraction.isEmpty() -> native(n)
            else -> sino(n)
        }
        val point = if (fraction.isEmpty()) "" else " 점 " + digits(fraction.drop(1), zero = "영")
        val suffix = when {
            unit.isNotEmpty() -> " " + UNITS.getValue(unit)
            counter == "월" -> "월"
            counter.isNotEmpty() -> " $counter"
            else -> ""
        }
        return prefix.trim() + number + point + suffix
    }

    private fun digits(s: String, zero: String) = s.map { if (it == '0') zero else SINO_DIGITS[it - '0'] }.joinToString("")

    /** 123 → 백이십삼, 10000 → 만, 100000000 → 일억. */
    fun sino(n: Long): String {
        if (n == 0L) return "영"
        val groups = generateSequence(n) { it / 10000 }.takeWhile { it > 0 }.map { (it % 10000).toInt() }.toList()
        return groups.indices.reversed().joinToString("") { i ->
            val g = groups[i]
            when {
                g == 0 -> ""
                g == 1 && i == 1 -> "만" // 만, but 일억, 일조
                else -> group(g) + BIG_UNITS[i]
            }
        }
    }

    private fun group(g: Int) = (3 downTo 0).joinToString("") { p ->
        val d = g / POW10[p] % 10
        when {
            d == 0 -> ""
            d == 1 && p > 0 -> SMALL_UNITS[p] // 십, not 일십
            else -> SINO_DIGITS[d] + SMALL_UNITS[p]
        }
    }

    /** Native reading before a counter: 1 → 한, 20 → 스무, 21 → 스물한. 100 and up fall back to sino (백이십 개). */
    fun native(n: Long): String = when (n) {
        20L -> "스무"
        in 1L..99L -> NATIVE_TENS[(n / 10).toInt()] + NATIVE_ONES[(n % 10).toInt()]
        else -> sino(n)
    }
}

/** User pronunciation fixes, applied before normalization. Keys match literally, longest first. */
class PronunciationDictionary(private val entries: Map<String, String>) {

    // A key that starts or ends with a Latin letter/digit must not be glued to another one ("AI" ≠ "MAIL").
    // Korean keys match inside words, since particles attach directly ("Readium을").
    private val regex = entries.keys.filter { it.isNotBlank() }.sortedByDescending { it.length }
        .joinToString("|") { key ->
            val start = if (key.first().isAsciiLetterOrDigit()) "(?<![A-Za-z0-9])" else ""
            val end = if (key.last().isAsciiLetterOrDigit()) "(?![A-Za-z0-9])" else ""
            start + Regex.escape(key) + end
        }
        .takeIf { it.isNotEmpty() }
        ?.let(::Regex)

    fun apply(text: String): String = regex?.replace(text) { entries.getValue(it.value) } ?: text

    private fun Char.isAsciiLetterOrDigit() = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
}
