package app.voicereader.korean

/**
 * Latin letters and no Hangul: an English sentence quoted in a Korean book, or a line of an English book whose
 * language isn't declared. Read it as English; a Korean voice setting garbles it (and Korean number rules would turn
 * "1865" into 천팔백육십오). A Korean sentence with an English word or two (오늘은 The Great Gatsby를 읽었다) stays Korean:
 * the Korean setting reads those words well enough, and switching mid-sentence sounded worse.
 */
fun isLatinOnly(text: String): Boolean =
    text.any { it in 'a'..'z' || it in 'A'..'Z' } && text.none { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' || it in 'ᄀ'..'ᇿ' }
