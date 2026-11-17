#![allow(non_camel_case_types, non_snake_case, dead_code)]

use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;

use crate::audio::{
    append_pause_silence, punctuation_pause_bonus_ms, recommended_supertonic_speed,
    trim_and_normalize_speech,
};
use crate::dialogue::{is_dialogue, join_short_sentences, split_dialogue};
use crate::dictionary_en::lookup_hint;
use crate::korean::{normalize_korean, PronunciationDictionary};
use crate::options::{apply_reading_options, ReadingOptions};
use crate::script::is_latin_only;
use crate::voice_styles::{append_voice, read_voice_bin_header};
use crate::wordwise::{convert_epub_wordwise, transform_xhtml_wordwise};

pub type JNIEnv = *const *const *const c_void;
pub type jobject = *mut c_void;
pub type jclass = jobject;
pub type jstring = jobject;
pub type jarray = jobject;
pub type jobjectArray = jobject;
pub type jbyteArray = jobject;
pub type jintArray = jobject;
pub type jfloatArray = jobject;
pub type jboolean = u8;
pub type jbyte = i8;
pub type jchar = u16;
pub type jint = i32;
pub type jsize = i32;
pub type jfloat = f32;

const JNI_ABORT: jint = 2;

unsafe fn vtable_fn<T>(env: JNIEnv, idx: usize) -> T {
    let table = *env;
    std::mem::transmute_copy(&*table.add(idx))
}

unsafe fn get_jstring(env: JNIEnv, s: jstring) -> String {
    if s.is_null() {
        return String::new();
    }
    let get_len: unsafe extern "system" fn(JNIEnv, jstring) -> jsize = vtable_fn(env, 164);
    let get_chars: unsafe extern "system" fn(JNIEnv, jstring, *mut jboolean) -> *const jchar =
        vtable_fn(env, 165);
    let rel_chars: unsafe extern "system" fn(JNIEnv, jstring, *const jchar) = vtable_fn(env, 166);

    let len = get_len(env, s).max(0) as usize;
    if len == 0 {
        return String::new();
    }
    let ptr = get_chars(env, s, std::ptr::null_mut());
    if ptr.is_null() {
        return String::new();
    }
    let utf16 = slice::from_raw_parts(ptr, len);
    let res = String::from_utf16_lossy(utf16);
    rel_chars(env, s, ptr);
    res
}

unsafe fn new_jstring(env: JNIEnv, s: &str) -> jstring {
    let new_str: unsafe extern "system" fn(JNIEnv, *const jchar, jsize) -> jstring =
        vtable_fn(env, 163);
    let utf16: Vec<jchar> = s.encode_utf16().collect();
    new_str(env, utf16.as_ptr(), utf16.len() as jsize)
}

unsafe fn get_jstring_array(env: JNIEnv, arr: jobjectArray) -> Vec<String> {
    if arr.is_null() {
        return Vec::new();
    }
    let get_len: unsafe extern "system" fn(JNIEnv, jarray) -> jsize = vtable_fn(env, 171);
    let get_elem: unsafe extern "system" fn(JNIEnv, jobjectArray, jsize) -> jobject =
        vtable_fn(env, 173);
    let del_local: unsafe extern "system" fn(JNIEnv, jobject) = vtable_fn(env, 23);

    let len = get_len(env, arr).max(0);
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let elem = get_elem(env, arr, i);
        out.push(get_jstring(env, elem));
        if !elem.is_null() {
            del_local(env, elem);
        }
    }
    out
}

unsafe fn get_jbyte_vec(env: JNIEnv, arr: jbyteArray) -> Vec<u8> {
    if arr.is_null() {
        return Vec::new();
    }
    let get_len: unsafe extern "system" fn(JNIEnv, jarray) -> jsize = vtable_fn(env, 171);
    let get_elems: unsafe extern "system" fn(JNIEnv, jbyteArray, *mut jboolean) -> *mut jbyte =
        vtable_fn(env, 184);
    let rel_elems: unsafe extern "system" fn(JNIEnv, jbyteArray, *mut jbyte, jint) =
        vtable_fn(env, 192);
    let len = get_len(env, arr).max(0) as usize;
    if len == 0 {
        return Vec::new();
    }
    let ptr = get_elems(env, arr, std::ptr::null_mut());
    let vec = slice::from_raw_parts(ptr as *const u8, len).to_vec();
    rel_elems(env, arr, ptr, JNI_ABORT);
    vec
}

unsafe fn new_jbyte_array(env: JNIEnv, data: &[u8]) -> jbyteArray {
    let new_arr: unsafe extern "system" fn(JNIEnv, jsize) -> jbyteArray = vtable_fn(env, 176);
    let set_reg: unsafe extern "system" fn(JNIEnv, jbyteArray, jsize, jsize, *const jbyte) =
        vtable_fn(env, 208);
    let arr = new_arr(env, data.len() as jsize);
    if !arr.is_null() && !data.is_empty() {
        set_reg(env, arr, 0, data.len() as jsize, data.as_ptr() as *const jbyte);
    }
    arr
}

unsafe fn get_jint_vec(env: JNIEnv, arr: jintArray) -> Vec<i32> {
    if arr.is_null() {
        return Vec::new();
    }
    let get_len: unsafe extern "system" fn(JNIEnv, jarray) -> jsize = vtable_fn(env, 171);
    let get_elems: unsafe extern "system" fn(JNIEnv, jintArray, *mut jboolean) -> *mut jint =
        vtable_fn(env, 187);
    let rel_elems: unsafe extern "system" fn(JNIEnv, jintArray, *mut jint, jint) =
        vtable_fn(env, 195);
    let len = get_len(env, arr).max(0) as usize;
    if len == 0 {
        return Vec::new();
    }
    let ptr = get_elems(env, arr, std::ptr::null_mut());
    let vec = slice::from_raw_parts(ptr, len).to_vec();
    rel_elems(env, arr, ptr, JNI_ABORT);
    vec
}

unsafe fn new_jint_array(env: JNIEnv, data: &[i32]) -> jintArray {
    let new_arr: unsafe extern "system" fn(JNIEnv, jsize) -> jintArray = vtable_fn(env, 179);
    let set_reg: unsafe extern "system" fn(JNIEnv, jintArray, jsize, jsize, *const jint) =
        vtable_fn(env, 211);
    let arr = new_arr(env, data.len() as jsize);
    if !arr.is_null() && !data.is_empty() {
        set_reg(env, arr, 0, data.len() as jsize, data.as_ptr());
    }
    arr
}

unsafe fn get_jfloat_vec(env: JNIEnv, arr: jfloatArray) -> Vec<f32> {
    if arr.is_null() {
        return Vec::new();
    }
    let get_len: unsafe extern "system" fn(JNIEnv, jarray) -> jsize = vtable_fn(env, 171);
    let get_elems: unsafe extern "system" fn(JNIEnv, jfloatArray, *mut jboolean) -> *mut jfloat =
        vtable_fn(env, 189);
    let rel_elems: unsafe extern "system" fn(JNIEnv, jfloatArray, *mut jfloat, jint) =
        vtable_fn(env, 197);
    let len = get_len(env, arr).max(0) as usize;
    if len == 0 {
        return Vec::new();
    }
    let ptr = get_elems(env, arr, std::ptr::null_mut());
    let vec = slice::from_raw_parts(ptr, len).to_vec();
    rel_elems(env, arr, ptr, JNI_ABORT);
    vec
}

unsafe fn new_jfloat_array(env: JNIEnv, data: &[f32]) -> jfloatArray {
    let new_arr: unsafe extern "system" fn(JNIEnv, jsize) -> jfloatArray = vtable_fn(env, 181);
    let set_reg: unsafe extern "system" fn(JNIEnv, jfloatArray, jsize, jsize, *const jfloat) =
        vtable_fn(env, 213);
    let arr = new_arr(env, data.len() as jsize);
    if !arr.is_null() && !data.is_empty() {
        set_reg(env, arr, 0, data.len() as jsize, data.as_ptr());
    }
    arr
}

// ============================================================================
// JNI Exported Functions for `app.voicereader.VoiceReaderNative`
// ============================================================================

#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativeNormalizeKorean(
    env: JNIEnv,
    _cls: jclass,
    text: jstring,
    parentheses: jboolean,
    footnoteMarks: jboolean,
    spellAcronyms: jboolean,
    dictKeys: jobjectArray,
    dictVals: jobjectArray,
) -> jstring {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let input = get_jstring(env, text);
        let keys = get_jstring_array(env, dictKeys);
        let vals = get_jstring_array(env, dictVals);
        let entries: Vec<(String, String)> = keys.into_iter().zip(vals).collect();
        let fixed = if entries.is_empty() {
            input
        } else {
            PronunciationDictionary::new(entries).apply(&input)
        };
        let opts = ReadingOptions {
            parentheses: parentheses != 0,
            footnote_marks: footnoteMarks != 0,
            spell_acronyms: spellAcronyms != 0,
        };
        normalize_korean(&fixed, opts)
    }));
    match res {
        Ok(s) => new_jstring(env, &s),
        Err(_) => text,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativeApplyReadingOptions(
    env: JNIEnv,
    _cls: jclass,
    text: jstring,
    parentheses: jboolean,
    footnoteMarks: jboolean,
    spellAcronyms: jboolean,
) -> jstring {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let input = get_jstring(env, text);
        let opts = ReadingOptions {
            parentheses: parentheses != 0,
            footnote_marks: footnoteMarks != 0,
            spell_acronyms: spellAcronyms != 0,
        };
        apply_reading_options(&input, opts)
    }));
    match res {
        Ok(s) => new_jstring(env, &s),
        Err(_) => text,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativeIsLatinOnly(
    env: JNIEnv,
    _cls: jclass,
    text: jstring,
) -> jboolean {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let input = get_jstring(env, text);
        if is_latin_only(&input) { 1 } else { 0 }
    }));
    res.unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativeIsDialogue(
    env: JNIEnv,
    _cls: jclass,
    text: jstring,
) -> jboolean {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let input = get_jstring(env, text);
        if is_dialogue(&input) { 1 } else { 0 }
    }));
    res.unwrap_or(0)
}

/// Splits dialogue and merges only ultra-short fragments (`< 16` chars, `<= 75` chars).
/// `sentenceBoundsUtf16` is flat `[start0, end0, start1, end1, ...]` from ICU tokenizer on `text`.
/// Returns flat UTF-16 code unit `[start0, end0, ...]` ranges.
#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativeChunkParagraph(
    env: JNIEnv,
    _cls: jclass,
    text: jstring,
    sentenceBoundsUtf16: jintArray,
    splitQuotes: jboolean,
    minChars: jint,
    maxChars: jint,
) -> jintArray {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let s = get_jstring(env, text);
        let bounds = get_jint_vec(env, sentenceBoundsUtf16);
        // Map UTF-16 code unit offsets to UTF-8 byte offsets
        let utf16_to_byte = build_utf16_to_byte(&s);
        let byte_to_utf16 = build_byte_to_utf16(&s);

        let mut base_ranges = Vec::with_capacity(bounds.len() / 2);
        for pair in bounds.chunks_exact(2) {
            let u_start = (pair[0].max(0) as usize).min(utf16_to_byte.len() - 1);
            let u_end = (pair[1].max(0) as usize).min(utf16_to_byte.len() - 1);
            let b_start = utf16_to_byte[u_start];
            let b_end = utf16_to_byte[u_end];
            if b_start < b_end {
                base_ranges.push(b_start..b_end);
            }
        }

        let after_dialogue = if splitQuotes != 0 {
            split_dialogue(&s, |sub| {
                let sub_start = sub.as_ptr() as usize - s.as_ptr() as usize;
                let sub_end = sub_start + sub.len();
                let mut local = Vec::new();
                for r in &base_ranges {
                    let rs = r.start.max(sub_start);
                    let re = r.end.min(sub_end);
                    if rs < re {
                        local.push((rs - sub_start)..(re - sub_start));
                    }
                }
                if local.is_empty() && !sub.trim().is_empty() {
                    local.push(0..sub.len());
                }
                local
            })
        } else {
            base_ranges
        };

        let min_c = minChars.max(0) as usize;
        let max_c = maxChars.max(minChars).max(1) as usize;
        let chunked = join_short_sentences(&s, &after_dialogue, min_c, max_c, |a, b| {
            let x = &s[a.clone()];
            let y = &s[b.clone()];
            (splitQuotes != 0 && is_dialogue(x) != is_dialogue(y))
                || is_latin_only(x) != is_latin_only(y)
        });

        let mut out = Vec::with_capacity(chunked.len() * 2);
        for r in chunked {
            out.push(byte_to_utf16[r.start] as i32);
            out.push(byte_to_utf16[r.end] as i32);
        }
        out
    }));
    match res {
        Ok(v) => new_jint_array(env, &v),
        Err(_) => new_jint_array(env, &[]),
    }
}

fn build_utf16_to_byte(s: &str) -> Vec<usize> {
    let mut map = Vec::with_capacity(s.len() + 1);
    for (b_idx, ch) in s.char_indices() {
        for _ in 0..ch.len_utf16() {
            map.push(b_idx);
        }
    }
    map.push(s.len());
    map
}

fn build_byte_to_utf16(s: &str) -> Vec<usize> {
    let mut map = vec![0usize; s.len() + 1];
    let mut u_idx = 0usize;
    for (b_idx, ch) in s.char_indices() {
        for k in 0..ch.len_utf8() {
            map[b_idx + k] = u_idx;
        }
        u_idx += ch.len_utf16();
    }
    map[s.len()] = u_idx;
    map
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativeTrimAndNormalizeSpeech(
    env: JNIEnv,
    _cls: jclass,
    samples: jfloatArray,
    sampleRate: jint,
    text: jstring,
) -> jfloatArray {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let pcm = get_jfloat_vec(env, samples);
        let t = get_jstring(env, text);
        trim_and_normalize_speech(&pcm, sampleRate.max(0) as u32, &t)
    }));
    match res {
        Ok(out) => new_jfloat_array(env, &out),
        Err(_) => samples,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativeAppendPauseSilence(
    env: JNIEnv,
    _cls: jclass,
    samples: jfloatArray,
    sampleRate: jint,
    pauseMs: jint,
    text: jstring,
) -> jfloatArray {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let pcm = get_jfloat_vec(env, samples);
        let t = get_jstring(env, text);
        append_pause_silence(&pcm, sampleRate.max(0) as u32, pauseMs.max(0) as u32, &t)
    }));
    match res {
        Ok(out) => new_jfloat_array(env, &out),
        Err(_) => samples,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativePunctuationPauseBonusMs(
    env: JNIEnv,
    _cls: jclass,
    text: jstring,
) -> jint {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let t = get_jstring(env, text);
        punctuation_pause_bonus_ms(&t)
    }));
    res.unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativeRecommendedSpeed(
    env: JNIEnv,
    _cls: jclass,
    baseSpeed: jfloat,
    text: jstring,
) -> jfloat {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let t = get_jstring(env, text);
        recommended_supertonic_speed(baseSpeed, &t)
    }));
    res.unwrap_or(baseSpeed)
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativeReadVoiceBinCount(
    env: JNIEnv,
    _cls: jclass,
    bytes: jbyteArray,
) -> jint {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let raw = get_jbyte_vec(env, bytes);
        read_voice_bin_header(&raw).map(|h| h.count as jint).unwrap_or(-1)
    }));
    res.unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativeAppendVoiceBin(
    env: JNIEnv,
    _cls: jclass,
    binBytes: jbyteArray,
    ttlFloats: jfloatArray,
    dpFloats: jfloatArray,
) -> jbyteArray {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let bin = get_jbyte_vec(env, binBytes);
        let ttl = get_jfloat_vec(env, ttlFloats);
        let dp = get_jfloat_vec(env, dpFloats);
        append_voice(&bin, &ttl, &dp).ok()
    }));
    match res {
        Ok(Some(out)) => new_jbyte_array(env, &out),
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativeConvertEpubWordWise(
    env: JNIEnv,
    _cls: jclass,
    inputPath: jstring,
    outputPath: jstring,
    level: jint,
) -> jint {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let in_p = get_jstring(env, inputPath);
        let out_p = get_jstring(env, outputPath);
        convert_epub_wordwise(&in_p, &out_p, level.clamp(0, 3) as u8)
            .map(|n| n as jint)
            .unwrap_or(-1)
    }));
    res.unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativeTransformHtmlWordWise(
    env: JNIEnv,
    _cls: jclass,
    html: jstring,
    level: jint,
) -> jstring {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let input = get_jstring(env, html);
        transform_xhtml_wordwise(&input, level.clamp(0, 3) as u8).0
    }));
    match res {
        Ok(s) => new_jstring(env, &s),
        Err(_) => html,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_app_voicereader_VoiceReaderNative_nativeLookupWordHint(
    env: JNIEnv,
    _cls: jclass,
    word: jstring,
    level: jint,
) -> jstring {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let w = get_jstring(env, word);
        lookup_hint(&w, level.clamp(0, 3) as u8).map(|(_, h)| h)
    }));
    match res {
        Ok(Some(hint)) => new_jstring(env, hint),
        _ => std::ptr::null_mut(),
    }
}
