use std::fs;
use std::path::Path;

use crate::dictionary_en::{lookup_hint, min_tier_for_level};
use crate::zip::{read_zip, write_zip};

const WORDWISE_STYLE: &str = concat!(
    "<style id=\"vw-wordwise-style\">",
    "ruby.vw-ww{ruby-position:under !important;-webkit-ruby-position:after !important;ruby-align:center !important;}",
    "ruby.vw-ww>rt{font-size:0.56em !important;line-height:1.05 !important;font-weight:normal !important;font-style:normal !important;letter-spacing:0 !important;opacity:0.78 !important;user-select:none !important;-webkit-user-select:none !important;}",
    "ruby.vw-ww>rt::before{content:attr(data-hint);}",
    "</style>"
);

fn escape_attr(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '"' => out.push_str("&quot;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            _ => out.push(c),
        }
    }
    out
}

fn is_skip_tag(tag_name: &str) -> bool {
    matches!(
        tag_name,
        "head" | "script" | "style" | "title" | "code" | "pre" | "svg" | "ruby" | "rt" | "nav"
    )
}

fn is_block_tag(tag_name: &str) -> bool {
    matches!(
        tag_name,
        "p" | "div" | "h1" | "h2" | "h3" | "h4" | "h5" | "h6" | "li" | "blockquote" | "br" | "hr"
            | "section" | "article" | "body" | "td" | "th" | "figcaption"
    )
}

fn parse_tag_name(tag_inner: &str) -> (bool, String) {
    let trimmed = tag_inner.trim_start();
    let is_close = trimmed.starts_with('/');
    let rest = if is_close { &trimmed[1..] } else { trimmed }.trim_start();
    let name: String = rest
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == ':')
        .map(|c| c.to_ascii_lowercase())
        .collect();
    let local = name.rsplit(':').next().unwrap_or(&name).to_string();
    (is_close, local)
}

/// Annotates difficult English words in XHTML/HTML with `<ruby class="vw-ww">word<rt data-hint="easy"></rt></ruby>`.
/// Using `data-hint` + CSS `::before { content: attr(data-hint); }` guarantees Readium TTS never reads hints aloud.
pub fn transform_xhtml_wordwise(html: &str, level: u8) -> (String, usize) {
    if min_tier_for_level(level).is_none() {
        return (html.to_string(), 0);
    }

    let bytes = html.as_bytes();
    let mut out = String::with_capacity(html.len() + html.len() / 6);
    let mut skip_stack: Vec<String> = Vec::new();
    let mut style_injected = html.contains("id=\"vw-wordwise-style\"");
    let has_body_tag = html.to_ascii_lowercase().contains("<body");
    let mut in_body = !has_body_tag;
    let mut at_sentence_start = true;
    let mut count = 0usize;

    let mut i = 0usize;
    while i < bytes.len() {
        if bytes[i] == b'<' {
            // HTML comment
            if html[i..].starts_with("<!--") {
                if let Some(end) = html[i + 4..].find("-->") {
                    let close = i + 4 + end + 3;
                    out.push_str(&html[i..close]);
                    i = close;
                    continue;
                }
            }
            // Processing instruction / DOCTYPE
            if html[i..].starts_with("<?") || html[i..].starts_with("<!") {
                if let Some(end) = html[i..].find('>') {
                    let close = i + end + 1;
                    out.push_str(&html[i..close]);
                    i = close;
                    continue;
                }
            }
            if let Some(rel_end) = find_tag_end(&html[i..]) {
                let tag_end = i + rel_end + 1;
                let tag_str = &html[i..tag_end];
                let inner = &html[i + 1..tag_end - 1];
                let self_closing = inner.trim_end().ends_with('/');
                let (is_close, tag_name) = parse_tag_name(inner);

                if !is_close && tag_name == "body" {
                    in_body = true;
                    at_sentence_start = true;
                }
                if is_close && tag_name == "head" && !style_injected {
                    out.push_str(WORDWISE_STYLE);
                    style_injected = true;
                }
                if !is_close && tag_name == "body" && !style_injected {
                    out.push_str(tag_str);
                    out.push_str(WORDWISE_STYLE);
                    style_injected = true;
                    i = tag_end;
                    continue;
                }

                if !self_closing && is_skip_tag(&tag_name) {
                    if is_close {
                        if let Some(pos) = skip_stack.iter().rposition(|t| t == &tag_name) {
                            skip_stack.truncate(pos);
                        }
                    } else {
                        skip_stack.push(tag_name.clone());
                    }
                }
                if is_block_tag(&tag_name) {
                    at_sentence_start = true;
                }

                out.push_str(tag_str);
                if is_close && tag_name == "body" {
                    in_body = false;
                }
                i = tag_end;
                continue;
            }
        }

        // Entity like `&amp;` or `&#8217;`
        if bytes[i] == b'&' {
            if let Some(semi) = html[i..].find(';') {
                if semi > 1 && semi <= 12 && !html[i + 1..i + semi].contains(|c: char| c.is_whitespace() || c == '<') {
                    let ent = &html[i..=i + semi];
                    out.push_str(ent);
                    i += semi + 1;
                    continue;
                }
            }
        }

        if in_body && skip_stack.is_empty() && bytes[i].is_ascii_alphabetic() {
            let mut j = i;
            while j < bytes.len() && bytes[j].is_ascii_alphabetic() {
                j += 1;
            }
            let word = &html[i..j];
            let starts_upper = word.as_bytes()[0].is_ascii_uppercase();
            let all_upper = word.bytes().all(|b| b.is_ascii_uppercase());
            // Skip mid-sentence capitalized proper nouns (e.g. "Grace", "Victor") and ALL-CAPS acronyms.
            let eligible_case = !all_upper && (!starts_upper || at_sentence_start);

            // Ensure word isn't immediately part of an apostrophe contraction or hyphenated suffix like "don't"
            let next_is_contraction = j < bytes.len()
                && (bytes[j] == b'\'' || html[j..].starts_with('’'))
                && html[j..].chars().nth(1).map(|c| c.is_ascii_alphabetic()).unwrap_or(false);

            if eligible_case && !next_is_contraction {
                if let Some((_tier, hint)) = lookup_hint(word, level) {
                    out.push_str("<ruby class=\"vw-ww\">");
                    out.push_str(word);
                    out.push_str("<rt data-hint=\"");
                    out.push_str(&escape_attr(hint));
                    out.push_str("\"></rt></ruby>");
                    count += 1;
                    at_sentence_start = false;
                    i = j;
                    continue;
                }
            }
            out.push_str(word);
            at_sentence_start = false;
            i = j;
            continue;
        }

        let ch = html[i..].chars().next().unwrap();
        if matches!(ch, '.' | '?' | '!' | '…') {
            at_sentence_start = true;
        } else if !ch.is_whitespace() && !matches!(ch, '"' | '\'' | '“' | '‘' | '(' | '[') {
            at_sentence_start = false;
        }
        out.push(ch);
        i += ch.len_utf8();
    }

    (out, count)
}

fn find_tag_end(s: &str) -> Option<usize> {
    let bytes = s.as_bytes();
    let mut quote: Option<u8> = None;
    for (idx, &b) in bytes.iter().enumerate() {
        match quote {
            Some(q) => {
                if b == q {
                    quote = None;
                }
            }
            None => match b {
                b'"' | b'\'' => quote = Some(b),
                b'>' => return Some(idx),
                _ => {}
            },
        }
    }
    None
}

fn is_content_html(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    let base = lower.rsplit('/').next().unwrap_or(&lower);
    if base == "nav.xhtml" || base == "toc.xhtml" || base == "toc.html" {
        return false;
    }
    lower.ends_with(".xhtml") || lower.ends_with(".html") || lower.ends_with(".htm")
}

/// Converts an English EPUB file at `input_path` into a Word Wise annotated EPUB at `output_path`.
/// Writes atomically via a temp file and returns the number of glossed words.
pub fn convert_epub_wordwise(input_path: &str, output_path: &str, level: u8) -> Result<usize, String> {
    if min_tier_for_level(level).is_none() {
        return Err("Invalid Word Wise level (expected 1..=3)".into());
    }
    let bytes = fs::read(input_path).map_err(|e| format!("Failed to read EPUB: {e}"))?;
    let mut entries = read_zip(&bytes)?;
    let mut total_glossed = 0usize;

    for entry in &mut entries {
        if is_content_html(&entry.name) {
            if let Ok(text) = std::str::from_utf8(&entry.data) {
                let (transformed, n) = transform_xhtml_wordwise(text, level);
                if n > 0 || transformed.len() != text.len() {
                    entry.data = transformed.into_bytes();
                    total_glossed += n;
                }
            }
        }
    }

    let out_bytes = write_zip(&entries);
    let out_p = Path::new(output_path);
    if let Some(parent) = out_p.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("Failed to create output dir: {e}"))?;
    }
    let tmp_path = format!("{output_path}.part");
    fs::write(&tmp_path, &out_bytes).map_err(|e| format!("Failed to write EPUB: {e}"))?;
    fs::rename(&tmp_path, out_p).map_err(|e| format!("Failed to finalize EPUB: {e}"))?;
    Ok(total_glossed)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::zip::{ZipEntry, METHOD_DEFLATE, METHOD_STORED};

    #[test]
    fn annotates_difficult_words_with_silent_rt_and_preserves_tts_text() {
        let html = "<html><head><title>Ephemeral</title></head><body><p>Her ubiquitous smile could alleviate Victor's melancholy.</p></body></html>";
        let (out, n) = transform_xhtml_wordwise(html, 2);
        assert_eq!(n, 3); // ubiquitous, alleviate, melancholy (Victor skipped as proper noun, title skipped)
        assert!(out.contains("<title>Ephemeral</title>"));
        assert!(out.contains("<ruby class=\"vw-ww\">ubiquitous<rt data-hint=\"everywhere\"></rt></ruby>"));
        assert!(out.contains("<ruby class=\"vw-ww\">alleviate<rt data-hint=\"ease\"></rt></ruby>"));
        assert!(out.contains("<ruby class=\"vw-ww\">melancholy<rt data-hint=\"deep sadness\"></rt></ruby>"));
        assert!(out.contains("Victor's"));
        assert!(out.contains("ruby-position:under"));
    }

    #[test]
    fn sentence_start_capitalized_word_is_annotated() {
        let html = "<html><body><p>Abruptly, he stopped. Ubiquitous signs appeared.</p></body></html>";
        let (out, n) = transform_xhtml_wordwise(html, 2);
        assert_eq!(n, 2);
        assert!(out.contains("<ruby class=\"vw-ww\">Abruptly<rt data-hint=\"sudden\"></rt></ruby>"));
        assert!(out.contains("<ruby class=\"vw-ww\">Ubiquitous<rt data-hint=\"everywhere\"></rt></ruby>"));
    }

    #[test]
    fn converts_full_epub_zip_atomically() {
        let dir = std::env::temp_dir().join("vw_wordwise_test");
        let _ = fs::create_dir_all(&dir);
        let in_path = dir.join("in.epub");
        let out_path = dir.join("out.epub");
        let entries = vec![
            ZipEntry {
                name: "mimetype".into(),
                method: METHOD_STORED,
                data: b"application/epub+zip".to_vec(),
            },
            ZipEntry {
                name: "OEBPS/ch1.xhtml".into(),
                method: METHOD_DEFLATE,
                data: b"<html><head></head><body><p>The ephemeral beauty was ubiquitous.</p></body></html>".to_vec(),
            },
        ];
        fs::write(&in_path, write_zip(&entries)).unwrap();
        let count = convert_epub_wordwise(
            in_path.to_str().unwrap(),
            out_path.to_str().unwrap(),
            2,
        )
        .unwrap();
        assert_eq!(count, 2);

        let out_entries = read_zip(&fs::read(&out_path).unwrap()).unwrap();
        assert_eq!(out_entries[0].name, "mimetype");
        assert_eq!(out_entries[0].method, METHOD_STORED);
        let ch1 = std::str::from_utf8(&out_entries[1].data).unwrap();
        assert!(ch1.contains("data-hint=\"short-lived\""));
        assert!(ch1.contains("data-hint=\"everywhere\""));
        let _ = fs::remove_dir_all(&dir);
    }
}
