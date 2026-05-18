# SoundCue — Flip 6 + Watch 7 Ultra · Gemma 4 E2B 온디바이스 데모

> 해커톤 심사 제출용. 실제 **Gemma 4 E2B** 모델을 Flip 6에서 **LiteRT-LM**으로 로컬 실행하고, 결과를 Watch 7 Ultra로 브리지.

---

## 모델 선택 근거 (Why Gemma 4 E2B)

> **"제품의 핵심 가치가 '즉각 반응'이기 때문입니다. 청각장애 부모에게 3초는 길고, E2B는 그 요구를 충족하는 최소 Gemma 4 변형입니다. 작업이 깊은 추론이 아닌 구조화된 텍스트 생성이라 E4B의 추론 능력은 과잉입니다."**

### 선택 기준 비교

| 항목 | E2B (채택) | E4B | 26B MoE / 31B Dense |
|---|---|---|---|
| 추론 속도 | **3배 빠름** | 느림 | 서버급, 모바일 불가 |
| 모델 크기 | ~2GB | ~4GB | 10GB+ |
| Flip 6 실기 동작 | **가능** | 가능 (발열·지연) | 불가 |
| JSON 생성 품질 | 충분 | 과잉 | 과잉 |
| 배터리 영향 | 낮음 | 중간 | 해당 없음 |

### SoundCue 작업 성격

- **입력**: `event=baby_cry, confidence=0.88` — 짧은 구조화된 텍스트
- **출력**: `{"title":"...","body":"...","watchText":"...","severity":"..."}` — 4개 짧은 한글 문자열
- 깊은 추론·장문 생성·체인 오브 쏘트 **불필요**
- 필요한 능력: **구조 준수 + 한국어 자연스러움 + 1~2초 응답**

→ E2B 설계 목적과 정확히 일치. E4B는 "가능성 중심 경고 문구" 같은 정성 요구를 더 잘 지키지만, 현재 프롬프트 엔지니어링으로 E2B도 충분히 커버됨.

### 향후 확장 여지

본 프로토타입은 모델 경로만 교체하면 E4B로 즉시 스왑 가능한 구조입니다 (`GemmaLiteRtProvider.MODEL_FILE` 상수 변경). 감정 해석·아기 상태 추정 같은 **고난도 추론 레이어**가 추가될 때 E4B로 승격을 고려합니다.

---

## 핵심 동작 방식

- **LiteRT-LM** 기반 온디바이스 추론 (MediaPipe LLM Inference는 deprecated, AICore Prompt API는 Flip 6 미지원이라 채택 안 함)
- Gemma 4 E2B `.litertlm` 모델을 **앱 외부 파일 경로**에 두고 `adb push`로 배치 (APK 번들 X)
- 앱 시작 시 **GPU 우선 → 실패 시 CPU 재시도**로 Engine 초기화
- 버튼 시뮬레이터 이벤트 → Gemma 4 E2B → JSON 응답 파싱 → 폰 카드 UI + Watch 진동 알림
- **Judge Mode**: Gemma 실패 시 Fake로 숨기지 않고 UI에 오류 표시

---

## 빌드 요구사항

- Android Studio **Ladybug (2024.2)** 이상
- JDK 17 (Ladybug 번들 JDK 21도 호환)
- AGP 8.5.2 · Kotlin 2.0.0 · Compose BOM 2024.09.02 · Gradle 8.9
- minSdk 26 / targetSdk 34

## 실기 요구사항

- **Galaxy Z Flip 6** (Android 14 / One UI 6.1)
- **Galaxy Watch 7 Ultra** (Wear OS 5, Flip 6와 페어링, 알림 브리지 ON)
- 저장 공간 최소 **3GB 여유** (Gemma 4 E2B 모델 약 2.58GB)

---

## Gemma 4 E2B 모델 배치 (필수)

### 1. 모델 파일 구하기
- Kaggle: `https://www.kaggle.com/models/google/gemma-4` → LiteRT 탭 → `gemma4-e2b-it.litertlm` 다운로드
- 또는 HuggingFace `litert-community/Gemma4-E2B-IT` 레포에서 `.litertlm` 파일
- 파일 크기 약 2.58GB

### 2. Flip 6에 푸시 (adb)

앱이 한 번이라도 실행돼야 `getExternalFilesDir` 경로가 생성됩니다. 먼저 앱 설치·실행(초기화 실패 예상) → 그 다음 모델을 푸시:

```bash
# 1) 폰에 경로 생성 (앱 최초 실행 후 자동 생성됨)
adb shell mkdir -p /sdcard/Android/data/com.soundcue.babycare/files/models

# 2) 모델 파일 푸시 (2~3분 소요)
adb push gemma4-e2b-it.litertlm /sdcard/Android/data/com.soundcue.babycare/files/models/gemma4-e2b.litertlm
```

파일명은 정확히 **`gemma4-e2b.litertlm`**이어야 합니다 (`data/GemmaLiteRtProvider.kt`의 `MODEL_FILE` 상수).

### 3. 앱 재실행
앱을 강제 종료한 뒤 다시 실행 → 홈 화면 상태 카드에 `Gemma 4 E2B · GPU · READY` 표시되면 성공.

---

## 실행 순서

1. Android Studio에서 `SoundCue/` 폴더 열기
2. Gradle Sync 완료 대기
3. Flip 6 USB 디버깅 ON + 연결
4. Run ▶ → 앱 설치, 최초 실행 시 `Model not found` 상태 정상
5. 위 **모델 배치** 절차 수행
6. 앱 재실행 → 상태 `READY` 확인
7. 육아 모드 카드 → 데모 버튼 탭 → 추론·알림·워치 진동 검증

### 정상 동작 시 보이는 것

**홈 화면 하단 AI Engine 카드**:
```
Local AI Engine                         [READY]
Gemma 4 E2B
Backend         GPU
Model           gemma4-e2b.litertlm
Last latency    842 ms
```

**육아 대시보드 상단 뱃지**:
```
Gemma 4 · GPU
최근 추론: 842 ms
```

---

## 문제 해결

| 증상 | 원인 | 해결 |
|---|---|---|
| `Model not found at ...` | 모델 파일 미배치 | 위 adb push 절차 수행 |
| `GPU init failed: ...` → CPU 자동 전환 | GPU OpenCL 초기화 실패 | 정상. CPU로도 동작 (응답 느려짐 3~5배) |
| `Both GPU and CPU backends failed` | LiteRT-LM 의존성 문제 or 기기 아키텍처 불일치 | Logcat `GemmaLiteRt` 태그 확인 |
| `JSON parse failed` | Gemma가 설명문·코드펜스 붙여서 응답 | 프롬프트 튜닝 필요. 현재 파서는 자동 fence 제거 + 중괄호 추출 보강 |
| Watch에 알림 미도착 | Galaxy Wearable 앱 SoundCue 알림 OFF | 앱 알림 ON 설정 |

---

## Judge Mode vs Debug Mode

`presentation/MainViewModel.kt`의 `judgeMode` 상수:

- `true` (기본): 심사 제출용. Gemma 실패 시 Fake로 대체하지 않고 UI에 오류 명시
- `false`: 개발/데모 안정성용. Gemma 실패 시 `FakeLocalProvider`로 폴백

해커톤 영상 촬영·제출 빌드는 반드시 `true` 유지.

---

## 프로젝트 구조

```
SoundCue/
├── build.gradle.kts, settings.gradle.kts, gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts                # LiteRT-LM 의존성 포함
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml         # GPU용 native-library 2종 포함
        ├── java/com/soundcue/babycare/
        │   ├── MainActivity.kt
        │   ├── domain/Models.kt
        │   ├── data/
        │   │   ├── LocalAiProvider.kt          # 인터페이스 + FakeLocalProvider
        │   │   ├── GemmaLiteRtProvider.kt      # ★ 실제 Gemma 4 E2B 호출
        │   │   └── AlertNotifier.kt
        │   ├── presentation/
        │   │   └── MainViewModel.kt            # AiRuntimeState 포함
        │   └── ui/
        │       ├── theme/Theme.kt
        │       ├── components/ (ModeCard, EventCard)
        │       └── screens/ (Home, BabyDashboard,
        │                      GestureSpeak, Profile, Report)
        └── res/
wear/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/soundcue/babycare/wear/
    │   ├── MainActivity.kt
    │   ├── AlertListenerService.kt
    │   ├── AlertState.kt
    │   ├── GestureBridge.kt
    │   ├── WatchTts.kt
    │   └── gesture/
    │       ├── GestureRecorder.kt
    │       ├── GestureMatcher.kt
    │       ├── GestureModel.kt
    │       ├── GestureStore.kt
    │       └── GestureViewModel.kt
    └── res/
```

---

## 참고

- [LiteRT-LM Android Kotlin guide](https://ai.google.dev/edge/litert-lm/android)
- [LiteRT-LM overview](https://ai.google.dev/edge/litert-lm/overview)
- [MediaPipe LLM Inference deprecation](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference)
- [Bring state-of-the-art agentic skills to the edge with Gemma 4](https://developers.googleblog.com/bring-state-of-the-art-agentic-skills-to-the-edge-with-gemma-4/)
