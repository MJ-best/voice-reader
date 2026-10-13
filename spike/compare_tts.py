"""0단계 게이트: 온디바이스 한국어 TTS 후보 비교.

후보마다 테스트 문장 30개를 합성해 spike/out/<후보>/NN_raw.wav 로 저장하고 RTF를 잰다.
숫자·영어 문장은 정규화된 읽기형(NN_norm.wav)도 함께 합성한다. 문장마다 REPEATS번 합성하고 첫 번째만 저장.
사람 귀 대신 한국어 Zipformer ASR로 다시 받아써 CER을 낸다 (객관 지표 보조용).
결과: spike/out/summary.json, spike/out/<후보>/detail.json

모델은 ~/.cache/epub-tts-spike/models 에 받는다 (저장소 밖). 재현:
    /opt/homebrew/bin/python3.12 -m venv ~/.cache/epub-tts-spike/venv
    ~/.cache/epub-tts-spike/venv/bin/pip install sherpa-onnx==1.13.8 onnxruntime numpy soundfile uroman
    ~/.cache/epub-tts-spike/venv/bin/python -I spike/compare_tts.py
"""

import json
import re
import tarfile
import time
import urllib.request
from pathlib import Path

import numpy as np
import onnxruntime as ort
import sherpa_onnx
import soundfile as sf

CACHE = Path.home() / ".cache/epub-tts-spike/models"
OUT = Path(__file__).resolve().parent / "out"
THREADS = 2  # 폰 빅코어 2개 정도를 흉내
REPEATS = 3
GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
HF = "https://huggingface.co/Xenova/mms-tts-kor/resolve/main"

# (원문, 기대 읽기형). 읽기형은 norm 합성 입력. ASR이 숫자를 아라비아 숫자로도 받아쓰므로
# CER은 원문·읽기형 중 낮은 쪽을 쓴다.
SENTENCES = [
    # 숫자·날짜·단위
    ("2026년 10월 13일 화요일입니다.", "이천이십육 년 시월 십삼 일 화요일입니다."),
    ("오후 3시 45분에 회의가 시작됩니다.", "오후 세 시 사십오 분에 회의가 시작됩니다."),
    ("가격은 12,800원이고 할인율은 15%입니다.", "가격은 만 이천팔백 원이고 할인율은 십오 퍼센트입니다."),
    ("서울에서 부산까지 약 325km, 차로 4시간 반 걸립니다.", "서울에서 부산까지 약 삼백이십오 킬로미터, 차로 네 시간 반 걸립니다."),
    ("물 500ml와 밀가루 2.5kg을 준비하세요.", "물 오백 밀리리터와 밀가루 이 점 오 킬로그램을 준비하세요."),
    ("제3장은 1999년 6월 25일에 끝난다.", "제삼 장은 천구백구십구 년 유월 이십오 일에 끝난다."),
    ("사과 3개와 배 2개, 모두 5개를 샀다.", "사과 세 개와 배 두 개, 모두 다섯 개를 샀다."),
    ("전화번호는 010-1234-5678입니다.", "전화번호는 공일공 일이삼사 오육칠팔입니다."),
    ("기온이 영하 7.5℃까지 떨어졌다.", "기온이 영하 칠 점 오 도까지 떨어졌다."),
    # 영어 섞인 문장
    ("오늘은 iPhone으로 YouTube 영상을 봤다.", "오늘은 아이폰으로 유튜브 영상을 봤다."),
    ("이 기능은 AI와 GPU 성능에 달려 있다.", "이 기능은 에이아이와 지피유 성능에 달려 있다."),
    ('그녀는 "Thank you"라고 짧게 대답했다.', "그녀는 땡큐라고 짧게 대답했다."),
    ("회의 자료는 PDF 파일로 email에 첨부했습니다.", "회의 자료는 피디에프 파일로 이메일에 첨부했습니다."),
    ("Harry Potter 시리즈는 전 세계에서 사랑받았다.", "해리 포터 시리즈는 전 세계에서 사랑받았다."),
    ("우리 팀은 Android와 Kotlin으로 앱을 만든다.", "우리 팀은 안드로이드와 코틀린으로 앱을 만든다."),
    # 고유명사
    ("세종대왕은 집현전 학자들과 함께 훈민정음을 만들었다.", None),
    ("김유신과 연개소문은 삼국 시대의 장수였다.", None),
    ("우리는 제주도 한라산과 강릉 경포대를 여행했다.", None),
    ("도스토옙스키의 『죄와 벌』을 읽고 라스콜니코프에 대해 토론했다.", None),
    # 따옴표 대화문
    ('"어디 가니?" 엄마가 물었다.', None),
    ('"몰라요." 소년은 고개를 저으며 대답했다.', None),
    ("그는 '설마 그럴 리가' 하고 중얼거렸다.", None),
    ('"정말이야? 거짓말하지 마!" 친구가 소리쳤다.', None),
    ("“내일 아침 일곱 시에 만나자.” 그녀가 조용히 말했다.", None),
    # 음운 변동 (같이→가치, 국물→궁물, 신라→실라, 닭값→닥깝)
    ("같이 맛있는 국물을 먹으며 신라의 역사를 이야기했다.", None),
    ("닭값이 올라서 읽을거리를 사지 못했다.", None),
    # 긴 문장
    ("창밖으로 비가 내리기 시작하자 그는 읽던 책을 덮고, 오래전 할머니가 들려주던 옛이야기를 하나씩 떠올리며 따뜻한 차를 천천히 마셨다.", None),
    ("도서관 맨 안쪽 서가에는 아무도 빌려 가지 않은 낡은 책들이 먼지를 뒤집어쓴 채 꽂혀 있었는데, 그중 한 권의 표지에는 누군가 연필로 작게 적어 놓은 이름이 희미하게 남아 있었다.", None),
    ("그날 밤 마을 사람들은 광장에 모여 등불을 하나둘 밝혔고, 아이들은 그 사이를 뛰어다니며 웃었으며, 노인들은 오래된 노래를 낮은 목소리로 따라 부르면서 지난 한 해 동안 있었던 일들을 조용히 되돌아보았다.", None),
    ("기차가 터널을 빠져나오자 눈부신 햇살이 객실 안으로 쏟아져 들어왔고, 창가에 앉아 있던 여자는 무릎 위에 펼쳐 두었던 편지를 다시 접어 가방 깊숙이 넣은 뒤, 멀리 보이는 바다를 한참 동안 말없이 바라보았는데, 그 얼굴에는 슬픔인지 안도인지 알 수 없는 표정이 떠올라 있었다.", None),
]
assert len(SENTENCES) == 30


def fetch_tar(release, name):
    """sherpa-onnx 릴리스 tar.bz2를 CACHE/name/ 에 풀고 그 안의 모델 폴더를 돌려준다."""
    d = CACHE / name
    if not (d / name).is_dir():
        d.mkdir(parents=True, exist_ok=True)
        tmp = d / "dl.tar.bz2"
        print(f"downloading {name} ...")
        urllib.request.urlretrieve(f"{GH}/{release}/{name}.tar.bz2", tmp)
        with tarfile.open(tmp) as t:
            t.extractall(d, filter="data")
        tmp.unlink()
    return d / name


def fetch_mms():
    d = CACHE / "mms-tts-kor"
    d.mkdir(parents=True, exist_ok=True)
    for f in ("onnx/model.onnx", "vocab.json", "config.json"):
        if not (d / Path(f).name).exists():
            print(f"downloading mms-tts-kor/{f} ...")
            urllib.request.urlretrieve(f"{HF}/{f}", d / Path(f).name)
    return d


def sherpa_supertonic(name, sid=0):  # sid 0..4 = F1..F5, 5..9 = M1..M5
    m = fetch_tar("tts-models", name)
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
                duration_predictor=str(m / "duration_predictor.int8.onnx"),
                text_encoder=str(m / "text_encoder.int8.onnx"),
                vector_estimator=str(m / "vector_estimator.int8.onnx"),
                vocoder=str(m / "vocoder.int8.onnx"),
                tts_json=str(m / "tts.json"),
                unicode_indexer=str(m / "unicode_indexer.bin"),
                voice_style=str(m / "voice.bin"),
            ),
            num_threads=THREADS,
        )))
    cfg = sherpa_onnx.GenerationConfig()
    cfg.sid = sid
    cfg.num_steps = 8
    cfg.extra["lang"] = "ko"

    def synth(text):
        a = tts.generate(text, cfg)
        return np.asarray(a.samples, dtype=np.float32), a.sample_rate
    return synth, m


def sherpa_mimic3():
    m = fetch_tar("tts-models", "vits-mimic3-ko_KO-kss_low")
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=str(m / "ko_KO-kss_low.onnx"),
                tokens=str(m / "tokens.txt"),
                data_dir=str(m / "espeak-ng-data"),
            ),
            num_threads=THREADS,
        )))

    def synth(text):
        a = tts.generate(text, sid=0, speed=1.0)
        return np.asarray(a.samples, dtype=np.float32), a.sample_rate
    return synth, m


def ort_mms():
    # sherpa-onnx엔 kor MMS 사전 변환본이 없고 재변환엔 torch가 필요해서,
    # 같은 가중치의 transformers.js ONNX 내보내기를 onnxruntime으로 직접 돌린다.
    import uroman

    m = fetch_mms()
    vocab = json.loads((m / "vocab.json").read_text())
    sr = json.loads((m / "config.json").read_text())["sampling_rate"]
    so = ort.SessionOptions()
    so.intra_op_num_threads = THREADS
    sess = ort.InferenceSession(str(m / "model.onnx"), so)
    rom = uroman.Uroman()

    def synth(text):
        # VitsTokenizer(is_uroman, normalize, add_blank) 와 같은 처리: 로마자화 → 소문자 → vocab 밖 문자 버림 → 0 끼워넣기
        ids = [vocab[c] for c in rom.romanize_string(text).lower() if c in vocab]
        x = np.zeros(2 * len(ids) + 1, dtype=np.int64)
        x[1::2] = ids
        x = x[None]
        wav = sess.run(["waveform"], {"input_ids": x, "attention_mask": np.ones_like(x)})[0][0]
        return wav.astype(np.float32), sr
    return synth, m


CANDIDATES = {
    "supertonic3_int8": lambda: sherpa_supertonic("sherpa-onnx-supertonic-3-tts-int8-2026-05-11"),
    "supertonic2_int8": lambda: sherpa_supertonic("sherpa-onnx-supertonic-tts-int8-2026-03-06"),
    "mimic3_kss": sherpa_mimic3,
    "mms_kor": ort_mms,
}


def make_asr():
    # SenseVoice int8(2025-09-09)는 자기 ko.wav 샘플도 못 받아써서 한국어 전용 Zipformer(KsponSpeech)를 쓴다.
    m = fetch_tar("asr-models", "sherpa-onnx-zipformer-korean-2024-06-24")
    rec = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=str(m / "encoder-epoch-99-avg-1.int8.onnx"),
        decoder=str(m / "decoder-epoch-99-avg-1.int8.onnx"),
        joiner=str(m / "joiner-epoch-99-avg-1.int8.onnx"),
        tokens=str(m / "tokens.txt"), num_threads=4)

    def asr(wav, sr):
        s = rec.create_stream()
        s.accept_waveform(sr, wav)
        rec.decode_stream(s)
        return s.result.text
    return asr


def cer(ref, hyp):
    """공백·문장부호를 뺀 글자 단위 편집 거리 / 기준 길이."""
    keep = lambda s: re.sub(r"[^가-힣a-z0-9]", "", s.lower())
    r, h = keep(ref), keep(hyp)
    prev = list(range(len(h) + 1))
    for i, rc in enumerate(r, 1):
        cur = [i]
        for j, hc in enumerate(h, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rc != hc)))
        prev = cur
    return prev[-1] / max(len(r), 1)


def dir_mb(p):
    return sum(f.stat().st_size for f in p.rglob("*") if f.is_file()) / 1e6


def main():
    asr = make_asr()
    summary = {}
    for name, load in CANDIDATES.items():
        t0 = time.perf_counter()
        synth, model_dir = load()
        load_s = time.perf_counter() - t0
        synth("안녕하세요.")  # 워밍업, 측정 제외
        out = OUT / name
        out.mkdir(parents=True, exist_ok=True)
        rows, tot_t, tot_a, rtfs = [], 0.0, 0.0, []
        for i, (raw, spoken) in enumerate(SENTENCES, 1):
            row = {"id": i, "text": raw}
            for kind, text in (("raw", raw), ("norm", spoken)):
                if text is None:
                    continue
                runs = []  # 합성은 확률적(노이즈 샘플링)이라 같은 문장을 REPEATS번 돌린다
                for r in range(REPEATS):
                    t0 = time.perf_counter()
                    wav, sr = synth(text)
                    dt = time.perf_counter() - t0
                    dur = len(wav) / sr
                    tot_t, tot_a = tot_t + dt, tot_a + dur
                    rtfs.append(dt / dur)
                    if r == 0:
                        sf.write(out / f"{i:02d}_{kind}.wav", wav, sr, subtype="PCM_16")
                    hyp = asr(wav, sr)
                    runs.append((hyp, min(cer(raw, hyp), cer(spoken or raw, hyp))))
                row[kind] = {"sec": round(dur, 2), "asr": [h for h, _ in runs],
                             "cer": float(np.mean([c for _, c in runs])), "cer_runs": [round(c, 3) for _, c in runs]}
            rows.append(row)
        best = [r.get("norm", r["raw"]) for r in rows]  # 정규화기가 있다고 가정한 입력
        summary[name] = {
            "size_mb": round(dir_mb(model_dir), 1), "load_s": round(load_s, 2),
            "sample_rate": sr, "rtf": round(tot_t / tot_a, 4), "rtf_max": round(max(rtfs), 4),
            "cer_raw": round(float(np.mean([r["raw"]["cer"] for r in rows])), 3),
            "cer_norm": round(float(np.mean([b["cer"] for b in best])), 3),
            # 정규화 입력에서 CER>0.3 인 합성 비율 = 건너뛰기·뭉개짐 같은 큰 실패
            "fail_rate_norm": round(float(np.mean([c > 0.3 for b in best for c in b["cer_runs"]])), 3),
        }
        (out / "detail.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1))
        print(name, summary[name], flush=True)
    (OUT / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    assert abs(cer("가 나다!", "가나라") - 1 / 3) < 1e-9  # 공백·부호 무시, 1글자 치환
    main()
