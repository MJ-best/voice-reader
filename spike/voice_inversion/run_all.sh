#!/bin/sh
# README 수치표 재현: HF audio_samples 화자로 fit → pack → eval. 맥 M4(MPS)에서 약 4시간. 이미 만든 스타일은 건너뛴다.
# 결과: $VI_CACHE/styles/<model>/*.json·*.log, $VI_CACHE/eval/<model>/eval.json·*.wav, $VI_CACHE/packs/*.zip
set -e
PY=${PY:-$HOME/.cache/epub-tts-spike/inv-venv/bin/python}
VI="$(cd "$(dirname "$0")" && pwd)/voice_inversion.py"
C=${VI_CACHE:-$HOME/.cache/epub-tts-spike/voice_inversion}
REF=$C/hf/supertonic-3/audio_samples
mkdir -p "$C/packs" "$C/eval"

fit() {  # 이름 모델 화자 언어 [앞에서 쓸 초]. (c): 가장 가까운 프리셋에서 시작, 300회 마지막 값
  mkdir -p "$C/styles/$2"
  [ -f "$C/styles/$2/$1.invert.json" ] && return
  "$PY" -I "$VI" fit --name "$1" --model "$2" --ref "$REF/$3_reference.wav" --lang "$4" ${5:+--seconds $5} \
    --init nearest --select last --iters 300 --out "$C/styles/$2" 2>&1 | grep -v -i -e warn -e "x = x\[" -e "return torch" > "$C/styles/$2/$1.log"
}
sel() {  # 이름 화자 언어. (c'): 혼합에서 시작, 200회 중 검증 문장 WavLM이 가장 높은 시점
  mkdir -p "$C/styles/supertonic-3-sel"
  [ -f "$C/styles/supertonic-3-sel/$1.invert.json" ] && return
  "$PY" -I "$VI" fit --name "$1" --ref "$REF/$2_reference.wav" --lang "$3" --init blend --select best --iters 200 \
    --out "$C/styles/supertonic-3-sel" 2>&1 | grep -v -i -e warn -e "x = x\[" -e "return torch" > "$C/styles/supertonic-3-sel/$1.log"
}

for m in supertonic-3 supertonic-2; do
  [ $m = supertonic-3 ] && "$PY" -I -c "import sys; sys.path.insert(0, '$(dirname "$VI")'); import voice_inversion as v; v.hf_model('$m')"
  fit luna $m luna en
  fit alphonse $m alphonse ko
  [ $m = supertonic-2 ] && continue  # 2는 대표 화자 둘만
  fit keld $m keld en
  fit nora $m nora en              # 녹음이 3초뿐
  fit luna_3s $m luna en 3
  fit luna_6s $m luna en 6
  fit alphonse_3s $m alphonse ko 3
  fit alphonse_6s $m alphonse ko 6
done
sel alphonse_sel alphonse ko
sel keld_sel keld en
sel nora_sel nora en
sel luna_sel luna en

for m in supertonic-3 supertonic-2 supertonic-3-sel; do
  set --
  for f in "$C"/styles/$m/*.invert.json; do b=${f%.invert.json}; set -- "$@" $b.nearest.json $b.blend.json $f; done
  "$PY" -I "$VI" pack "$@" --model ${m%-sel} --out "$C/packs/eval-$m.zip"
  t="luna alphonse keld nora"; [ $m = supertonic-2 ] && t="luna alphonse"
  "$PY" -I "$VI" eval "$C/packs/eval-$m.zip" --targets $t --out "$C/eval/$m" 2>&1 | grep -v -i warn > "$C/eval/$m.log" || true
done
