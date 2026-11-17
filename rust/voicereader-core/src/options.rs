#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ReadingOptions {
    pub parentheses: bool,
    pub footnote_marks: bool,
    pub spell_acronyms: bool,
}

impl Default for ReadingOptions {
    fn default() -> Self {
        Self {
            parentheses: true,
            footnote_marks: false,
            spell_acronyms: true,
        }
    }
}

#[inline]
fn is_superscript_digit(ch: char) -> bool {
    matches!(
        ch,
        '⁰' | '¹' | '²' | '³' | '⁴' | '⁵' | '⁶' | '⁷' | '⁸' | '⁹'
    )
}

#[inline]
fn is_footnote_symbol(ch: char) -> bool {
    matches!(ch, '*' | '†' | '‡')
}

/// Drops footnote marks and parenthesized asides according to `options` (language-independent).
/// Nested brackets are removed cleanly innermost-first, matching the Kotlin implementation.
pub fn apply_reading_options(text: &str, options: ReadingOptions) -> String {
    let after_footnotes = if options.footnote_marks {
        text.to_string()
    } else {
        strip_footnote_marks(text)
    };

    if options.parentheses {
        return after_footnotes;
    }

    let mut cur = after_footnotes;
    loop {
        let next = strip_innermost_brackets(&cur);
        if next == cur {
            break cur;
        }
        cur = next;
    }
}

fn strip_footnote_marks(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < text.len() {
        let ch = text[i..].chars().next().unwrap();
        let ch_len = ch.len_utf8();

        if is_superscript_digit(ch) {
            i += ch_len;
            while i < text.len() {
                let next_ch = text[i..].chars().next().unwrap();
                if is_superscript_digit(next_ch) {
                    i += next_ch.len_utf8();
                } else {
                    break;
                }
            }
            continue;
        }

        if is_footnote_symbol(ch) {
            i += ch_len;
            while i < text.len() {
                let next_ch = text[i..].chars().next().unwrap();
                if is_footnote_symbol(next_ch) {
                    i += next_ch.len_utf8();
                } else {
                    break;
                }
            }
            continue;
        }

        if ch == '[' {
            // Check `[1..3 digits]`
            let mut j = i + 1;
            let mut digits = 0;
            while j < bytes.len() && bytes[j].is_ascii_digit() && digits < 3 {
                j += 1;
                digits += 1;
            }
            if digits >= 1 && j < bytes.len() && bytes[j] == b']' {
                i = j + 1;
                continue;
            }
        }

        out.push(ch);
        i += ch_len;
    }
    out
}

fn strip_innermost_brackets(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < text.len() {
        // Consume optional preceding whitespace if followed by an innermost bracket group
        let mut ws_end = i;
        while ws_end < text.len() {
            let c = text[ws_end..].chars().next().unwrap();
            if c.is_whitespace() {
                ws_end += c.len_utf8();
            } else {
                break;
            }
        }

        if let Some(close_end) = match_innermost_bracket(&text[ws_end..]) {
            i = ws_end + close_end;
            continue;
        }

        let ch = text[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn match_innermost_bracket(s: &str) -> Option<usize> {
    let first = s.chars().next()?;
    let (open, close) = match first {
        '(' => ('(', ')'),
        '[' => ('[', ']'),
        '（' => ('（', '）'),
        _ => return None,
    };
    let mut pos = first.len_utf8();
    for ch in s[pos..].chars() {
        if ch == close {
            return Some(pos + ch.len_utf8());
        }
        if ch == open {
            return None;
        }
        pos += ch.len_utf8();
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_reading_options() {
        assert_eq!(
            apply_reading_options("끝났다.¹ 말이다[1]", ReadingOptions::default()),
            "끝났다. 말이다"
        );
        assert_eq!(
            apply_reading_options(
                "끝났다.¹",
                ReadingOptions {
                    footnote_marks: true,
                    ..Default::default()
                }
            ),
            "끝났다.¹"
        );
        assert_eq!(
            apply_reading_options(
                "말(설명(부연))이다",
                ReadingOptions {
                    parentheses: false,
                    ..Default::default()
                }
            ),
            "말이다"
        );
        assert_eq!(
            apply_reading_options(
                "Book (draft)",
                ReadingOptions {
                    parentheses: false,
                    ..Default::default()
                }
            ),
            "Book"
        );
    }
}
