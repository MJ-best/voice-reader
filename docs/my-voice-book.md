# 책 한 권을 내 목소리로 미리 만들기

컴퓨터에서 [VoxCPM2](https://github.com/OpenBMB/VoxCPM)로 책 한 권을 내 목소리로 미리 만들어 두고, 앱은 그 음성을 재생만 하는 방법입니다.
[내 목소리로 듣기](my-voice.md)(Supertonic 목소리 파일)보다 훨씬 닮게 나오지만, 컴퓨터에서 오래 걸립니다. 아끼는 책 몇 권에 쓰는 방법입니다.

1. [준비물과 걸리는 시간](#1-준비물과-걸리는-시간)
2. [컴퓨터 준비](#2-컴퓨터-준비)
3. [앱에서 문장 내보내기](#3-앱에서-문장-내보내기)
4. [음성 만들기](#4-음성-만들기)
5. [앱에 넣기](#5-앱에-넣기)

**알아 둘 점**

- VoxCPM2(20억 파라미터)는 e-book 리더에서 직접 돌리기에는 너무 큽니다. 그래서 문장 내보내기 → 컴퓨터에서 합성 → 앱에서 재생, 세 단계로 나눴습니다.
- 녹음, 문장, 만든 음성은 모두 내 컴퓨터와 기기에만 있습니다. 서버나 유료 서비스가 필요 없습니다.
- 본인의 목소리이거나, 화자에게 명시적인 동의를 받은 목소리만 쓰세요. 앱도 음성을 넣을 때 동의를 묻습니다.

## 1. 준비물과 걸리는 시간

- **녹음 하나:** 책 읽어 주는 톤으로 10초 이상. [내 목소리로 듣기의 대본](my-voice.md#2-녹음하기) 중 파일 1(서술)이 알맞습니다. 앞 13초만 씁니다.
- **컴퓨터:** 디스크 여유 약 5GB(모델 3.5GB), 메모리 8GB 이상.
- **시간:** Apple M4 맥북(16GB)에서 음성 1초를 만드는 데 약 3초 걸렸습니다. 10시간 분량 책이면 30시간쯤입니다.
  - 중간에 멈춰도 다시 실행하면 이어서 만듭니다. 며칠에 나눠 만들어도 됩니다.
  - 다른 컴퓨터에서는 재 보지 않았습니다. NVIDIA GPU가 있으면 더 빠를 것으로 봅니다.

이 맥에서 같은 4문장을 녹음과 비교한 결과입니다(WeSpeaker · WavLM은 클수록 닮음, 받아쓰기 오류율은 작을수록 정확함).

| | WeSpeaker | WavLM | 받아쓰기 오류율 | 음성 1초에 걸린 시간 |
|---|---|---|---|---|
| Supertonic 3 목소리 파일(남성 2 시작, 100%) | 0.77 | 0.94 | 4.3% | 기기에서 바로 |
| **VoxCPM2, 녹음 13초** | **0.85** | **0.96** | **3.7%** | 3.0초 |
| VoxCPM2, 녹음 35초 | 0.86 | 0.96 | 6.0% | 4.2초 |

녹음을 길게 넣으면 매 문장 그만큼 더 읽어야 해서 느려지고, 들어 보기에도 13초가 더 자연스러웠습니다.

## 2. 컴퓨터 준비

VoxCPM2를 압축 모델(GGUF)로 돌리는 [llama.cpp-omni](https://github.com/tc-mb/llama.cpp-omni)를 빌드하고, 모델 두 개를 받습니다. VoxCPM 공식 README가 안내하는 방법입니다.

**macOS** (Apple Silicon. 확인한 환경입니다)

```bash
brew install cmake
git clone --depth 1 https://github.com/tc-mb/llama.cpp-omni.git
cmake -S llama.cpp-omni -B llama.cpp-omni/build -DCMAKE_BUILD_TYPE=Release
cmake --build llama.cpp-omni/build --target voxcpm2-cli -j
```

맥 GPU(Metal)는 자동으로 씁니다. 소리 변환에는 맥에 들어 있는 `afconvert`를 씁니다.

**Linux · Windows** (확인하지 않았습니다)

- cmake와 C++ 컴파일러(Windows는 Visual Studio)로 같은 명령을 실행합니다. NVIDIA GPU는 `-DGGML_CUDA=ON`, 그 밖의 GPU는 `-DGGML_VULKAN=ON`을 cmake 첫 명령에 붙입니다. 자세한 것은 llama.cpp-omni의 빌드 안내를 보세요.
- Windows에서는 프로그램이 `build\bin\Release\voxcpm2-cli.exe`에 생길 수 있습니다.
- 소리 변환에 [ffmpeg](https://ffmpeg.org/)가 필요합니다.

**모델 받기** (BaseLM Q8 1.7GB, Acoustic 1.8GB)

```bash
mkdir voxcpm2-gguf
curl -L -o voxcpm2-gguf/VoxCPM2-BaseLM-Q8_0.gguf https://huggingface.co/DennisHuang648/VoxCPM2-GGUF/resolve/main/VoxCPM2-BaseLM-Q8_0.gguf
curl -L -o voxcpm2-gguf/VoxCPM2-Acoustic-F16.gguf https://huggingface.co/DennisHuang648/VoxCPM2-GGUF/resolve/main/VoxCPM2-Acoustic-F16.gguf
```

**변환 도구** (이 저장소의 `spike/voxcpm_book.py`, Python 3.10 이상)

```bash
git clone https://github.com/MJ-best/voice-reader
python3 -m venv .venv-book
.venv-book/bin/pip install numpy soundfile
```

Windows에서는 `.venv-book\Scripts\pip`입니다.

## 3. 앱에서 문장 내보내기

1. 만들 책을 열고 **설정 → 낭독 → 내 목소리 음성**으로 갑니다.
2. **1. 문장 내보내기**를 누르고 저장할 곳을 고릅니다. `<책 제목> 문장.json`이 생깁니다.
3. 이 파일을 컴퓨터로 옮깁니다(USB, 클라우드, 메일 등).

파일에는 앱이 실제로 읽을 문장이 그대로 들어 있습니다. 짧은 문장은 묶여 있고, 발음 사전과 읽기 옵션, 숫자 읽기(`3개` → 세 개)도 적용되어 있습니다.

## 4. 음성 만들기

```bash
.venv-book/bin/python voice-reader/spike/voxcpm_book.py "비 오는 저녁 문장.json" \
  --ref 녹음1.wav \
  --cli llama.cpp-omni/build/bin/voxcpm2-cli \
  --models voxcpm2-gguf
```

- 문장마다 남은 시간을 보여 줍니다. 만든 문장은 `<책 제목> 내 목소리/` 폴더에 쌓입니다.
- 끝나면 `<책 제목> 내 목소리.zip`이 생깁니다.
- **Ctrl+C로 멈추면** 그때까지 만든 문장으로 zip을 만들고 끝납니다. 같은 명령을 다시 실행하면 남은 문장부터 이어서 만들고, zip도 새로 만듭니다.
- 녹음은 wav, m4a 등 아무 형식이나 됩니다. 앞에서 쓸 길이는 `--ref-seconds`로 바꿉니다(기본 13초).
- 노트북이라면 만드는 동안 잠자기에 들지 않게 하세요. 맥은 `caffeinate -i`를 명령 앞에 붙이면 됩니다.

## 5. 앱에 넣기

1. zip 파일을 기기로 옮깁니다.
2. 책을 열고 **설정 → 낭독 → 내 목소리 음성 → 2. 음성 넣기** → 동의 확인 → zip을 고릅니다.
3. **듣기**를 누르면 그 음성으로 읽습니다. 하이라이트, 페이지 넘김, 이전·다음 문장, 속도 조절은 그대로 됩니다.

- 음성이 없는 문장은 고른 목소리(Supertonic이나 기기 음성)로 읽습니다. 중간까지 만든 zip을 먼저 넣어 들어도 됩니다. 나중에 다 만든 zip을 다시 넣으면 바뀝니다.
- 내보낸 뒤 발음 사전이나 읽기 옵션을 바꾸면, 그 영향을 받는 문장은 글이 달라져 고른 목소리로 읽습니다. 다시 내보내 음성을 만들면 됩니다. 이미 만든 문장은 건너뛰므로 바뀐 문장만 새로 만듭니다.
- 속도는 이미 만든 음성을 빠르거나 느리게 재생해 맞춥니다. 목소리 높이는 그대로입니다.
- 지우려면 같은 화면의 **음성 지우기**를 누릅니다. 서재에서 책을 빼도 함께 지워집니다.
