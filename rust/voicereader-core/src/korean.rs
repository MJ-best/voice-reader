use crate::options::{apply_reading_options, ReadingOptions};

const SINO_DIGITS: [&str; 10] = ["영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구"];
const SMALL_UNITS: [&str; 4] = ["", "십", "백", "천"];
const BIG_UNITS: [&str; 5] = ["", "만", "억", "조", "경"];
const POW10: [u32; 4] = [1, 10, 100, 1000];

const NATIVE_ONES: [&str; 10] = ["", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉"];
const NATIVE_TENS: [&str; 10] = ["", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔"];

const NATIVE_COUNTERS: &[&str] = &[
    "개", "명", "사람", "살", "마리", "권", "잔", "병", "번째", "번", "가지", "그루", "벌", "켤레",
    "채", "척", "송이", "군데", "곳", "시간", "시", "달", "바퀴", "걸음", "끼", "줄", "쌍", "통",
];

const SINO_SUFFIXES: &[&str] = &["개월", "번지", "채널", "월"];

// Ordered longest-first for greedy prefix matching.
const UNITS: &[(&str, &str)] = &[
    ("km", "킬로미터"),
    ("cm", "센티미터"),
    ("mm", "밀리미터"),
    ("ml", "밀리리터"),
    ("mL", "밀리리터"),
    ("mg", "밀리그램"),
    ("kg", "킬로그램"),
    ("°C", "도"),
    ("℃", "도"),
    ("m", "미터"),
    ("g", "그램"),
    ("L", "리터"),
    ("%", "퍼센트"),
];

// Longest-first sorted at runtime / compile-time order:
const BIBLE_BOOKS: &[(&str, &str)] = &[
    ("예레미야애가", "예레미야애가"),
    ("데살로니가전서", "데살로니가전서"),
    ("데살로니가후서", "데살로니가후서"),
    ("고린도전서", "고린도전서"),
    ("고린도후서", "고린도후서"),
    ("갈라디아서", "갈라디아서"),
    ("에베소서", "에베소서"),
    ("빌립보서", "빌립보서"),
    ("골로새서", "골로새서"),
    ("디모데전서", "디모데전서"),
    ("디모데후서", "디모데후서"),
    ("빌레몬서", "빌레몬서"),
    ("히브리서", "히브리서"),
    ("야고보서", "야고보서"),
    ("베드로전서", "베드로전서"),
    ("베드로후서", "베드로후서"),
    ("요한계시록", "요한계시록"),
    ("열왕기상", "열왕기상"),
    ("열왕기하", "열왕기하"),
    ("사무엘상", "사무엘상"),
    ("사무엘하", "사무엘하"),
    ("느헤미야", "느헤미야"),
    ("마태복음", "마태복음"),
    ("마가복음", "마가복음"),
    ("누가복음", "누가복음"),
    ("요한복음", "요한복음"),
    ("사도행전", "사도행전"),
    ("예레미야", "예레미야"),
    ("출애굽기", "출애굽기"),
    ("레위기", "레위기"),
    ("민수기", "민수기"),
    ("신명기", "신명기"),
    ("여호수아", "여호수아"),
    ("사사기", "사사기"),
    ("역대상", "역대상"),
    ("역대하", "역대하"),
    ("에스라", "에스라"),
    ("에스더", "에스더"),
    ("전도서", "전도서"),
    ("아가서", "아가"),
    ("이사야", "이사야"),
    ("에스겔", "에스겔"),
    ("다니엘", "다니엘"),
    ("호세아", "호세아"),
    ("아모스", "아모스"),
    ("오바댜", "오바댜"),
    ("하박국", "하박국"),
    ("스바냐", "스바냐"),
    ("스가랴", "스가랴"),
    ("말라기", "말라기"),
    ("로마서", "로마서"),
    ("디도서", "디도서"),
    ("요한일서", "요한일서"),
    ("요한1서", "요한일서"),
    ("요한이서", "요한이서"),
    ("요한2서", "요한이서"),
    ("요한삼서", "요한삼서"),
    ("요한3서", "요한삼서"),
    ("유다서", "유다서"),
    ("창세기", "창세기"),
    ("계시록", "요한계시록"),
    ("욥기", "욥기"),
    ("시편", "시편"),
    ("잠언", "잠언"),
    ("아가", "아가"),
    ("요엘", "요엘"),
    ("요나", "요나"),
    ("미가", "미가"),
    ("나훔", "나훔"),
    ("학개", "학개"),
    ("삼상", "사무엘상"),
    ("삼하", "사무엘하"),
    ("왕상", "열왕기상"),
    ("왕하", "열왕기하"),
    ("대상", "역대상"),
    ("대하", "역대하"),
    ("애가", "예레미야애가"),
    ("고전", "고린도전서"),
    ("고후", "고린도후서"),
    ("살전", "데살로니가전서"),
    ("살후", "데살로니가후서"),
    ("딤전", "디모데전서"),
    ("딤후", "디모데후서"),
    ("벧전", "베드로전서"),
    ("벧후", "베드로후서"),
    ("요일", "요한일서"),
    ("요이", "요한이서"),
    ("요삼", "요한삼서"),
    ("창", "창세기"),
    ("출", "출애굽기"),
    ("레", "레위기"),
    ("민", "민수기"),
    ("신", "신명기"),
    ("수", "여호수아"),
    ("삿", "사사기"),
    ("룻", "룻기"),
    ("룻기", "룻기"),
    ("스", "에스라"),
    ("느", "느헤미야"),
    ("에", "에스더"),
    ("욥", "욥기"),
    ("시", "시편"),
    ("잠", "잠언"),
    ("전", "전도서"),
    ("아", "아가"),
    ("사", "이사야"),
    ("렘", "예레미야"),
    ("애", "예레미야애가"),
    ("겔", "에스겔"),
    ("단", "다니엘"),
    ("호", "호세아"),
    ("욜", "요엘"),
    ("암", "아모스"),
    ("옵", "오바댜"),
    ("욘", "요나"),
    ("미", "미가"),
    ("나", "나훔"),
    ("합", "하박국"),
    ("습", "스바냐"),
    ("학", "학개"),
    ("슥", "스가랴"),
    ("말", "말라기"),
    ("마", "마태복음"),
    ("막", "마가복음"),
    ("눅", "누가복음"),
    ("요", "요한복음"),
    ("행", "사도행전"),
    ("롬", "로마서"),
    ("갈", "갈라디아서"),
    ("엡", "에베소서"),
    ("빌", "빌립보서"),
    ("골", "골로새서"),
    ("딛", "디도서"),
    ("몬", "빌레몬서"),
    ("히", "히브리서"),
    ("약", "야고보서"),
    ("유", "유다서"),
    ("계", "요한계시록"),
];

#[inline]
fn is_han(ch: char) -> bool {
    matches!(
        ch as u32,
        0x3400..=0x4DBF | 0x4E00..=0x9FFF | 0xF900..=0xFAFF
    )
}

#[inline]
fn is_hangul_syllable(ch: char) -> bool {
    matches!(ch as u32, 0xAC00..=0xD7A3)
}

fn letter_name(c: char) -> Option<&'static str> {
    Some(match c {
        'A' => "에이",
        'B' => "비",
        'C' => "씨",
        'D' => "디",
        'E' => "이",
        'F' => "에프",
        'G' => "지",
        'H' => "에이치",
        'I' => "아이",
        'J' => "제이",
        'K' => "케이",
        'L' => "엘",
        'M' => "엠",
        'N' => "엔",
        'O' => "오",
        'P' => "피",
        'Q' => "큐",
        'R' => "알",
        'S' => "에스",
        'T' => "티",
        'U' => "유",
        'V' => "브이",
        'W' => "더블유",
        'X' => "엑스",
        'Y' => "와이",
        'Z' => "지",
        _ => return None,
    })
}

pub fn sino(n: u64) -> String {
    if n == 0 {
        return "영".to_string();
    }
    let mut groups = Vec::new();
    let mut cur = n;
    while cur > 0 {
        groups.push((cur % 10000) as u32);
        cur /= 10000;
    }
    let mut out = String::new();
    for i in (0..groups.len()).rev() {
        let g = groups[i];
        if g == 0 {
            continue;
        }
        if g == 1 && i == 1 {
            out.push('만');
        } else {
            append_group(g, &mut out);
            if i < BIG_UNITS.len() {
                out.push_str(BIG_UNITS[i]);
            }
        }
    }
    out
}

fn append_group(g: u32, out: &mut String) {
    for p in (0..4).rev() {
        let d = (g / POW10[p] % 10) as usize;
        if d == 0 {
            continue;
        }
        if d == 1 && p > 0 {
            out.push_str(SMALL_UNITS[p]);
        } else {
            out.push_str(SINO_DIGITS[d]);
            out.push_str(SMALL_UNITS[p]);
        }
    }
}

pub fn native(n: u64) -> String {
    if n == 20 {
        return "스무".to_string();
    }
    if (1..=99).contains(&n) {
        let tens = NATIVE_TENS[(n / 10) as usize];
        let ones = NATIVE_ONES[(n % 10) as usize];
        return format!("{tens}{ones}");
    }
    sino(n)
}

fn digits_str(s: &str, zero: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        if b == b'0' {
            out.push_str(zero);
        } else if b.is_ascii_digit() {
            out.push_str(SINO_DIGITS[(b - b'0') as usize]);
        }
    }
    out
}

pub fn normalize_korean(text: &str, options: ReadingOptions) -> String {
    let step1 = replace_hanja_then_hangul(text);
    let step2 = remove_parenthesized_hanja(&step1);
    let step3 = apply_reading_options(&step2, options);
    let step4 = replace_phones(&step3);
    let step5 = replace_dates(&step4);
    let step6 = replace_times(&step5);
    let step7 = replace_bible_verses(&step6);
    let step8 = replace_numbers(&step7);
    if options.spell_acronyms {
        replace_acronyms(&step8)
    } else {
        step8
    }
}

/// `大韓民國(대한민국)` -> `대한민국`
fn replace_hanja_then_hangul(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < text.len() {
        let ch = text[i..].chars().next().unwrap();
        if is_han(ch) {
            let mut j = i + ch.len_utf8();
            while j < text.len() {
                let c = text[j..].chars().next().unwrap();
                if is_han(c) {
                    j += c.len_utf8();
                } else {
                    break;
                }
            }
            let mut k = j;
            while k < text.len() {
                let c = text[k..].chars().next().unwrap();
                if c.is_whitespace() {
                    k += c.len_utf8();
                } else {
                    break;
                }
            }
            if text[k..].starts_with('(') {
                let inner_start = k + 1;
                if let Some(rel_close) = text[inner_start..].find(')') {
                    let inner = &text[inner_start..inner_start + rel_close];
                    if !inner.is_empty()
                        && inner.chars().all(|c| is_hangul_syllable(c) || c.is_whitespace())
                    {
                        out.push_str(inner);
                        i = inner_start + rel_close + 1;
                        continue;
                    }
                }
            }
            out.push_str(&text[i..j]);
            i = j;
            continue;
        }
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

/// `대한민국(大韓民國)` -> `대한민국`
fn remove_parenthesized_hanja(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < text.len() {
        let mut ws_end = i;
        while ws_end < text.len() {
            let c = text[ws_end..].chars().next().unwrap();
            if c.is_whitespace() {
                ws_end += c.len_utf8();
            } else {
                break;
            }
        }
        if text[ws_end..].starts_with('(') {
            let inner_start = ws_end + 1;
            if let Some(rel_close) = text[inner_start..].find(')') {
                let inner = &text[inner_start..inner_start + rel_close];
                if !inner.is_empty() && inner.chars().all(is_han) {
                    i = inner_start + rel_close + 1;
                    continue;
                }
            }
        }
        let ch = text[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn replace_phones(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'0' && (i == 0 || !bytes[i - 1].is_ascii_digit()) {
            if let Some((end, p1, p2, p3)) = match_phone(text, i) {
                out.push_str(&digits_str(p1, "공"));
                out.push(' ');
                out.push_str(&digits_str(p2, "공"));
                out.push(' ');
                out.push_str(&digits_str(p3, "공"));
                i = end;
                continue;
            }
        }
        let ch = text[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn match_phone(text: &str, i: usize) -> Option<(usize, &str, &str, &str)> {
    let b = text.as_bytes();
    let mut j = i + 1;
    let mut d1 = 0;
    while j < b.len() && b[j].is_ascii_digit() && d1 < 2 {
        j += 1;
        d1 += 1;
    }
    if !(1..=2).contains(&d1) || j >= b.len() || b[j] != b'-' {
        return None;
    }
    let p1 = &text[i..j];
    j += 1;
    let p2_start = j;
    let mut d2 = 0;
    while j < b.len() && b[j].is_ascii_digit() && d2 < 4 {
        j += 1;
        d2 += 1;
    }
    if !(3..=4).contains(&d2) || j >= b.len() || b[j] != b'-' {
        return None;
    }
    let p2 = &text[p2_start..j];
    j += 1;
    let p3_start = j;
    let mut d3 = 0;
    while j < b.len() && b[j].is_ascii_digit() && d3 < 4 {
        j += 1;
        d3 += 1;
    }
    if d3 != 4 || (j < b.len() && b[j].is_ascii_digit()) {
        return None;
    }
    let p3 = &text[p3_start..j];
    Some((j, p1, p2, p3))
}

fn replace_dates(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i].is_ascii_digit() && (i == 0 || !bytes[i - 1].is_ascii_digit()) {
            if let Some((end, y, mo, d)) = match_date(text, i) {
                if let (Ok(mo_n), Ok(d_n)) = (mo.parse::<u32>(), d.parse::<u32>()) {
                    if (1..=12).contains(&mo_n) && (1..=31).contains(&d_n) {
                        out.push_str(&format!("{y}년 {mo_n}월 {d_n}일"));
                        i = end;
                        continue;
                    }
                }
            }
        }
        let ch = text[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn match_date(text: &str, i: usize) -> Option<(usize, &str, &str, &str)> {
    let b = text.as_bytes();
    if i + 4 >= b.len() || !b[i..i + 4].iter().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let y = &text[i..i + 4];
    let sep = b[i + 4];
    if !matches!(sep, b'.' | b'/' | b'-') {
        return None;
    }
    let mut j = i + 5;
    let mo_start = j;
    let mut d_mo = 0;
    while j < b.len() && b[j].is_ascii_digit() && d_mo < 2 {
        j += 1;
        d_mo += 1;
    }
    if !(1..=2).contains(&d_mo) || j >= b.len() || !matches!(b[j], b'.' | b'/' | b'-') {
        return None;
    }
    let mo = &text[mo_start..j];
    j += 1;
    let d_start = j;
    let mut d_day = 0;
    while j < b.len() && b[j].is_ascii_digit() && d_day < 2 {
        j += 1;
        d_day += 1;
    }
    if !(1..=2).contains(&d_day) {
        return None;
    }
    if j < b.len() && b[j].is_ascii_digit() {
        return None;
    }
    let d = &text[d_start..j];
    if j < b.len() && b[j] == b'.' {
        j += 1;
    }
    Some((j, y, mo, d))
}

fn replace_times(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < text.len() {
        let half = if text[i..].starts_with("오전") {
            Some("오전")
        } else if text[i..].starts_with("오후") {
            Some("오후")
        } else {
            None
        };
        if let Some(h_word) = half {
            let mut j = i + h_word.len();
            if j < text.len() {
                let c = text[j..].chars().next().unwrap();
                if c.is_whitespace() {
                    j += c.len_utf8();
                }
            }
            let b = text.as_bytes();
            let h_start = j;
            let mut hd = 0;
            while j < b.len() && b[j].is_ascii_digit() && hd < 2 {
                j += 1;
                hd += 1;
            }
            if (1..=2).contains(&hd) && j + 2 < b.len() && b[j] == b':' {
                let h = &text[h_start..j];
                let m_start = j + 1;
                if b[m_start].is_ascii_digit()
                    && b[m_start + 1].is_ascii_digit()
                    && (m_start + 2 == b.len() || !b[m_start + 2].is_ascii_digit())
                {
                    let min_str = &text[m_start..m_start + 2];
                    out.push_str(&format!("{h_word} {h}시"));
                    if min_str != "00" {
                        let m_val: u32 = min_str.parse().unwrap_or(0);
                        out.push_str(&format!(" {m_val}분"));
                    }
                    i = m_start + 2;
                    continue;
                }
            }
        }
        let ch = text[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn replace_bible_verses(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < text.len() {
        let prev_is_hangul = text[..i]
            .chars()
            .next_back()
            .map(is_hangul_syllable)
            .unwrap_or(false);
        if !prev_is_hangul {
            if let Some((end, replacement)) = match_bible_verse(text, i) {
                out.push_str(&replacement);
                i = end;
                continue;
            }
        }
        let ch = text[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn read_up_to_3_digits(s: &str, start: usize) -> Option<(usize, &str)> {
    let b = s.as_bytes();
    let mut j = start;
    let mut count = 0;
    while j < b.len() && b[j].is_ascii_digit() && count < 3 {
        j += 1;
        count += 1;
    }
    if count >= 1 {
        Some((j, &s[start..j]))
    } else {
        None
    }
}

fn match_bible_verse(text: &str, i: usize) -> Option<(usize, String)> {
    let rest = &text[i..];
    for &(abbr, full) in BIBLE_BOOKS {
        if !rest.starts_with(abbr) {
            continue;
        }
        let mut j = i + abbr.len();
        if j < text.len() {
            let c = text[j..].chars().next().unwrap();
            if c.is_whitespace() {
                j += c.len_utf8();
            }
        }
        let (j_after_ch, chapter) = read_up_to_3_digits(text, j)?;
        if j_after_ch >= text.len() || text.as_bytes()[j_after_ch] != b':' {
            continue;
        }
        let (mut end, verse) = read_up_to_3_digits(text, j_after_ch + 1)?;
        let unit = if full == "시편" { "편" } else { "장" };
        let mut res = format!("{full} {chapter}{unit} {verse}절");

        // Optional range: `(?:\s?[-~–]\s?(?:(\d{1,3}):)?(\d{1,3}))?`
        let mut r = end;
        if r < text.len() {
            let c = text[r..].chars().next().unwrap();
            if c.is_whitespace() {
                r += c.len_utf8();
            }
        }
        if r < text.len() {
            let dash = text[r..].chars().next().unwrap();
            if matches!(dash, '-' | '~' | '–') {
                r += dash.len_utf8();
                if r < text.len() {
                    let c = text[r..].chars().next().unwrap();
                    if c.is_whitespace() {
                        r += c.len_utf8();
                    }
                }
                if let Some((j1, n1)) = read_up_to_3_digits(text, r) {
                    if j1 < text.len() && text.as_bytes()[j1] == b':' {
                        if let Some((j2, n2)) = read_up_to_3_digits(text, j1 + 1) {
                            res.push_str(&format!("에서 {n1}{unit} {n2}절"));
                            end = j2;
                        }
                    } else {
                        res.push_str(&format!("에서 {n1}절"));
                        end = j1;
                    }
                }
            }
        }
        return Some((end, res));
    }
    None
}

fn replace_numbers(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < text.len() {
        if let Some((end, replacement)) = match_number(text, i) {
            out.push_str(&replacement);
            i = end;
            continue;
        }
        let ch = text[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn match_number(text: &str, i: usize) -> Option<(usize, String)> {
    let mut j = i;
    let mut prefix = "";
    if text[j..].starts_with('제') {
        let after = j + '제'.len_utf8();
        if after < text.len() && text[after..].chars().next().unwrap().is_whitespace() {
            let after_ws = after + text[after..].chars().next().unwrap().len_utf8();
            if after_ws < text.len() && text.as_bytes()[after_ws].is_ascii_digit() {
                prefix = "제";
                j = after_ws;
            }
        } else if after < text.len() && text.as_bytes()[after].is_ascii_digit() {
            prefix = "제";
            j = after;
        }
    }

    let b = text.as_bytes();
    if j >= b.len() || !b[j].is_ascii_digit() {
        return None;
    }

    // Match either grouped `1,234,567` or plain `\d+`
    let num_start = j;
    let grouped_end = match_grouped_digits(b, j);
    let mut plain_end = j;
    while plain_end < b.len() && b[plain_end].is_ascii_digit() {
        plain_end += 1;
    }
    let num_end = grouped_end.unwrap_or(plain_end).max(plain_end);
    let grouped = &text[num_start..num_end];
    j = num_end;

    // Optional `.fraction`
    let mut fraction = "";
    if j + 1 < b.len() && b[j] == b'.' && b[j + 1].is_ascii_digit() {
        let f_start = j;
        j += 1;
        while j < b.len() && b[j].is_ascii_digit() {
            j += 1;
        }
        fraction = &text[f_start..j];
    }

    // Optional `\s?(unit|counter)`
    let mut unit = "";
    let mut counter = "";
    let mut after_ws = j;
    if after_ws < text.len() {
        let c = text[after_ws..].chars().next().unwrap();
        if c.is_whitespace() {
            after_ws += c.len_utf8();
        }
    }

    let rest = &text[after_ws..];
    let mut matched_suffix_end = j;
    for &(u, u_ko) in UNITS {
        if rest.starts_with(u) {
            let after_u = &rest[u.len()..];
            let next_is_ascii_alpha = after_u
                .chars()
                .next()
                .map(|c| c.is_ascii_alphabetic())
                .unwrap_or(false);
            if !next_is_ascii_alpha {
                unit = u_ko;
                matched_suffix_end = after_ws + u.len();
                break;
            }
        }
    }
    if unit.is_empty() {
        // Longest counter/sino suffix wins
        let mut best: Option<&str> = None;
        for &s in SINO_SUFFIXES.iter().chain(NATIVE_COUNTERS.iter()) {
            if rest.starts_with(s) {
                if best.map(|b| s.len() > b.len()).unwrap_or(true) {
                    best = Some(s);
                }
            }
        }
        if let Some(c) = best {
            counter = c;
            matched_suffix_end = after_ws + c.len();
        }
    }

    let end = if !unit.is_empty() || !counter.is_empty() {
        matched_suffix_end
    } else {
        j
    };

    let raw: String = grouped.chars().filter(|&c| c != ',').collect();
    let n = raw.parse::<u64>().ok();
    let number = match n {
        None => digits_str(&raw, "영"),
        Some(_) if raw.len() > 1 && raw.starts_with('0') => digits_str(&raw, "공"),
        Some(1) if counter == "번째" && prefix.is_empty() => "첫".to_string(),
        Some(6) if counter == "월" => "유".to_string(),
        Some(10) if counter == "월" => "시".to_string(),
        Some(val)
            if NATIVE_COUNTERS.contains(&counter) && prefix.is_empty() && fraction.is_empty() =>
        {
            native(val)
        }
        Some(val) => sino(val),
    };

    let point = if fraction.is_empty() {
        String::new()
    } else {
        format!(" 점 {}", digits_str(&fraction[1..], "영"))
    };

    let suffix = if !unit.is_empty() {
        format!(" {unit}")
    } else if counter == "월" {
        "월".to_string()
    } else if !counter.is_empty() {
        format!(" {counter}")
    } else {
        String::new()
    };

    Some((end, format!("{prefix}{number}{point}{suffix}")))
}

fn match_grouped_digits(b: &[u8], start: usize) -> Option<usize> {
    let mut j = start;
    let mut first = 0;
    while j < b.len() && b[j].is_ascii_digit() && first < 3 {
        j += 1;
        first += 1;
    }
    if first == 0 {
        return None;
    }
    let mut has_comma = false;
    while j + 3 < b.len()
        && b[j] == b','
        && b[j + 1].is_ascii_digit()
        && b[j + 2].is_ascii_digit()
        && b[j + 3].is_ascii_digit()
    {
        has_comma = true;
        j += 4;
    }
    if has_comma && (j == b.len() || !b[j].is_ascii_digit()) {
        Some(j)
    } else {
        None
    }
}

fn replace_acronyms(text: &str) -> String {
    let b = text.as_bytes();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < b.len() {
        if b[i].is_ascii_uppercase() && (i == 0 || !b[i - 1].is_ascii_alphabetic()) {
            let mut j = i;
            while j < b.len() && b[j].is_ascii_uppercase() {
                j += 1;
            }
            let len = j - i;
            if (2..=4).contains(&len) && (j == b.len() || !b[j].is_ascii_alphabetic()) {
                for &ch in &b[i..j] {
                    if let Some(name) = letter_name(ch as char) {
                        out.push_str(name);
                    }
                }
                i = j;
                continue;
            }
        }
        let ch = text[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

/// User pronunciation fixes, applied before normalization. Keys match literally, longest first.
pub struct PronunciationDictionary {
    entries: Vec<(String, String)>,
}

impl PronunciationDictionary {
    pub fn new(mut entries: Vec<(String, String)>) -> Self {
        entries.retain(|(k, _)| !k.trim().is_empty());
        entries.sort_by(|a, b| b.0.len().cmp(&a.0.len()));
        Self { entries }
    }

    pub fn apply(&self, text: &str) -> String {
        if self.entries.is_empty() {
            return text.to_string();
        }
        let mut out = String::with_capacity(text.len());
        let bytes = text.as_bytes();
        let mut i = 0;
        while i < text.len() {
            let mut matched = None;
            for (key, val) in &self.entries {
                if !text[i..].starts_with(key.as_str()) {
                    continue;
                }
                let first_ascii = key.as_bytes().first().copied().unwrap().is_ascii_alphanumeric();
                let last_ascii = key.as_bytes().last().copied().unwrap().is_ascii_alphanumeric();
                if first_ascii && i > 0 && bytes[i - 1].is_ascii_alphanumeric() {
                    continue;
                }
                let end = i + key.len();
                if last_ascii && end < bytes.len() && bytes[end].is_ascii_alphanumeric() {
                    continue;
                }
                matched = Some((end, val.as_str()));
                break;
            }
            if let Some((end, val)) = matched {
                out.push_str(val);
                i = end;
            } else {
                let ch = text[i..].chars().next().unwrap();
                out.push(ch);
                i += ch.len_utf8();
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn check(expected: &str, input: &str) {
        assert_eq!(normalize_korean(input, ReadingOptions::default()), expected);
    }

    #[test]
    fn test_sino() {
        assert_eq!(sino(0), "영");
        assert_eq!(sino(10), "십");
        assert_eq!(sino(11), "십일");
        assert_eq!(sino(100), "백");
        assert_eq!(sino(2026), "이천이십육");
        assert_eq!(sino(10000), "만");
        assert_eq!(sino(10001), "만일");
        assert_eq!(sino(12345), "만이천삼백사십오");
        assert_eq!(sino(110000), "십일만");
        assert_eq!(sino(100_000_000), "일억");
        assert_eq!(sino(1_0000_0000_0000), "일조");
    }

    #[test]
    fn test_native_counters() {
        check("세 개", "3개");
        check("세 개", "3 개");
        check("스무 살", "20살");
        check("스물한 명", "21명");
        check("백이십 개", "120개");
        check("첫 번째, 두 번째", "1번째, 2번째");
        check("일곱 시 삼십분", "7시 30분");
        check("오 개월", "5개월");
        check("삼백십이 권의 책", "312권의 책");
        check("제삼 권", "제3권");
    }

    #[test]
    fn test_dates_and_times() {
        check("이천이십육년 시월 십삼일", "2026년 10월 13일");
        check("이천이십육년 시월 십삼일", "2026.10.13.");
        check("유월", "6월");
        check("오후 세 시 오분", "오후 3:05");
    }

    #[test]
    fn test_bible_verses() {
        check("요한복음 삼장 십육절", "요한복음 3:16");
        check("요한복음 삼장 십육절", "요 3:16");
        check("(로마서 팔장 이십팔절)", "(롬 8:28)");
        check("고린도전서 십삼장 사절", "고전13:4");
        check("시편 이십삼편 일절", "시 23:1");
        check("마태복음 오장 삼절에서 십이절", "마 5:3-12");
        check("창세기 일장 일절에서 이장 삼절", "창 1:1~2:3");
        check("요한일서 사장 팔절", "요한1서 4:8");
        check("예레미야애가 삼장 이십이절", "예레미야애가 3:22");
        check("점수는 삼:이였다", "점수는 3:2였다");
        check("결과 삼:이", "결과 3:2");
    }

    #[test]
    fn test_units_and_decimals() {
        check("오 킬로미터", "5km");
        check("삼 점 오 킬로그램", "3.5kg");
        check("오십 퍼센트", "50%");
        check("삼 점 일사", "3.14");
        check("오 mangoes", "5 mangoes");
        check("천이백삼십사원", "1,234원");
    }

    #[test]
    fn test_digit_strings_and_acronyms() {
        check("공일공 일이삼사 오육칠팔", "010-1234-5678");
        check("공칠", "07");
        check("에이아이가 티티에스 엔진을", "AI가 TTS 엔진을");
        check("Hello UNESCO", "Hello UNESCO");
        check("대한민국", "대한민국(大韓民國)");
        check("대한민국", "大韓民國(대한민국)");
    }

    #[test]
    fn test_dictionary() {
        let dict = PronunciationDictionary::new(vec![
            ("Readium".into(), "리디움".into()),
            ("AI".into(), "인공지능".into()),
            ("값".into(), "갑".into()),
        ]);
        assert_eq!(dict.apply("Readium을 써요"), "리디움을 써요");
        assert_eq!(dict.apply("MAIL AI"), "MAIL 인공지능");
        assert_eq!(dict.apply("값이 올랐다"), "갑이 올랐다");
    }
}
