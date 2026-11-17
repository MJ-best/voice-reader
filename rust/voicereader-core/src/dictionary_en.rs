use crate::dictionary_en_data::WORDWISE_DICT;

/// Difficulty tiers for Word Wise hints:
/// - 1: Intermediate (B1-B2)
/// - 2: Upper-Intermediate (B2)
/// - 3: Advanced (C1)
/// - 4: Literary / Rare (C2)
///
/// User `level`:
/// - 0: Off
/// - 1: Light (only tiers 3..=4)
/// - 2: Medium (tiers 2..=4)
/// - 3: Detailed (tiers 1..=4)
pub fn min_tier_for_level(level: u8) -> Option<u8> {
    match level {
        1 => Some(3),
        2 => Some(2),
        3 => Some(1),
        _ => None,
    }
}

const IRREGULARS: &[(&str, &str)] = &[
    ("abode", "abide"),
    ("arisen", "arise"),
    ("arose", "arise"),
    ("awoke", "awake"),
    ("awoken", "awake"),
    ("bade", "bid"),
    ("begat", "beget"),
    ("begot", "beget"),
    ("begotten", "beget"),
    ("beheld", "behold"),
    ("bereft", "bereave"),
    ("besought", "beseech"),
    ("bidden", "bid"),
    ("bound", "bind"),
    ("clung", "cling"),
    ("crept", "creep"),
    ("dealt", "deal"),
    ("dug", "dig"),
    ("dwelt", "dwell"),
    ("fled", "flee"),
    ("flung", "fling"),
    ("forbade", "forbid"),
    ("forbidden", "forbid"),
    ("forgave", "forgive"),
    ("forgiven", "forgive"),
    ("forsaken", "forsake"),
    ("forsook", "forsake"),
    ("fought", "fight"),
    ("froze", "freeze"),
    ("frozen", "freeze"),
    ("ground", "grind"),
    ("knelt", "kneel"),
    ("leapt", "leap"),
    ("lit", "light"),
    ("misled", "mislead"),
    ("overcame", "overcome"),
    ("pled", "plead"),
    ("shaken", "shake"),
    ("shone", "shine"),
    ("shook", "shake"),
    ("shrank", "shrink"),
    ("shrunk", "shrink"),
    ("slain", "slay"),
    ("slew", "slay"),
    ("slung", "sling"),
    ("smitten", "smite"),
    ("smote", "smite"),
    ("sought", "seek"),
    ("sped", "speed"),
    ("spun", "spin"),
    ("stole", "steal"),
    ("stolen", "steal"),
    ("stricken", "strike"),
    ("stridden", "stride"),
    ("striven", "strive"),
    ("strode", "stride"),
    ("strove", "strive"),
    ("struck", "strike"),
    ("swept", "sweep"),
    ("swollen", "swell"),
    ("swore", "swear"),
    ("sworn", "swear"),
    ("swung", "swing"),
    ("tore", "tear"),
    ("torn", "tear"),
    ("trod", "tread"),
    ("trodden", "tread"),
    ("undertaken", "undertake"),
    ("undertook", "undertake"),
    ("wept", "weep"),
    ("withheld", "withhold"),
    ("withstood", "withstand"),
    ("wove", "weave"),
    ("woven", "weave"),
    ("wrought", "work"),
    ("wrung", "wring"),
];

fn exact_lookup(lower: &str) -> Option<(u8, &'static str)> {
    WORDWISE_DICT
        .binary_search_by_key(&lower, |&(w, _, _)| w)
        .ok()
        .map(|idx| (WORDWISE_DICT[idx].1, WORDWISE_DICT[idx].2))
}

/// Looks up a word (handling English inflections and irregular forms) and returns its
/// `(tier, simple_english_hint)` if its difficulty meets `level`.
pub fn lookup_hint(word: &str, level: u8) -> Option<(u8, &'static str)> {
    let min_tier = min_tier_for_level(level)?;
    if word.len() < 4 || !word.is_ascii() || !word.bytes().all(|b| b.is_ascii_alphabetic()) {
        return None;
    }
    let lower = word.to_ascii_lowercase();

    if let Some(hit) = exact_lookup(&lower) {
        return if hit.0 >= min_tier { Some(hit) } else { None };
    }

    if let Ok(idx) = IRREGULARS.binary_search_by_key(&lower.as_str(), |&(f, _)| f) {
        if let Some(hit) = exact_lookup(IRREGULARS[idx].1) {
            return if hit.0 >= min_tier { Some(hit) } else { None };
        }
    }

    for cand in lemmatize_candidates(&lower) {
        if let Some(hit) = exact_lookup(&cand) {
            return if hit.0 >= min_tier { Some(hit) } else { None };
        }
    }
    None
}

fn lemmatize_candidates(w: &str) -> Vec<String> {
    let mut cands = Vec::with_capacity(8);
    if let Some(stem) = w.strip_suffix("ies") {
        if stem.len() >= 2 {
            cands.push(format!("{stem}y"));
        }
    }
    if let Some(stem) = w.strip_suffix("ied") {
        if stem.len() >= 2 {
            cands.push(format!("{stem}y"));
        }
    }
    if let Some(stem) = w.strip_suffix("ier") {
        if stem.len() >= 2 {
            cands.push(format!("{stem}y"));
        }
    }
    if let Some(stem) = w.strip_suffix("iest") {
        if stem.len() >= 2 {
            cands.push(format!("{stem}y"));
        }
    }
    if let Some(stem) = w.strip_suffix("ily") {
        if stem.len() >= 2 {
            cands.push(format!("{stem}y"));
        }
    }
    if let Some(stem) = w.strip_suffix("ally") {
        if stem.len() >= 3 {
            cands.push(format!("{stem}al"));
            cands.push(stem.to_string());
        }
    }
    if let Some(stem) = w.strip_suffix("ly") {
        if stem.len() >= 3 {
            cands.push(stem.to_string());
            cands.push(format!("{stem}e"));
            if stem.ends_with('l') && !stem.ends_with("ll") {
                cands.push(format!("{stem}e"));
            }
        }
    }
    if let Some(stem) = w.strip_suffix("es") {
        if stem.len() >= 3 {
            cands.push(stem.to_string());
        }
    }
    if let Some(stem) = w.strip_suffix('s') {
        if stem.len() >= 3 && !stem.ends_with('s') {
            cands.push(stem.to_string());
        }
    }
    if let Some(stem) = w.strip_suffix("ed") {
        if stem.len() >= 3 {
            cands.push(stem.to_string());
            cands.push(format!("{stem}e"));
            let b = stem.as_bytes();
            if b.len() >= 2 && b[b.len() - 1] == b[b.len() - 2] {
                cands.push(stem[..stem.len() - 1].to_string());
            }
        }
    }
    if let Some(stem) = w.strip_suffix("ing") {
        if stem.len() >= 3 {
            cands.push(stem.to_string());
            cands.push(format!("{stem}e"));
            let b = stem.as_bytes();
            if b.len() >= 2 && b[b.len() - 1] == b[b.len() - 2] {
                cands.push(stem[..stem.len() - 1].to_string());
            }
        }
    }
    if let Some(stem) = w.strip_suffix("er") {
        if stem.len() >= 3 {
            cands.push(stem.to_string());
            cands.push(format!("{stem}e"));
        }
    }
    if let Some(stem) = w.strip_suffix("est") {
        if stem.len() >= 3 {
            cands.push(stem.to_string());
            cands.push(format!("{stem}e"));
        }
    }
    if let Some(stem) = w.strip_suffix("ness") {
        if stem.len() >= 3 {
            cands.push(stem.to_string());
            if let Some(s2) = stem.strip_suffix('i') {
                cands.push(format!("{s2}y"));
            }
        }
    }
    cands
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dictionary_is_sorted_for_binary_search() {
        for win in WORDWISE_DICT.windows(2) {
            assert!(
                win[0].0 < win[1].0,
                "Dictionary not strictly sorted at '{}' >= '{}'",
                win[0].0,
                win[1].0
            );
        }
        for win in IRREGULARS.windows(2) {
            assert!(
                win[0].0 < win[1].0,
                "Irregulars not sorted at '{}' >= '{}'",
                win[0].0,
                win[1].0
            );
        }
    }

    #[test]
    fn resolves_base_and_inflected_words() {
        assert_eq!(lookup_hint("ubiquitous", 2).map(|x| x.1), Some("everywhere"));
        assert_eq!(lookup_hint("contemplated", 2).map(|x| x.1), Some("think deeply"));
        assert_eq!(lookup_hint("ephemerally", 1).map(|x| x.1), Some("short-lived"));
        assert_eq!(lookup_hint("forsook", 1).map(|x| x.1), Some("abandon"));
        assert_eq!(lookup_hint("ubiquitous", 0), None);
    }
}
