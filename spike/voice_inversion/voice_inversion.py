"""Supertonic 목소리 스타일을 Voice Builder 없이 만드는 실험 (스타일 역추정).

모델(공식 ONNX → onnx2torch)은 고정하고 style_ttl [1,50,256]만 학습 변수로 둔다. 합성한 소리와 대상 녹음의
화자 임베딩(ECAPA-TDNN) 코사인 유사도를 올리는 방향으로 경사 하강한다. style_dp [1,8,16]은 길이(말 속도)만
정하고 경사가 흐르지 않으므로 시작점(프리셋/혼합)의 값을 그대로 쓴다.
기본값(README의 c'): 프리셋 혼합에서 시작해 200회 돌리고, 25회마다 검증 문장으로 잰 WavLM-SV 유사도가 가장
높았던 시점을 고른다. ECAPA만 오래 올리면 화자에 따라 소리가 무너진다(README 참고).

    fit   대상 녹음 → <name>.nearest.json, <name>.blend.json, <name>.invert.json (Supertonic 스타일 JSON)
    pack  스타일 JSON → sherpa-onnx generate_voices_bin.py로 voice.bin → 앱 음성 팩 zip
    eval  음성 팩을 sherpa-onnx(int8, 앱과 같은 경로)로 sid 합성 → 화자 유사도(WeSpeaker, WavLM-SV, ECAPA), CER

평가는 어디에도 쓰지 않은 WeSpeaker ResNet34를 주로 보고, WavLM-SV(기본값에서는 시점 선택에 씀)를 같이 본다.
ECAPA는 최적화 대상이라 부풀려진다.
모델·가중치·결과물은 VI_CACHE(기본 ~/.cache/epub-tts-spike/voice_inversion)에 둔다. 재현(맥):
    /opt/homebrew/bin/python3.12 -m venv ~/.cache/epub-tts-spike/inv-venv
    ~/.cache/epub-tts-spike/inv-venv/bin/pip install torch torchaudio onnx onnx2torch onnxruntime \
        soundfile numpy speechbrain transformers sherpa-onnx==1.13.8
    sh spike/voice_inversion/run_all.sh   # README 수치표 (맥 M4에서 약 4시간)
코랩은 colab.ipynb를 연다.
"""

import argparse
import json
import os
import random
import re
import shutil
import subprocess
import sys
import time
import unicodedata
import urllib.request
import zipfile
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
import torch.nn.functional as F
import torchaudio.functional as AF

CACHE = Path(os.environ.get("VI_CACHE", Path.home() / ".cache/epub-tts-spike/voice_inversion"))
os.environ.setdefault("HF_HOME", str(CACHE / "hf_cache"))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from compare_tts import cer, fetch_tar, make_asr  # noqa: E402  0단계 스파이크의 ASR·CER을 그대로 쓴다

SR = 44100
PRESETS = ["F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"]
PARTS = ["duration_predictor", "text_encoder", "vector_estimator", "vocoder"]
# HF 공식 fp32 ONNX(역추정용, 검증한 리비전에 고정)와 sherpa-onnx int8 릴리스(앱이 쓰는 것, 팩·평가용)
HF_REV = {"supertonic-3": "3cadd1ee6394adea1bd021217a0e650ede09a323", "supertonic-2": "75e6727618a02f323c720cba9478152d4bc16ca4"}
SHERPA = {
    "supertonic-3": "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
    "supertonic-2": "sherpa-onnx-supertonic-tts-int8-2026-03-06",
}
VOICES_BIN = ("https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/48cd9c3ca6edc9726e9eee887fd27ba873c71049/"
              "scripts/supertonic/generate_voices_bin.py")

# 최적화용 문장(평가 문장과 겹치지 않게)
FIT_TEXTS = {
    "ko": [
        "아침마다 창문을 열면 차가운 바람이 방 안으로 들어온다.",
        "그는 오래된 사진첩을 넘기며 한참 동안 말이 없었다.",
        "시장 골목에는 따뜻한 국수 냄새가 가득했다.",
        "내일은 비가 온다고 하니 우산을 꼭 챙기세요.",
        "도서관 창가 자리에 앉아 책을 펼쳤다.",
        "아이들은 운동장에서 공을 차며 큰 소리로 웃었다.",
        "기차는 예정보다 십 분 늦게 역에 도착했다.",
        "어머니는 부엌에서 조용히 노래를 흥얼거리셨다.",
        "산 정상에 오르자 멀리 바다가 내려다보였다.",
        "편지를 다 읽은 그녀는 천천히 고개를 들었다.",
        "왜 그렇게 생각하는지 이유를 말해 줄 수 있니?",
        "정말 오랜만이다! 그동안 잘 지냈어?",
    ],
    "en": [
        "The morning light spilled across the kitchen table.",
        "She closed the book and looked out at the quiet street.",
        "We walked along the river until the sun went down.",
        "Could you tell me where the nearest station is?",
        "He had never seen so many stars in a single night.",
        "The old clock in the hallway struck seven.",
    ],
}
# 검증용(--select best가 고르는 데 쓴다. 최적화·평가 문장과 겹치지 않게)
VAL_KO = [
    "바닷가 작은 마을에는 밤늦도록 등대 불빛이 깜박였다.",
    "할아버지는 낡은 의자에 앉아 지난 이야기를 들려주셨다.",
    "그 소식을 듣고 우리는 한동안 아무 말도 하지 못했다.",
]
# 평가용 한국어 문장 (서술·대화·긴 문장)
EVAL_KO = [
    "창밖으로 비가 내리기 시작하자 그는 읽던 책을 덮었다.",
    "\"어디 가니?\" 엄마가 물었다.",
    "같이 맛있는 국물을 먹으며 신라의 역사를 이야기했다.",
    "도서관 맨 안쪽 서가에는 아무도 빌려 가지 않은 낡은 책들이 꽂혀 있었다.",
]
# supertonic-3 HF audio_samples: (언어, Voice Builder 결과 wav가 읽은 문장) — HF README의 짝
TARGETS = {
    "luna": ("en", "I was not afraid of silence. I had lived with it long enough to know that, sometimes, it speaks more honestly than people do."),
    "alphonse": ("ko", "혼자 떠나기엔 길이 험하구나. 이 낡은 검을 가져가거라. 언젠가 어둠이 네 이름을 부르더라도, 부디 빛을 잊지 말거라."),
    "keld": ("en", "Here's a story worth paying attention to. Supertone has released Supertonic 3, its on-device TTS model. This version expands support to thirty-one languages and improves reading stability."),
    "nora": ("en", "Good morning, thank you for calling. How can I help you today?"),
}


def device():
    return "cuda" if torch.cuda.is_available() else "mps" if torch.backends.mps.is_available() else "cpu"


def gpu_mem(dev):
    """MPS는 문장 길이마다 다른 버퍼를 캐시에 쌓아 두어 300회면 8GB를 넘겼다. 반복마다 비우고 쓰는 양(GB)을 돌려준다."""
    if dev == "mps":
        torch.mps.empty_cache()
        return torch.mps.driver_allocated_memory() / 1e9
    return torch.cuda.max_memory_allocated() / 1e9 if dev == "cuda" else 0.0


def download(url, path):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(url, f"{path}.part")
        os.replace(f"{path}.part", path)
    return path


def hf_model(model):
    """HF Supertone/<model>의 fp32 ONNX, 프리셋 JSON, LICENSE(OpenRAIL-M). v3이면 audio_samples도."""
    root = CACHE / "hf" / model
    files = [f"onnx/{p}.onnx" for p in PARTS] + ["onnx/tts.json", "onnx/unicode_indexer.json", "LICENSE"]
    files += [f"voice_styles/{p}.json" for p in PRESETS]
    if model == "supertonic-3":
        files += [f"audio_samples/{t}_{k}.wav" for t in TARGETS for k in ("reference", "supertonic3")]
    for f in files:
        download(f"https://huggingface.co/Supertone/{model}/resolve/{HF_REV[model]}/{f}", root / f)
    return root


def onnx_to_torch(path):
    """onnx2torch 1.5는 opset 19 변환기(Shape, Constant, Cast, Pad ...)와 빈 선택 입력을 모른다. 그 둘만 손본다.
    opset 19의 변경은 타입 확장뿐이라 직전 버전 변환기로 같은 계산이 된다(onnxruntime과 최대 5e-5 차이)."""
    import onnx
    import onnx2torch.converter as conv
    from onnx2torch.node_converters import registry as reg

    def get_converter(operation_type, version, domain=""):
        vs = [d.version for d in reg._CONVERTER_REGISTRY
              if d.domain == domain and d.operation_type == operation_type and d.version <= version]
        if not vs:
            raise NotImplementedError(f"{operation_type}-{version}")
        return reg._CONVERTER_REGISTRY[reg.OperationDescription(domain, operation_type, max(vs))]

    conv.get_converter = get_converter
    m = onnx.load(str(path))
    nodes = []
    for n in m.graph.node:
        if n.op_type == "Constant" and n.attribute[0].name == "value":  # Clip min/max를 정적 값으로 보이게
            t = n.attribute[0].t
            t.name = n.output[0]
            m.graph.initializer.append(t)
        else:
            while n.input and n.input[-1] == "":
                n.input.pop()
            nodes.append(n)
    del m.graph.node[:]
    m.graph.node.extend(nodes)
    return conv.convert(m).eval().requires_grad_(False)


def read_style(path):
    j = json.loads(Path(path).read_text())
    return (torch.tensor(j["style_ttl"]["data"], dtype=torch.float32).reshape(1, 50, 256),
            torch.tensor(j["style_dp"]["data"], dtype=torch.float32).reshape(1, 8, 16))


def write_style(path, ttl, dp, **meta):
    """Supertonic 스타일 JSON (공식 voice_styles, Voice Builder, 앱의 목소리 가져오기와 같은 형식)."""
    j = {"style_ttl": {"dims": [1, 50, 256], "data": ttl.detach().cpu().reshape(1, 50, 256).tolist()},
         "style_dp": {"dims": [1, 8, 16], "data": dp.detach().cpu().reshape(1, 8, 16).tolist()},
         "metadata": {"made_by": "spike/voice_inversion", **meta}}
    Path(path).write_text(json.dumps(j))


class Supertonic:
    """공식 py/helper.py의 합성 경로를 torch로. 문장 전처리는 깨끗한 테스트 문장에 필요한 만큼만 옮겼다."""

    def __init__(self, model, dev):
        root = hf_model(model)
        self.dev = dev
        self.dp, self.te, self.ve, self.voc = (onnx_to_torch(root / f"onnx/{p}.onnx").to(dev) for p in PARTS)
        self.indexer = json.loads((root / "onnx/unicode_indexer.json").read_text())
        styles = [read_style(root / f"voice_styles/{p}.json") for p in PRESETS]
        self.ttl = torch.cat([s[0] for s in styles]).to(dev)  # [10,50,256]
        self.dps = torch.cat([s[1] for s in styles]).to(dev)  # [10,8,16]

    def ids(self, text, lang):
        t = re.sub(r"\s+", " ", unicodedata.normalize("NFKD", text)).strip()
        if not re.search(r"[.!?;:,'\"')\]}…。」』】〉》›»]$", t):
            t += "."
        t = f"<{lang}>{t}</{lang}>"
        return torch.tensor([[self.indexer[ord(c)] for c in t]], device=self.dev)

    def __call__(self, ttl, dp, text, lang, steps, seed, speed=1.05, checkpoint=False):
        """[n] 파형(44.1kHz). ttl에 대해 미분 가능. dp는 길이만 정하므로 경사가 없다."""
        ids = self.ids(text, lang)
        mask = torch.ones(1, 1, ids.shape[1], device=self.dev)
        with torch.no_grad():
            n = int(self.dp(ids, dp, mask).item() / speed * SR)
        latent_len = -(-n // 3072)  # base_chunk_size 512 × chunk_compress_factor 6
        g = torch.Generator().manual_seed(seed)
        x = torch.randn(1, 144, latent_len, generator=g).to(self.dev)
        lmask = torch.ones(1, 1, latent_len, device=self.dev)
        temb = self.te(ids, ttl, mask)
        total = torch.tensor([float(steps)], device=self.dev)
        for s in range(steps):
            args = (x, temb, ttl, lmask, mask, torch.tensor([float(s)], device=self.dev), total)
            x = torch.utils.checkpoint.checkpoint(self.ve, *args, use_reentrant=False) if checkpoint else self.ve(*args)
        return self.voc(x)[0, :n]


class Ecapa:
    """speechbrain ECAPA-TDNN (VoxCeleb). 최적화 대상 지표."""

    def __init__(self, dev):
        from speechbrain.inference.speaker import EncoderClassifier
        self.m = EncoderClassifier.from_hparams("speechbrain/spkrec-ecapa-voxceleb", savedir=str(CACHE / "ecapa"),
                                                run_opts={"device": dev})
        self.m.mods.requires_grad_(False)

    def __call__(self, wav16):
        return self.m.encode_batch(wav16[None]).reshape(-1)


class WavLMSV:
    """microsoft/wavlm-base-plus-sv. 경사에는 쓰지 않는다. 기본값(--select best)에서는 시점 선택에 쓴다."""

    def __init__(self, dev):
        from transformers import WavLMForXVector
        self.m = WavLMForXVector.from_pretrained("microsoft/wavlm-base-plus-sv").to(dev).eval().requires_grad_(False)

    def __call__(self, wav16):
        return self.m(input_values=wav16[None]).embeddings.reshape(-1)


class WeSpeaker:
    """sherpa-onnx로 돌리는 WeSpeaker ResNet34 (VoxCeleb). 미분이 안 되는 두 번째 평가용 지표."""

    def __init__(self, dev):
        import sherpa_onnx
        path = download("https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/"
                        "wespeaker_en_voxceleb_resnet34_LM.onnx", CACHE / "spk/wespeaker_en_voxceleb_resnet34_LM.onnx")
        self.m = sherpa_onnx.SpeakerEmbeddingExtractor(
            sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(path), num_threads=4))
        self.dev = dev

    def __call__(self, wav16):
        s = self.m.create_stream()
        s.accept_waveform(16000, wav16.detach().cpu().numpy())
        s.input_finished()
        return torch.tensor(self.m.compute(s), device=self.dev)


def embed(enc, wav, sr):
    """16kHz로 바꿔 음량을 맞추고, 길면 10초씩 잘라 평균한 단위 벡터."""
    w = AF.resample(wav, sr, 16000) if sr != 16000 else wav
    chunks = [c for c in w.split(160000) if len(c) >= 32000] or [w]
    es = [F.normalize(enc(c / (c.pow(2).mean().sqrt() + 1e-6) * 0.05), dim=0) for c in chunks]
    return F.normalize(torch.stack(es).mean(0), dim=0)


def load_wav(path, seconds=None, offset=0.0):
    w, sr = sf.read(path, dtype="float32", always_2d=True)
    w = w.mean(1)[int(offset * sr):]
    return torch.from_numpy(w[: int(seconds * sr)] if seconds else w), sr


def load_refs(paths, seconds=None):
    """Several takes joined into one 44.1 kHz mono recording, 0.3 s of silence between files (as colab.ipynb does)."""
    parts = []
    for p in paths:
        w, sr = load_wav(p)
        parts += [AF.resample(w, sr, SR), torch.zeros(int(SR * 0.3))]
    w = torch.cat(parts[:-1])
    return (w[: int(seconds * SR)] if seconds else w), SR


def fit(a):
    dev = device()
    torch.manual_seed(0)
    random.seed(0)
    st = Supertonic(a.model, dev)
    wav, sr = load_refs(a.ref, a.seconds)
    # 최적화 지표(들)와 검증 지표. 검증 지표는 경사에 안 쓰고 기록하거나(--select last) 가장 좋은 시점을 고르는 데만 쓴다
    # (--select best). 검증이 같이 오르지 않으면 최적화 지표만 속인 것.
    encs = [Ecapa(dev)] + ([WavLMSV(dev)] if a.objective == "ecapa+wavlm" else [])
    judge_name, judge = ("wespeaker", WeSpeaker(dev)) if len(encs) > 1 else ("wavlm", WavLMSV(dev))
    with torch.no_grad():
        targets = [embed(e, wav.to(dev), sr) for e in encs]
        target_judge = embed(judge, wav.to(dev), sr)

    def held_out(ttl, dp):
        with torch.no_grad():
            return round(float(torch.stack([F.cosine_similarity(embed(judge, st(ttl, dp, t, "ko", a.eval_steps, 7 + i), SR),
                                                                target_judge, dim=0) for i, t in enumerate(VAL_KO)]).mean()), 4)
    texts = [(t, "ko") for t in FIT_TEXTS["ko"]] + [(t, a.lang) for t in FIT_TEXTS.get(a.lang, []) if a.lang != "ko"]
    probe = texts[:3] + [t for t in texts if t[1] != "ko"][:2]
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    log = {"ref": [str(p) for p in a.ref], "seconds": len(wav) / sr, "model": a.model, "device": dev, "objective": a.objective}

    def sim(ttl, dp, items, steps, seed=0, grad=False):
        with torch.set_grad_enabled(grad):
            sims = []
            for i, (t, l) in enumerate(items):
                w = st(ttl, dp, t, l, steps, seed + i, checkpoint=grad and a.checkpoint)
                sims += [F.cosine_similarity(embed(e, w, SR), tg, dim=0) for e, tg in zip(encs, targets)]
            return torch.stack(sims).mean()

    # (a) 가장 가까운 프리셋
    t0 = time.time()
    sims = torch.stack([sim(st.ttl[i:i + 1], st.dps[i:i + 1], probe, a.eval_steps) for i in range(10)])
    best = int(sims.argmax())
    log["preset_sims"] = dict(zip(PRESETS, [round(float(s), 4) for s in sims]))
    log["nearest"] = PRESETS[best]
    write_style(out / f"{a.name}.nearest.json", st.ttl[best], st.dps[best], method="nearest", preset=PRESETS[best])
    log["time_nearest"] = time.time() - t0

    # (b) 프리셋 혼합: softmax 가중치 10개만 학습
    t0 = time.time()
    logits = (sims / 0.05).detach().clone().requires_grad_(True)
    opt = torch.optim.Adam([logits], lr=0.1)
    for it in range(a.blend_iters):
        w = logits.softmax(0)
        loss = 1 - sim((w[:, None, None] * st.ttl).sum(0, keepdim=True), (w[:, None, None] * st.dps).sum(0, keepdim=True),
                       random.sample(texts, a.batch), a.fit_steps, seed=1000 + it * 10, grad=True)
        opt.zero_grad()
        loss.backward()
        opt.step()
        gpu_mem(dev)
    w = logits.detach().softmax(0)
    blend_ttl, blend_dp = (w[:, None, None] * st.ttl).sum(0, keepdim=True), (w[:, None, None] * st.dps).sum(0, keepdim=True)
    log["blend_weights"] = dict(zip(PRESETS, [round(float(x), 3) for x in w]))
    write_style(out / f"{a.name}.blend.json", blend_ttl, blend_dp, method="blend", weights=log["blend_weights"])
    log["time_blend"] = time.time() - t0
    log[f"heldout_{judge_name}"] = {"nearest": held_out(st.ttl[best:best + 1], st.dps[best:best + 1]),
                            "blend": held_out(blend_ttl, blend_dp)}

    # (c) 역추정: style_ttl 전체를 학습. 프리셋 사이 퍼짐(원소별 표준편차)으로 잰 거리에 L2 벌점.
    t0 = time.time()
    # A preset named with --init: the one eval found closest. ECAPA's own pick (nearest) often isn't (README).
    start = PRESETS.index(a.init) if a.init in PRESETS else best if a.init == "nearest" else None
    base_ttl, base_dp = (st.ttl[start:start + 1], st.dps[start:start + 1]) if start is not None else (blend_ttl, blend_dp)
    log["init"] = a.init
    spread = st.ttl.std(0, keepdim=True)
    delta = torch.zeros_like(base_ttl, requires_grad=True)
    opt = torch.optim.Adam([delta], lr=a.lr)
    curve, held = [], [(0, held_out(base_ttl, base_dp))]
    best_val, best_delta = held[0][1], delta.detach().clone()
    mem = 0.0
    for it in range(a.iters):
        s = 1 - sim(base_ttl + delta * spread, base_dp, random.sample(texts, a.batch), a.fit_steps, seed=5000 + it * 10, grad=True)
        loss = s + a.reg * delta.pow(2).mean()
        opt.zero_grad()
        loss.backward()
        opt.step()
        mem = max(mem, gpu_mem(dev))
        curve.append(round(1 - float(s.detach()), 4))
        if (it + 1) % 25 == 0:
            held.append((it + 1, held_out(base_ttl + delta.detach() * spread, base_dp)))
            if held[-1][1] > best_val:
                best_val, best_delta = held[-1][1], delta.detach().clone()
            print(f"[{a.name}] it {it + 1} {a.objective} {np.mean(curve[-25:]):.3f} {judge_name}(검증) {held[-1][1]:.3f} "
                  f"|delta| {float(delta.pow(2).mean().sqrt()):.2f} gpu {mem:.1f}GB", flush=True)
    log["invert_curve"] = curve
    log[f"invert_heldout_{judge_name}"] = held
    final = best_delta if a.select == "best" else delta.detach()
    log[f"heldout_{judge_name}"]["invert"] = best_val if a.select == "best" else held[-1][1]
    log["selected_iter"] = next(i for i, v in held if v == best_val) if a.select == "best" else a.iters
    log["delta_rms_in_preset_std"] = float(final.pow(2).mean().sqrt())
    write_style(out / f"{a.name}.invert.json", base_ttl + final * spread, base_dp, method="invert", init=a.init,
                iters=a.iters, selected_iter=log["selected_iter"], lr=a.lr, reg=a.reg, fit_steps=a.fit_steps,
                objective=a.objective, ref_seconds=log["seconds"])
    log["time_invert"] = time.time() - t0
    log["gpu_gb"] = round(mem, 2)
    log["args"] = vars(a)
    (out / f"{a.name}.fit.json").write_text(json.dumps(log, ensure_ascii=False, indent=1))
    print(json.dumps({k: v for k, v in log.items() if k not in ("invert_curve", "args")}, ensure_ascii=False))


def pack(a):
    """프리셋 10개(sid 0~9) 뒤에 새 스타일을 붙인 voice.bin과 앱 음성 팩 zip을 만든다. sid 순서를 출력한다."""
    base, hf = fetch_tar("tts-models", SHERPA[a.model]), hf_model(a.model)
    work = CACHE / "pack" / Path(a.out).stem
    shutil.rmtree(work, ignore_errors=True)
    (work / "styles").mkdir(parents=True)
    for p in PRESETS:
        shutil.copy(hf / f"voice_styles/{p}.json", work / "styles" / f"{p}.json")
    for i, s in enumerate(a.styles):  # generate_voices_bin.py는 파일 이름 순서로 sid를 매긴다: 대문자 프리셋 뒤에 오게
        shutil.copy(s, work / "styles" / f"x{i:02d}_{Path(s).stem}.json")
    tool = download(VOICES_BIN, CACHE / "ref/generate_voices_bin.py")
    subprocess.run([sys.executable, "-I", str(tool), str(work / "styles"), str(work / "voice.bin")], check=True)
    names = sorted(p.stem for p in (work / "styles").glob("*.json"))
    with zipfile.ZipFile(a.out, "w", zipfile.ZIP_STORED) as z:  # onnx는 압축이 거의 안 된다
        for f in sorted(base.glob("*.onnx")) + [base / "tts.json", base / "unicode_indexer.bin"]:
            z.write(f, f.name)
        z.write(work / "voice.bin", "voice.bin")
        z.write(hf / "LICENSE", "LICENSE")  # 가중치 라이선스(OpenRAIL-M). sherpa 릴리스의 LICENSE는 예제 코드 MIT다.
        z.writestr("voices.txt", "\n".join(f"{i}\t{n}" for i, n in enumerate(names)) + "\n")
    print(f"{a.out}: " + ", ".join(f"sid {i}={n}" for i, n in enumerate(names)))


def evaluate(a):
    """음성 팩(zip)을 sherpa-onnx로 열어 sid마다 평가 문장을 합성하고, 대상 녹음과의 화자 유사도와 CER을 잰다."""
    import sherpa_onnx

    dev = device()
    hf = hf_model("supertonic-3")
    d = CACHE / "pack" / (Path(a.pack).stem + "_unzipped")
    shutil.rmtree(d, ignore_errors=True)
    zipfile.ZipFile(a.pack).extractall(d)
    names = dict(line.split("\t") for line in (d / "voices.txt").read_text().split("\n") if line)
    sid_of = {v: int(k) for k, v in names.items()}
    onnx = lambda p: str(next(d.glob(f"{p}*.onnx")))
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
            duration_predictor=onnx("duration_predictor"), text_encoder=onnx("text_encoder"),
            vector_estimator=onnx("vector_estimator"), vocoder=onnx("vocoder"), tts_json=str(d / "tts.json"),
            unicode_indexer=str(d / "unicode_indexer.bin"), voice_style=str(d / "voice.bin")),
        num_threads=4)))
    encs = {"wavlm": WavLMSV(dev), "wespeaker": WeSpeaker(dev), "ecapa": Ecapa(dev)}
    asr = make_asr()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)

    def emb(w, sr):
        with torch.no_grad():
            return {k: embed(e, torch.as_tensor(w, dtype=torch.float32).to(dev), sr) for k, e in encs.items()}

    def cos(x, y):
        return {k: round(float(F.cosine_similarity(x[k], y[k], dim=0)), 4) for k in x}

    def mean(rows):
        return {k: round(float(np.mean([r[k] for r in rows])), 4) for k in rows[0]}

    # (이름, 언어, Voice Builder 결과가 읽은 문장, 녹음, Voice Builder 결과). --ref면 사용자 녹음 하나만 본다.
    targets = [(a.name, "ko", None, a.ref, None)] if a.ref else [
        (t, *TARGETS[t], hf / f"audio_samples/{t}_reference.wav", hf / f"audio_samples/{t}_supertonic3.wav")
        for t in a.targets]
    rows = {}
    for target, lang, same_text, ref_path, vb_path in targets:
        ref_w, ref_sr = load_refs(ref_path) if isinstance(ref_path, list) else load_wav(ref_path)
        ref = emb(ref_w, ref_sr)
        r = rows[target] = {}
        half = len(ref_w) // 2  # 상한 참고: 같은 녹음의 앞·뒤 절반끼리
        r["ref_half_vs_half"] = cos(emb(ref_w[:half], ref_sr), emb(ref_w[half:], ref_sr))
        if vb_path:
            r["voice_builder"] = cos(emb(*load_wav(vb_path)), ref)
            shutil.copy(ref_path, out / f"{target}__reference.wav")
            shutil.copy(vb_path, out / f"{target}__voice_builder.wav")
        voices = [n for n in names.values() if f"_{target}." in n or f"_{target}_" in n]
        items = ([(same_text, lang)] if same_text else []) + [(t, "ko") for t in EVAL_KO]
        for n in PRESETS + voices:
            res = {"same_text": None, "ko": [], "ko_cer": []}
            for i, (text, l) in enumerate(items):
                cfg = sherpa_onnx.GenerationConfig()
                cfg.sid, cfg.num_steps, cfg.extra["lang"] = sid_of[n], a.steps, l
                g = tts.generate(text, cfg)
                gpu_mem(dev)
                w = np.asarray(g.samples, dtype=np.float32)
                s = cos(emb(w, g.sample_rate), ref)
                if same_text and i == 0:
                    res["same_text"] = s
                else:
                    res["ko"].append(s)
                    res["ko_cer"].append(cer(text, asr(AF.resample(torch.from_numpy(w), g.sample_rate, 16000).numpy(), 16000)))
                if n in voices:
                    sf.write(out / f"{target}__{n}__{i}.wav", w, g.sample_rate)
            r[n] = {**res, "ko": mean(res["ko"]), "ko_cer": round(float(np.mean(res["ko_cer"])), 4)}
            print(target, n, r[n], flush=True)
        r["preset_mean"] = {k: mean([r[p][k] for p in PRESETS]) for k in ("ko", "same_text") if r[PRESETS[0]][k]}
    (out / "eval.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fit")
    f.add_argument("--ref", required=True, nargs="+", help="대상 화자 녹음 wav 하나 이상 (모노/스테레오, 아무 샘플레이트). 여러 개면 이어 붙인다")
    f.add_argument("--name", required=True)
    f.add_argument("--lang", default="ko", help="녹음 언어 (ko, en). 최적화 문장에 섞는다")
    f.add_argument("--model", default="supertonic-3", choices=list(SHERPA))
    f.add_argument("--seconds", type=float, help="녹음 앞에서 이만큼만 쓴다")
    f.add_argument("--out", default=str(CACHE / "styles"))
    f.add_argument("--init", default="blend", choices=["nearest", "blend", *PRESETS],
                   help="역추정 시작점: blend(혼합), nearest(ECAPA가 고른 프리셋), 또는 프리셋 이름(F1~M5). "
                        "eval에서 내 녹음과 가장 비슷했던 프리셋을 주면 좋다")
    f.add_argument("--iters", type=int, default=200)
    f.add_argument("--blend-iters", type=int, default=40)
    f.add_argument("--lr", type=float, default=0.05)
    f.add_argument("--reg", type=float, default=0.01)
    f.add_argument("--batch", type=int, default=2, help="반복마다 합성할 문장 수")
    f.add_argument("--fit-steps", type=int, default=4, help="최적화 중 flow-matching 스텝 수")
    f.add_argument("--eval-steps", type=int, default=8, help="앱과 같은 스텝 수")
    f.add_argument("--objective", default="ecapa", choices=["ecapa", "ecapa+wavlm"],
                   help="최적화할 화자 임베딩. ecapa+wavlm이면 held-out 기록은 WeSpeaker로 한다")
    f.add_argument("--select", default="best", choices=["last", "best"],
                   help="best: 25회마다 검증 문장으로 잰 유사도가 가장 높았던 시점(0회 = 시작점 포함)을 쓴다")
    f.add_argument("--checkpoint", action="store_true", help="flow 스텝마다 activation checkpointing (GPU 메모리가 모자랄 때)")
    k = sub.add_parser("pack")
    k.add_argument("styles", nargs="+")
    k.add_argument("--model", default="supertonic-3", choices=list(SHERPA))
    k.add_argument("--out", required=True)
    e = sub.add_parser("eval")
    e.add_argument("pack")
    e.add_argument("--targets", nargs="+", default=["luna"], help="HF audio_samples 화자 (luna alphonse keld nora)")
    e.add_argument("--ref", nargs="+", help="대신 이 녹음(들)과 비교한다 (사용자 녹음, fit과 같은 파일). --name과 같이")
    e.add_argument("--name", help="--ref의 이름. pack에 넣은 스타일 파일 이름에 _<name>. 이 들어 있어야 한다")
    e.add_argument("--steps", type=int, default=8)
    e.add_argument("--out", default=str(CACHE / "eval"))
    a = p.parse_args()
    {"fit": fit, "pack": pack, "eval": evaluate}[a.cmd](a)


if __name__ == "__main__":
    main()
