use std::f32::consts::PI;

const SILENCE_THRESHOLD: f32 = 0.0018; // ~ -55 dBFS (gentle: preserves soft sentence endings)
const VALLEY_RMS_THRESHOLD: f32 = 0.0045;
const LEAD_MARGIN_MS: u32 = 90;
const TRAIL_MARGIN_MS: u32 = 140;
const FADE_IN_MS: u32 = 12;
const FADE_OUT_MS: u32 = 22;
const INTERNAL_MIN_GAP_MS: u32 = 210;

/// Supertonic's duration predictor tends to accelerate very short utterances (< 18 chars).
/// Gently damp speed on short lines so they keep a steady reading cadence with longer sentences.
pub fn recommended_supertonic_speed(base_speed: f32, text: &str) -> f32 {
    let base = base_speed.clamp(0.5, 2.5);
    let chars = text.trim().chars().count();
    let factor = if chars <= 6 {
        0.90
    } else if chars < 18 {
        0.90 + 0.10 * ((chars - 6) as f32 / 12.0)
    } else {
        1.0
    };
    (base * factor).clamp(0.5, 2.5)
}

/// Counts internal sentence boundaries inside a chunk (excluding trailing sentence-end punctuation).
pub fn count_internal_sentence_breaks(text: &str) -> usize {
    let trimmed = text.trim();
    let chars: Vec<char> = trimmed.chars().collect();
    let mut breaks = 0usize;
    let mut i = 0;
    while i + 1 < chars.len() {
        let c = chars[i];
        if matches!(c, '.' | '?' | '!' | '…') {
            // Skip repeated punctuation like "..." or "?!"
            while i + 1 < chars.len() && matches!(chars[i + 1], '.' | '?' | '!' | '…' | '"' | '\'' | '”' | '’') {
                i += 1;
            }
            // Must be followed by whitespace and more alphanumeric/Hangul content
            if i + 1 < chars.len() && chars[i + 1].is_whitespace() {
                let has_more = chars[i + 1..].iter().any(|x| x.is_alphanumeric());
                if has_more {
                    breaks += 1;
                }
            }
        }
        i += 1;
    }
    breaks
}

/// Extra pause (in ms) derived from sentence-ending punctuation for natural prosody.
pub fn punctuation_pause_bonus_ms(text: &str) -> i32 {
    let trimmed = text.trim_end_matches(|c: char| c.is_whitespace() || matches!(c, '"' | '\'' | '”' | '’' | ')' | ']'));
    if trimmed.ends_with("...") || trimmed.ends_with('…') {
        180
    } else if trimmed.ends_with('?') || trimmed.ends_with('!') {
        70
    } else if trimmed.ends_with(',') || trimmed.ends_with(';') || trimmed.ends_with(':') || trimmed.ends_with('—') {
        -120
    } else {
        0
    }
}

/// Gently trims excessive model-generated leading/trailing padding (~250ms per side in Supertonic)
/// without clipping soft consonants, applies smooth raised-cosine fades, and expands overly tight
/// internal sentence pauses when a chunk contains multiple sentences.
pub fn trim_and_normalize_speech(samples: &[f32], sample_rate: u32, text: &str) -> Vec<f32> {
    if samples.is_empty() || sample_rate == 0 {
        return samples.to_vec();
    }

    let sr = sample_rate as usize;
    let win = (sr * 16 / 1000).max(1); // 16ms RMS window
    let lead_margin = sr * LEAD_MARGIN_MS as usize / 1000;
    let trail_margin = sr * TRAIL_MARGIN_MS as usize / 1000;

    let first = find_first_active(samples, win, SILENCE_THRESHOLD);
    let last = find_last_active(samples, win, SILENCE_THRESHOLD);

    let mut out = match (first, last) {
        (Some(f), Some(l)) if f <= l => {
            let start = f.saturating_sub(lead_margin);
            let end = (l + 1 + trail_margin).min(samples.len());
            samples[start..end].to_vec()
        }
        _ => return samples.to_vec(),
    };

    // Expand tight internal sentence pauses if the text contains multiple sentences.
    let internal_breaks = count_internal_sentence_breaks(text);
    if internal_breaks > 0 {
        out = expand_internal_pauses(&out, sample_rate, internal_breaks, INTERNAL_MIN_GAP_MS);
    }

    // Apply smooth cosine fade-in / fade-out so boundaries never click.
    let fade_in = (sr * FADE_IN_MS as usize / 1000).min(out.len() / 4);
    for (i, s) in out.iter_mut().take(fade_in).enumerate() {
        let g = 0.5 * (1.0 - (PI * i as f32 / fade_in as f32).cos());
        *s *= g;
    }
    let fade_out = (sr * FADE_OUT_MS as usize / 1000).min(out.len() / 4);
    let len = out.len();
    for i in 0..fade_out {
        let g = 0.5 * (1.0 - (PI * i as f32 / fade_out as f32).cos());
        out[len - 1 - i] *= g;
    }

    // Soft-limit safety
    for s in &mut out {
        if !s.is_finite() {
            *s = 0.0;
        } else if *s > 0.99 {
            *s = 0.99;
        } else if *s < -0.99 {
            *s = -0.99;
        }
    }
    out
}

/// Appends exact sample-accurate inter-sentence silence to the end of `samples` so persistent
/// streaming playback (`AudioTrack MODE_STREAM`) never clips the sentence tail or skips the pause.
pub fn append_pause_silence(samples: &[f32], sample_rate: u32, pause_ms: u32, text: &str) -> Vec<f32> {
    if sample_rate == 0 {
        return samples.to_vec();
    }
    let total_pause_ms = (pause_ms as i32 + punctuation_pause_bonus_ms(text)).max(80) as usize;
    let silence_samples = sample_rate as usize * total_pause_ms / 1000;
    let mut out = Vec::with_capacity(samples.len() + silence_samples);
    out.extend_from_slice(samples);
    out.resize(samples.len() + silence_samples, 0.0);
    out
}

fn find_first_active(samples: &[f32], win: usize, thresh: f32) -> Option<usize> {
    let thresh_sq = thresh * thresh;
    let mut i = 0;
    while i < samples.len() {
        let end = (i + win).min(samples.len());
        let sum_sq: f32 = samples[i..end].iter().map(|&x| x * x).sum();
        let rms_sq = sum_sq / (end - i) as f32;
        if rms_sq > thresh_sq || samples[i..end].iter().any(|&x| x.abs() > thresh * 2.5) {
            return Some(i);
        }
        i += (win / 2).max(1);
    }
    None
}

fn find_last_active(samples: &[f32], win: usize, thresh: f32) -> Option<usize> {
    let thresh_sq = thresh * thresh;
    let step = (win / 2).max(1);
    let mut end = samples.len();
    while end > 0 {
        let start = end.saturating_sub(win);
        let sum_sq: f32 = samples[start..end].iter().map(|&x| x * x).sum();
        let rms_sq = sum_sq / (end - start) as f32;
        if rms_sq > thresh_sq || samples[start..end].iter().any(|&x| x.abs() > thresh * 2.5) {
            return Some(end - 1);
        }
        end = end.saturating_sub(step);
    }
    None
}

fn expand_internal_pauses(
    samples: &[f32],
    sample_rate: u32,
    expected_breaks: usize,
    target_gap_ms: u32,
) -> Vec<f32> {
    let sr = sample_rate as usize;
    let frame = (sr * 10 / 1000).max(1); // 10ms frames
    let num_frames = samples.len() / frame;
    if num_frames < 30 {
        return samples.to_vec();
    }

    // Ignore first & last 12% so we don't confuse leading/trailing silence with internal sentence breaks.
    let min_f = num_frames * 12 / 100;
    let max_f = num_frames * 88 / 100;

    let mut frame_rms = Vec::with_capacity(num_frames);
    for f in 0..num_frames {
        let s = f * frame;
        let e = s + frame;
        let rms = (samples[s..e].iter().map(|&x| x * x).sum::<f32>() / frame as f32).sqrt();
        frame_rms.push(rms);
    }

    // Collect contiguous low-energy valleys of >= 20ms (2 frames)
    #[derive(Clone, Copy)]
    struct Valley {
        mid_sample: usize,
        len_frames: usize,
        min_rms: f32,
    }

    let mut valleys = Vec::new();
    let mut f = min_f;
    while f < max_f {
        if frame_rms[f] < VALLEY_RMS_THRESHOLD {
            let start_f = f;
            let mut min_rms = frame_rms[f];
            while f < max_f && frame_rms[f] < VALLEY_RMS_THRESHOLD {
                if frame_rms[f] < min_rms {
                    min_rms = frame_rms[f];
                }
                f += 1;
            }
            let len_frames = f - start_f;
            if len_frames >= 2 {
                valleys.push(Valley {
                    mid_sample: ((start_f + f) / 2) * frame,
                    len_frames,
                    min_rms,
                });
            }
        } else {
            f += 1;
        }
    }

    if valleys.is_empty() {
        return samples.to_vec();
    }

    // Pick the top `expected_breaks` longest/quietest valleys
    valleys.sort_by(|a, b| {
        b.len_frames
            .cmp(&a.len_frames)
            .then_with(|| a.min_rms.partial_cmp(&b.min_rms).unwrap_or(std::cmp::Ordering::Equal))
    });
    valleys.truncate(expected_breaks);
    valleys.sort_by_key(|v| v.mid_sample);

    let target_frames = (target_gap_ms as usize / 10).max(2);
    let fade = (sr * 8 / 1000).max(1);

    let mut out = Vec::with_capacity(samples.len() + expected_breaks * sr * target_gap_ms as usize / 1000);
    let mut cursor = 0usize;
    for v in valleys {
        if v.len_frames >= target_frames || v.mid_sample <= cursor {
            continue;
        }
        let extra_ms = (target_frames - v.len_frames) * 10;
        let extra_samples = sr * extra_ms / 1000;
        let split = v.mid_sample.min(samples.len());
        let chunk_start = out.len();
        out.extend_from_slice(&samples[cursor..split]);
        let chunk_len = out.len() - chunk_start;
        let f_out = fade.min(chunk_len);
        for i in 0..f_out {
            let g = 0.5 * (1.0 - (PI * i as f32 / f_out as f32).cos());
            let idx = out.len() - 1 - i;
            out[idx] *= g;
        }
        out.resize(out.len() + extra_samples, 0.0);
        let tail_start = out.len();
        let next_end = samples.len();
        // We fade-in the beginning of the remaining segment when next valley or end arrives
        let f_in = fade.min(next_end - split);
        out.extend_from_slice(&samples[split..split + f_in]);
        for i in 0..f_in {
            let g = 0.5 * (1.0 - (PI * i as f32 / f_in as f32).cos());
            out[tail_start + i] *= g;
        }
        cursor = split + f_in;
    }
    if cursor < samples.len() {
        out.extend_from_slice(&samples[cursor..]);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn speed_damps_short_utterances() {
        assert!((recommended_supertonic_speed(1.0, "네.") - 0.90).abs() < 0.001);
        assert!((recommended_supertonic_speed(1.0, "그는 천천히 문을 열고 들어갔다.") - 1.0).abs() < 0.001);
    }

    #[test]
    fn trims_excess_silence_without_clipping_soft_tail() {
        let sr = 16000u32;
        let mut pcm = vec![0.0f32; 16000]; // 1s
        // Speech from 4000..12000, with soft tail 12000..12800 at amplitude 0.004
        for s in &mut pcm[4000..12000] {
            *s = 0.25;
        }
        for s in &mut pcm[12000..12800] {
            *s = 0.004;
        }
        let trimmed = trim_and_normalize_speech(&pcm, sr, "Hello.");
        // Should preserve soft tail (~0.004 > 0.0018) + margins (~90ms + ~140ms)
        assert!(trimmed.len() >= 8800 + (sr as usize * 180 / 1000));
        assert!(trimmed.len() < pcm.len());
    }

    #[test]
    fn expands_tight_internal_sentence_pause() {
        let sr = 16000u32;
        let mut pcm = vec![0.3f32; 16000]; // 1.0s speech
        // Tiny 30ms dip at 0.48s..0.51s (samples 7680..8160)
        for s in &mut pcm[7680..8160] {
            *s = 0.0;
        }
        let out = trim_and_normalize_speech(&pcm, sr, "\"First sentence.\" \"Second sentence.\"");
        assert!(out.len() > pcm.len() + 2000);
    }

    #[test]
    fn counts_internal_breaks_properly() {
        assert_eq!(count_internal_sentence_breaks("Hello world."), 0);
        assert_eq!(count_internal_sentence_breaks("Hello! How are you?"), 1);
        assert_eq!(count_internal_sentence_breaks("One. Two. Three."), 2);
    }
}
