"""책 한 권을 내 목소리로: 앱이 내보낸 문장 파일을 VoxCPM2로 합성해, 앱에 넣을 음성 묶음(zip)을 만든다.

docs/my-voice-book.md의 절차대로 쓴다. 필요한 것:
  - llama.cpp-omni로 빌드한 voxcpm2-cli와 VoxCPM2 GGUF 두 개(BaseLM, Acoustic)
  - 내 녹음 하나(서술 톤, 10초 이상). 앞에서 --ref-seconds초만 쓴다(짧을수록 빠르고, 13초가 들어 보기에 자연스러웠다)
  - 소리 변환: macOS의 afconvert, 없으면 ffmpeg
  - pip install numpy soundfile

    python3 spike/voxcpm_book.py "책 문장.json" --ref 내녹음.wav --cli .../voxcpm2-cli --models .../voxcpm2-gguf

문장마다 <key>.m4a가 "<출력 이름>/" 폴더에 쌓이고, 끝나면(또는 Ctrl+C로 멈추면) 그때까지 만든 것을 zip으로 묶는다.
다시 실행하면 이미 만든 문장은 건너뛰고 이어서 만든다.
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

import numpy as np
import soundfile as sf

KEY = re.compile(r"[0-9a-f]{40}")
SILENCE = 0.01  # 앱의 Supertonic과 같은 기준(≈ -40 dBFS)
MARGIN = 0.05  # 말소리 앞뒤로 남기는 여백(초)


def convert(src, dst, *, rate, aac=False):
    """afconvert(맥) 또는 ffmpeg로 모노 rate Hz로 바꾼다. aac면 .m4a(AAC 40kbps), 아니면 16비트 wav."""
    if shutil.which("afconvert"):
        fmt = ["-f", "m4af", "-d", f"aac@{rate}", "-b", "40000"] if aac else ["-f", "WAVE", "-d", f"LEI16@{rate}"]
        cmd = ["afconvert", *fmt, "-c", "1", str(src), str(dst)]
    elif shutil.which("ffmpeg"):
        codec = ["-c:a", "aac", "-b:a", "40k"] if aac else ["-c:a", "pcm_s16le"]
        cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", str(src), "-ar", str(rate), "-ac", "1", *codec, str(dst)]
    else:
        sys.exit("afconvert(맥)나 ffmpeg가 필요합니다")
    subprocess.run(cmd, check=True)


def prepare_reference(src, seconds, work):
    """녹음을 16kHz 모노로 바꾸고, seconds 근처의 가장 조용한 곳에서 자른다. voxcpm2-cli는 기본 PCM wav만 읽는다."""
    full = work / "_ref_full.wav"
    convert(src, full, rate=16000)
    w, sr = sf.read(full, dtype="float32")
    if len(w) > seconds * sr:
        a, b = int(max(seconds - 2, 0) * sr), min(int((seconds + 2) * sr), len(w))
        energy = np.convolve(w[a:b] ** 2, np.ones(sr // 10) / (sr // 10), "same")  # 0.1초 에너지
        w = w[: a + int(np.argmin(energy))]
    ref = work / "_ref.wav"
    sf.write(ref, w, sr, subtype="PCM_16")  # 기본 PCM 헤더(afconvert는 확장 헤더로 써서 cli가 못 읽는다)
    return ref, len(w) / sr


def synthesize(cli, models, ref, text, out, seed):
    steps = max(200, 2 * len(text))  # 한 단계가 약 0.16초, 기본 200단계(32초)면 긴 문장이 잘린다
    cmd = [str(cli), "-t", text, "-r", str(ref), "-o", str(out), "--steps", str(steps), "--seed", str(seed), *map(str, models)]
    done = subprocess.run(cmd, capture_output=True, text=True)
    if done.returncode != 0 or not out.exists():
        raise RuntimeError(done.stderr.strip().splitlines()[-1:] or "voxcpm2-cli 실패")
    w, sr = sf.read(out, dtype="float32")
    return w, sr


def trimmed(w, sr):
    loud = np.flatnonzero(np.abs(w) > SILENCE)
    if len(loud) == 0:
        return w
    m = int(MARGIN * sr)
    return w[max(loud[0] - m, 0): loud[-1] + m + 1]


def hms(seconds):
    h, m = divmod(int(seconds) // 60, 60)
    return f"{h}시간 {m}분" if h else f"{m}분"


def pack(sentences, title, work, out):
    keys = {s["key"] for s in sentences}  # 다시 내보내 글이 바뀐 문장의 옛 음성은 뺀다
    done = sorted(p for p in work.glob("*.m4a") if p.stem in keys)
    meta = {"format": 1, "title": title, "voice": "VoxCPM2", "total": len(sentences), "done": len(done)}
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:  # m4a는 이미 압축돼 있다
        z.writestr("pack.json", json.dumps(meta, ensure_ascii=False))
        for p in done:
            z.write(p, p.name)
    print(f"\n{out}: 문장 {len(sentences)}개 중 {len(done)}개를 묶었습니다. 앱의 설정 → 낭독 → 내 목소리 음성 → 음성 넣기로 넣으세요.")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("sentences", type=Path, help="앱에서 내보낸 '<책 제목> 문장.json'")
    p.add_argument("--ref", type=Path, required=True, help="내 녹음 (wav, m4a 등)")
    p.add_argument("--ref-seconds", type=float, default=13, help="녹음 앞에서 쓸 길이(초)")
    p.add_argument("--cli", type=Path, required=True, help="llama.cpp-omni의 build/bin/voxcpm2-cli")
    p.add_argument("--models", type=Path, required=True, help="VoxCPM2 GGUF 두 개(BaseLM, Acoustic)가 있는 폴더")
    p.add_argument("--out", type=Path, help="만들 zip (기본: 문장 파일 옆 '<책 제목> 내 목소리.zip')")
    a = p.parse_args()

    book = json.loads(a.sentences.read_text(encoding="utf-8"))
    sentences = [s for s in book["sentences"] if KEY.fullmatch(s["key"])]
    title = book.get("title") or a.sentences.stem
    out = a.out or a.sentences.with_name(f"{title} 내 목소리.zip")
    work = out.with_suffix("")  # 만든 문장이 쌓이는 폴더. 다시 실행하면 여기서 이어 간다
    work.mkdir(parents=True, exist_ok=True)
    models = [next(a.models.glob(f"*{part}*.gguf"), None) for part in ("BaseLM", "Acoustic")]
    if None in models:
        sys.exit(f"{a.models}에 VoxCPM2 BaseLM, Acoustic GGUF가 없습니다")
    ref, ref_seconds = prepare_reference(a.ref, a.ref_seconds, work)

    todo = [s for s in sentences if not (work / f"{s['key']}.m4a").exists()]
    print(f"{title}: 문장 {len(sentences)}개 중 {len(sentences) - len(todo)}개는 이미 있음, {len(todo)}개 만들기 (녹음 {ref_seconds:.1f}초 사용)")
    started, chars_done, left = time.time(), 0, sum(len(s["text"]) for s in todo)
    try:
        with tempfile.TemporaryDirectory() as tmp:
            raw, part = Path(tmp) / "raw.wav", Path(tmp) / "part.m4a"
            for i, s in enumerate(todo, 1):
                text = s["text"]
                w, sr = synthesize(a.cli, models, ref, text, raw, seed=42)
                if len(w) / sr > 0.5 * len(text) + 2:  # 보통 글자당 0.15초. 서너 배를 넘으면 엉뚱하게 이어 말한 것
                    w, sr = synthesize(a.cli, models, ref, text, raw, seed=43)
                sf.write(raw, trimmed(w, sr), sr, subtype="PCM_16")
                convert(raw, part, rate=24000, aac=True)
                part.replace(work / f"{s['key']}.m4a")  # 다 만든 파일만 이름을 얻는다
                chars_done += len(text)
                left -= len(text)
                eta = (time.time() - started) / chars_done * left
                print(f"[{i}/{len(todo)}] {len(w) / sr:.1f}초 · 남은 시간 약 {hms(eta)} · {text[:30]}", flush=True)
    except KeyboardInterrupt:
        print("\n멈췄습니다. 다시 실행하면 이어서 만듭니다.")
    pack(sentences, title, work, out)


if __name__ == "__main__":
    main()
