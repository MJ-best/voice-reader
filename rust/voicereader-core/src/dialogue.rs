use std::ops::Range;

fn closing_quote_for(open: char) -> Option<char> {
    match open {
        '"' => Some('"'),
        '“' => Some('”'),
        '\'' => Some('\''),
        '‘' => Some('’'),
        _ => None,
    }
}

fn is_closing_quote(ch: char) -> bool {
    matches!(ch, '"' | '”' | '\'' | '’')
}

fn is_sentence_end(ch: char) -> bool {
    matches!(ch, '.' | '?' | '!' | '…')
}

/// True for a line of dialogue as produced by `split_dialogue`: starts with an opening quote.
pub fn is_dialogue(text: &str) -> bool {
    text.trim_start()
        .chars()
        .next()
        .and_then(closing_quote_for)
        .is_some()
}

/// Byte ranges of dialogue and narration pieces. `sentences_fn` receives a slice and returns byte
/// ranges relative to that slice.
pub fn split_dialogue<F>(text: &str, mut sentences_fn: F) -> Vec<Range<usize>>
where
    F: FnMut(&str) -> Vec<Range<usize>>,
{
    let mut ranges = Vec::new();
    let mut narration_start = 0usize;
    let mut i = 0usize;
    while i < text.len() {
        let ch = text[i..].chars().next().unwrap();
        let ch_len = ch.len_utf8();
        let end = if let Some(close) = closing_quote_for(ch) {
            if starts_sentence(text, i) {
                find_closing_quote(text, i + ch_len, close)
            } else {
                None
            }
        } else {
            None
        };

        let Some(close_start) = end else {
            i += ch_len;
            continue;
        };
        let close_end = close_start + text[close_start..].chars().next().unwrap().len_utf8();

        push_narration(text, narration_start, i, &mut sentences_fn, &mut ranges);
        ranges.push(i..close_end);
        narration_start = close_end;
        i = close_end;
    }
    push_narration(text, narration_start, text.len(), &mut sentences_fn, &mut ranges);
    ranges
}

fn starts_sentence(text: &str, byte_idx: usize) -> bool {
    let before = &text[..byte_idx];
    for ch in before.chars().rev() {
        if ch.is_whitespace() {
            continue;
        }
        return is_sentence_end(ch) || is_closing_quote(ch);
    }
    true
}

fn find_closing_quote(text: &str, from_byte: usize, close: char) -> Option<usize> {
    let straight = close == '\'' || close == '"';
    let mut pos = from_byte;
    while pos < text.len() {
        let ch = text[pos..].chars().next().unwrap();
        let ch_len = ch.len_utf8();
        if ch == close {
            if straight {
                let after = &text[pos + ch_len..];
                if let Some(next_ch) = after.chars().next() {
                    if next_ch.is_alphabetic() {
                        pos += ch_len;
                        continue;
                    }
                }
            }
            return Some(pos);
        }
        pos += ch_len;
    }
    None
}

fn push_narration<F>(
    text: &str,
    from: usize,
    until: usize,
    sentences_fn: &mut F,
    out: &mut Vec<Range<usize>>,
) where
    F: FnMut(&str) -> Vec<Range<usize>>,
{
    if from >= until {
        return;
    }
    for range in sentences_fn(&text[from..until]) {
        let slice = &text[from + range.start..from + range.end];
        let trimmed_start = slice
            .char_indices()
            .find(|(_, c)| !c.is_whitespace())
            .map(|(idx, _)| idx);
        let trimmed_end = slice
            .char_indices()
            .rev()
            .find(|(_, c)| !c.is_whitespace())
            .map(|(idx, c)| idx + c.len_utf8());
        if let (Some(s), Some(e)) = (trimmed_start, trimmed_end) {
            if s < e {
                out.push((from + range.start + s)..(from + range.start + e));
            }
        }
    }
}

/// Joins only ultra-short fragments so normal sentences keep their natural inter-sentence pause
/// instead of rushing together inside a single TTS call.
///
/// Previously `min = 40, max = 120` merged multiple complete sentences into one utterance, causing
/// TTS duration predictors to speed up across internal periods and skipping inter-sentence pauses.
/// Using `min = 16, max = 75` (in chars) only folds tiny 1–2 word interjections ("네.", "Oh.")
/// while letting normal sentences breathe individually.
pub fn join_short_sentences<F>(
    text: &str,
    ranges: &[Range<usize>],
    min_chars: usize,
    max_chars: usize,
    mut apart: F,
) -> Vec<Range<usize>>
where
    F: FnMut(&Range<usize>, &Range<usize>) -> bool,
{
    let mut joined: Vec<Range<usize>> = Vec::new();
    for range in ranges {
        if let Some(last) = joined.last().cloned() {
            let last_chars = text[last.clone()].chars().count();
            let combined_chars = text[last.start..range.end].chars().count();
            if last_chars < min_chars && combined_chars <= max_chars && !apart(&last, range) {
                let last_idx = joined.len() - 1;
                joined[last_idx] = last.start..range.end;
                continue;
            }
        }
        joined.push(range.clone());
    }
    joined
}

#[cfg(test)]
mod tests {
    use super::*;

    fn basic_sentences(s: &str) -> Vec<Range<usize>> {
        let mut out = Vec::new();
        let mut start = 0usize;
        let bytes = s.as_bytes();
        let mut i = 0usize;
        while i < s.len() {
            let ch = s[i..].chars().next().unwrap();
            let len = ch.len_utf8();
            if matches!(ch, '.' | '?' | '!') {
                if i + len < bytes.len() && s[i + len..].chars().next().unwrap().is_whitespace() {
                    out.push(start..i + len);
                    start = i + len;
                }
            }
            i += len;
        }
        if start < s.len() {
            out.push(start..s.len());
        }
        out
    }

    #[test]
    fn splits_dialogue_and_attribution() {
        let text = "\"어디 가니?\" 엄마가 물었다.";
        let res: Vec<&str> = split_dialogue(text, basic_sentences)
            .into_iter()
            .map(|r| &text[r])
            .collect();
        assert_eq!(res, vec!["\"어디 가니?\"", "엄마가 물었다."]);
    }

    #[test]
    fn apostrophe_not_closing_quote() {
        let text = "'I don't know,' she said.";
        let res: Vec<&str> = split_dialogue(text, basic_sentences)
            .into_iter()
            .map(|r| &text[r])
            .collect();
        assert_eq!(res, vec!["'I don't know,'", "she said."]);
    }

    #[test]
    fn normal_sentences_stay_separate_with_paced_min() {
        let text = "테오도로스는 자기 거처로 돌아갔다. 그는 문을 천천히 열었다. 밖에는 바람이 불고 있었다.";
        let raw = basic_sentences(text);
        let trimmed: Vec<Range<usize>> = raw
            .into_iter()
            .map(|r| {
                let s = text[r.clone()].trim();
                let off = s.as_ptr() as usize - text.as_ptr() as usize;
                off..off + s.len()
            })
            .collect();
        let chunked = join_short_sentences(text, &trimmed, 10, 75, |_, _| false);
        assert_eq!(chunked.len(), 3);
    }

    #[test]
    fn ultra_short_interjection_joins_neighbor() {
        let text = "\"네.\" 그가 조용히 대답했다.";
        let raw = split_dialogue(text, basic_sentences);
        let chunked = join_short_sentences(text, &raw, 10, 75, |_, _| false);
        assert_eq!(chunked.len(), 1);
    }
}
