# 내 목소리로 듣기

내 목소리를 녹음해 **목소리 파일(JSON)** 을 만들고, 앱에 넣어 그 목소리로 책을 듣는 방법입니다.
목소리 파일은 **내 컴퓨터에서** 만듭니다. 녹음은 컴퓨터 밖으로 나가지 않고, 유료 서비스도 필요 없습니다.

1. [동의 확인](#1-동의-확인)
2. [녹음하기](#2-녹음하기) (대본 포함, 1분 30초 정도)
3. [컴퓨터 준비](#3-컴퓨터-준비)
4. [목소리 파일 만들기](#4-목소리-파일-만들기)
5. [들어 보고 고르기](#5-들어-보고-고르기) (어눌하면 [강도 낮추기](#어눌하게-들리면-강도-낮추기))
6. [앱에 넣기](#6-앱에-넣기)

**알아 둘 점**

- 앱의 Supertonic 모델은 그대로 두고, 녹음과 비슷하게 들리도록 목소리 특징값만 맞춥니다. 방법과 측정 결과는 [spike/voice_inversion/README.md](../spike/voice_inversion/README.md)에 있습니다.
- 목소리 색깔(음색)은 따라오지만, 말버릇과 억양까지 따라오지는 않습니다.
- 품질은 화자 인식 모델의 점수로만 확인했고, 사람 귀로 비교한 검증은 아직 없습니다. 만든 뒤 꼭 들어 보고 고르세요(5번).
- **Supertonic 2용과 3용은 따로 만듭니다.** 서술에 쓰는 기본 목소리는 Supertonic 2입니다. 2용 파일을 3에 넣으면 엉뚱한 목소리가 납니다.

## 1. 동의 확인

**본인 목소리이거나, 녹음된 사람에게 명시적인 동의를 받은 목소리만** 쓰세요.
Supertonic 모델의 라이선스(BigScience OpenRAIL-M)는 동의 없이 다른 사람을 흉내 내는 데 쓰는 것을 금지합니다.
만든 소리를 다른 사람에게 들려줄 때는 기계가 만든 소리라고 밝혀야 합니다.
앱도 목소리 파일을 넣기 전에 같은 내용을 다시 확인합니다.

## 2. 녹음하기

목소리 파일은 낱말 내용이 아니라 목소리 색깔을 뽑아냅니다. 그래서 한국어의 여러 소리와 말투가 고루 들어가도록 짠 대본을 읽습니다.
된소리(ㄲㄸㅃㅆㅉ), 거센소리(ㅋㅌㅍㅊ), 이중모음(ㅢ·ㅟ·ㅚ·ㅙ·ㅝ 등)이 골고루 들어 있고, 서술·질문·감탄·대사가 섞여 있습니다.

한 묶음을 한 파일로 녹음하세요. 모두 읽으면 1분 30초쯤 걸립니다(최소 15초는 있어야 합니다).

**파일 0 · 동의 문장**

> 나는 이 녹음 속 목소리의 주인이며, 이 목소리로 음성 합성 스타일을 만들어 내 기기에서 책을 읽는 데 쓰는 것에 동의합니다.

**파일 1 · 서술 (차분하게, 책 읽어 주듯)**

1. 창문을 열자 따뜻한 바람이 방 안으로 천천히 들어왔다.
2. 그녀는 오래된 편지를 꺼내, 희미해진 글씨를 한 줄씩 읽어 내려갔다.
3. 골목 끝 작은 빵집에서는 아침마다 고소한 냄새가 퍼졌다.
4. 푸른 들판 위로 구름 그림자가 지나가고, 멀리서 기차가 덜컹거리며 터널을 빠져나왔다.
5. 우리는 깨끗하게 닦은 유리컵에 차가운 보리차를 따라 마셨다.

**파일 2 · 대사 (자연스러운 감정은 살리되 과장하지 않게)**

6. "어디 가는 길이에요? 비가 올 것 같은데, 우산은 챙겼어요?"
7. "괜찮아, 걱정하지 마. 뭐 필요한 거 있으면 바로 연락할게."
8. "와, 정말 예쁘다! 이런 꽃은 처음 봐."
9. "왜 아무 말도 안 했어? 나는 네가 그렇게 외로운 줄 몰랐어."
10. "천천히 말해 줘. 무슨 일이 있었는지 처음부터 다 듣고 싶어."

**파일 3 · 긴 문장 (낭독하듯, 쉼표에서 살짝 쉬며)**

11. 할머니는 의자에 앉아 뜨개질을 하며, 젊은 시절 바닷가 마을에서 보낸 여름 이야기를 들려주셨다.
12. 짧은 겨울 해가 산 너머로 지자, 집집마다 하나둘 불이 켜지고 된장찌개 끓는 냄새가 골목을 채웠다.
13. 책장을 덮고 나서도 마지막 문장이 귓가에 오래 남아, 나는 한참 동안 창밖을 바라보았다.
14. 오늘도 끝까지 들어 주셔서 고맙습니다. 내일 또 같은 자리에서 이야기를 이어 가겠습니다.

**녹음 요령**

- **톤:** 앱이 이 목소리로 책을 읽게 되니, 평소 책을 소리 내어 읽어 주는 톤과 속도를 처음부터 끝까지 같게 유지하세요. 대사도 연기하듯 바꾸기보다 살짝만 살리면 됩니다.
- **장소:** 조용하고 소리가 울리지 않는 곳에서 녹음하세요. 옷이 많은 방이나 커튼을 친 방이 좋고, 화장실이나 빈 방은 피하세요. 에어컨, 냉장고, TV 소리도 끄세요. 녹음된 잡음과 울림까지 목소리로 흉내 낼 수 있습니다.
- **거리:** 폰이나 마이크를 입에서 한 뼘(약 20cm) 떨어뜨리세요. 정면보다 살짝 옆에 두면 'ㅍ·ㅂ' 바람 소리가 덜 들어갑니다. 녹음하는 동안 같은 거리를 유지하세요.
- **음질:** 녹음 앱을 가장 좋은 음질로 맞추세요. 아이폰 음성 메모는 설정 → 앱 → 음성 메모 → 오디오 품질 → **무손실**입니다. 잡음 제거나 효과는 켜지 마세요.
- **실수:** 문장 사이에 1초쯤 쉬세요. 대본과 똑같이 읽었는지는 검사하지 않으니, 틀리면 그 문장만 다시 읽고 이어 가면 됩니다.

## 3. 컴퓨터 준비

**필요한 것**

- Python 3.12(이 버전에서 확인했습니다)와 git
- 디스크 여유 공간 약 3GB. 처음 실행할 때 모델과 화자 인식 모델을 내려받습니다.
- GPU가 있으면 자동으로 씁니다. NVIDIA GPU(CUDA)와 Apple Silicon 맥(MPS) 모두 됩니다. GPU 메모리는 3~4GB를 씁니다.
  - Apple Silicon 맥(M4)에서 목소리 하나에 약 10분 걸렸습니다.
  - NVIDIA GPU와 CPU에서는 시간을 재 보지 않았습니다. CPU로도 돌지만 GPU보다 훨씬 오래 걸립니다.

**설치 (macOS · Linux)**

```bash
git clone https://github.com/MJ-best/voice-reader
cd voice-reader
python3 -m venv .venv-voice
source .venv-voice/bin/activate
pip install torch torchaudio onnx onnx2torch onnxruntime soundfile numpy speechbrain transformers sherpa-onnx==1.13.8
```

**설치 (Windows, PowerShell)**

```powershell
git clone https://github.com/MJ-best/voice-reader
cd voice-reader
py -3.12 -m venv .venv-voice
.venv-voice\Scripts\Activate.ps1
pip install torch torchaudio onnx onnx2torch onnxruntime soundfile numpy speechbrain transformers sherpa-onnx==1.13.8
```

NVIDIA GPU를 쓰려면 `torch`와 `torchaudio`는 [PyTorch 설치 안내](https://pytorch.org/get-started/locally/)에서 내 CUDA 버전에 맞는 명령으로 설치하세요.

**녹음을 wav로 바꾸기**

도구는 wav 파일을 읽습니다. 음성 메모처럼 m4a로 저장됐다면 먼저 wav로 바꾸세요.

```bash
ffmpeg -i 녹음1.m4a 녹음1.wav
```

macOS에는 설치 없이 쓸 수 있는 명령도 있습니다.

```bash
afconvert -f WAVE -d LEI16 녹음1.m4a 녹음1.wav
```

## 4. 목소리 파일 만들기

녹음 wav를 모두 `--ref` 뒤에 적습니다. 도구가 파일들을 하나로 이어 붙입니다. `--name`은 영문이나 숫자로 짓습니다.

```bash
python spike/voice_inversion/voice_inversion.py fit --ref 녹음0.wav 녹음1.wav 녹음2.wav 녹음3.wav --name me --model supertonic-2 --out my-voice
```

`my-voice` 폴더에 목소리 파일 세 개가 생깁니다.

| 파일 | 내용 |
|---|---|
| `me.invert.json` | 녹음에 맞춰 다듬은 목소리. 보통 이것을 씁니다 |
| `me.blend.json` | 기본 목소리 10개를 섞어 가장 가깝게 만든 목소리 |
| `me.nearest.json` | 기본 목소리 중 가장 비슷한 하나 |

대사만 다른 목소리로 읽게 할 Supertonic 3용이 필요하면 `--model supertonic-3`으로 한 번 더 만듭니다.

## 5. 들어 보고 고르기

세 파일로 확인용 음성 팩을 만들고, 앱과 같은 방식으로 한국어 문장을 합성해 녹음과 비교합니다.

```bash
python spike/voice_inversion/voice_inversion.py pack my-voice/me.nearest.json my-voice/me.blend.json my-voice/me.invert.json --model supertonic-2 --out my-voice/me-supertonic-2.zip
```

```bash
python spike/voice_inversion/voice_inversion.py eval my-voice/me-supertonic-2.zip --ref 녹음0.wav 녹음1.wav 녹음2.wav 녹음3.wav --name me --out my-voice/eval
```

- **들어 보기:** `my-voice/eval` 폴더에 문장마다 소리 파일이 생깁니다.
  - `me__x02_me.invert__0.wav` ~ `__3.wav`: invert
  - `me__x01_me.blend__0.wav` ~ `__3.wav`: blend
  - `me__x00_me.nearest__0.wav` ~ `__3.wav`: nearest
- **점수 보기:** 화면에 목소리마다 점수가 나옵니다.
  - `wespeaker`·`wavlm`: 내 녹음과 얼마나 비슷한지(클수록 비슷)
  - `ko_cer`: 받아쓰기 오류율(발음이 뭉개졌는지, 작을수록 좋음)
- **고르는 기준:** invert의 `ko_cer`이 0.1 이하이고, `wespeaker`가 기본 목소리(F1~M5) 중 가장 높은 것보다 높으면 invert를 씁니다. 숫자보다 귀로 들어 보고 고르는 것이 먼저입니다.
- **기본 목소리보다 덜 비슷하면 다시 만들기:** 다듬기를 시작한 지점이 빗나간 경우가 많습니다. 점수표에서 `wespeaker`가 높으면서 `ko_cer`이 0.1 이하인 기본 목소리(예: `M1`)를 골라, 4번 명령에 `--init M1`을 붙여 다시 만드세요. 실제 녹음 하나에서 이렇게 해서 invert의 `wespeaker`가 0.66에서 0.82로 올랐습니다.

### 어눌하게 들리면 강도 낮추기

점수는 좋은데 실제로 들어 보면 발음이 꼬이거나 어눌하게 들릴 수 있습니다. 목소리 값을 녹음 쪽으로 많이 끌어당길수록 평가 모델은 비슷하다고 하지만, 모델이 자연스럽게 내던 범위에서 멀어지기 때문입니다.
이때는 다듬기를 시작한 기본 목소리에서 **일부만 옮긴 약한 버전**을 만들어 비교하세요. 다시 학습하지 않아 몇 초면 됩니다.

```bash
python spike/voice_inversion/voice_inversion.py mix my-voice/me.invert.json --percent 25 50 75 --model supertonic-2
```

- `my-voice` 폴더에 `me.invert.25.json`, `me.invert.50.json`, `me.invert.75.json`이 생깁니다. 숫자가 작을수록 기본 목소리에 가까워 자연스럽고, 클수록 내 목소리에 가깝습니다.
- 시작점은 4번에서 쓴 것을 알아서 찾습니다(`--init`을 줬으면 그 기본 목소리, 아니면 같은 폴더의 `me.blend.json`). 다른 시작점을 쓰려면 `--base M1`처럼 줍니다.
- 고르는 방법은 두 가지입니다.
  - 위 5번처럼 `pack`과 `eval`에 이 파일들을 넣어 들어 봅니다.
  - 파일들을 모두 앱에 넣고 책 문장으로 비교합니다. 넣을 때마다 목록 끝에 추가됩니다.
- 실제 녹음 하나에서는 100%가 어눌하게 들렸고, 75%가 자연스러우면서 닮았다고 골랐습니다.

## 6. 앱에 넣기

1. 고른 JSON 파일의 이름을 `내 목소리.json`처럼 바꿉니다. 파일 이름이 앱의 목소리 목록에 그대로 보입니다.
2. 파일을 기기로 옮깁니다(USB, 클라우드, 메일 등).
3. 앱 서재 위쪽의 **목소리** 버튼 → **직접 만든 목소리 넣기** → 동의 확인 → 파일을 고릅니다.
4. "어느 음성 팩에 넣을까요?"에서 **만든 모델과 같은 팩**을 고릅니다(`--model supertonic-2`로 만들었으면 Supertonic 2).
5. 목소리 목록 맨 끝에 추가됩니다. 누르면 들어 보고 그 목소리로 고릅니다.

앱 쪽 화면은 [사용 방법 8번](guide.md#8-직접-만든-목소리-넣기)에 있습니다.

## 문제 해결

| 증상 | 할 일 |
|---|---|
| invert와 blend의 소리가 똑같음 | 다듬는 동안 녹음과 더 가까워지지 않은 경우입니다. 더 조용한 곳에서, 더 길게(1분 이상) 다시 녹음하세요 |
| GPU 메모리 부족 오류 | `fit`에 `--checkpoint`를 붙이면 메모리를 덜 쓰는 대신 조금 느려집니다 |
| 녹음 파일을 못 읽음 | m4a·mp3라면 wav로 바꾸세요(3번) |
| 캐시를 지우고 싶음 | 내려받은 모델은 `~/.cache/epub-tts-spike`(Windows는 `%USERPROFILE%\.cache\epub-tts-spike`)에 있습니다. 다 쓰면 지워도 됩니다. 위치는 `VI_CACHE` 환경 변수로 바꿀 수 있습니다 |

## 개인정보

- 녹음과 만든 목소리 파일은 내 컴퓨터에만 있습니다. 도구는 아무것도 올리지 않고 모델만 내려받습니다.
  - Supertonic 모델: Hugging Face `Supertone`
  - 앱과 같은 int8 모델과 한국어 받아쓰기 모델: sherpa-onnx GitHub 릴리스
  - 화자 인식 모델: SpeechBrain ECAPA, Microsoft WavLM-SV, WeSpeaker
- 목소리 파일에는 녹음이 들어 있지 않지만, 그 목소리를 흉내 낼 수 있습니다. 녹음만큼 조심해서 다루고 남과 공유하지 마세요.
