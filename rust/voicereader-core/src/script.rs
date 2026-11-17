/// Latin letters and no Hangul: an English sentence quoted in a Korean book, or a line of an
/// English book whose language isn't declared.
pub fn is_latin_only(text: &str) -> bool {
    let mut has_latin = false;
    for ch in text.chars() {
        if ch.is_ascii_alphabetic() {
            has_latin = true;
        } else if is_hangul(ch) {
            return false;
        }
    }
    has_latin
}

#[inline]
pub fn is_hangul(ch: char) -> bool {
    matches!(
        ch as u32,
        0xAC00..=0xD7A3 // Hangul Syllables (가..힣)
        | 0x3131..=0x318E // Hangul Compatibility Jamo (ㄱ..ㆎ)
        | 0x1100..=0x11FF // Hangul Jamo (ᄀ..ᇿ)
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_latin_only() {
        assert!(is_latin_only("Hello, world!"));
        assert!(is_latin_only("1865: The Great Gatsby"));
        assert!(!is_latin_only("1865"));
        assert!(!is_latin_only("오늘은 The Great Gatsby를 읽었다."));
        assert!(!is_latin_only("ㅋㅋㅋ Hello"));
    }
}
