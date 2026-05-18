# SoundCue: On-Device Baby Sound Intelligence for Deaf Parents

**Gemma 4 Impact Challenge -- Accessibility Track**

---

## The Problem: When Silence Can Be Dangerous

For the 466 million people worldwide with disabling hearing loss, parenting introduces a unique and constant anxiety: *What if my baby is crying and I can't hear it?*

Existing solutions rely on cloud APIs, meaning they fail precisely when reliability matters most -- during network outages, in rural areas, or when latency is unacceptable. A 3-second cloud round-trip feels like an eternity when your baby is choking.

**SoundCue** solves this by running Gemma 4 E2B entirely on-device, translating baby sounds into contextual, actionable alerts delivered to a phone screen and a smartwatch vibration -- with zero internet dependency.

---

## How It Works

SoundCue is a native Android + Wear OS application built with Kotlin and Jetpack Compose. The core pipeline has three stages:

### Stage 1: Sound Detection (YAMNet)

A TFLite YAMNet model performs continuous on-device audio classification. It listens through the phone microphone using a VAD (Voice Activity Detection) system that captures sound segments only when activity is detected, preserving battery. YAMNet classifies audio into 521 AudioSet categories, which are then mapped to baby-relevant labels: `BABY_CRY`, `BABY_LAUGH`, `BABBLE`, `COUGH`, `BURP`, `HICCUP`, `CRASH_CLANG`, `LOUD_NOISE`, and more.

A `TemporalSmoother` applies sliding-window voting across recent predictions to eliminate flicker from noisy single-frame classifications.

### Stage 2: Contextual Reasoning (Gemma 4 E2B)

This is where Gemma 4 transforms raw classification into *understanding*. Rather than showing "baby_cry detected (0.88)", Gemma 4 E2B generates:

```json
{
  "heard": "rhythmic crying with rising intensity",
  "top_candidates": [
    {"event": "baby_cry_hunger", "confidence": 0.82},
    {"event": "baby_cry_discomfort", "confidence": 0.71}
  ],
  "selected_event": "baby_cry_hunger",
  "urgency": "HIGH",
  "care_hint": "Feeding time may be close. Check if baby is showing hunger cues."
}
```

**Why Gemma 4, not just rules?** Because Gemma reasons about context:
- It differentiates *hunger cries* from *discomfort cries* based on acoustic feature descriptions
- It generates care hints personalized to the baby's age and parent's preferred tone
- It produces natural Korean (or English) sentences for the watch gesture-to-speech feature
- It ranks which parent gesture would be most helpful given recent events

A rule engine would need hundreds of hand-crafted branches. Gemma handles this with a single prompt.

### Stage 3: Multi-Device Alert Delivery

Alerts flow to three outputs simultaneously:
1. **Phone UI** -- large, high-contrast cards designed for accessibility (big text, color-coded severity, care suggestions)
2. **Phone Notification** -- Android NotificationCompat with severity-based vibration patterns
3. **Galaxy Watch 7** -- via Wearable Data Layer MessageClient, delivering short watch-optimized text + haptic vibration patterns (2 short pulses for baby events, 3 strong pulses for high-urgency)

---

## Architecture

```
Microphone
    |
   VAD (Voice Activity Detection)
    |
  YAMNet TFLite (521-class audio classifier)
    |
  BabySoundPipeline (label mapping + temporal smoothing)
    |
  Gemma 4 E2B via LiteRT-LM (contextual care reasoning)
    |
  +--> Phone Card UI (Jetpack Compose)
  +--> Android Notification --> Watch Bridge
  +--> Watch Wearable MessageClient --> Haptic + Display
```

Key design decisions:

- **Gemma 4 E2B over E4B**: Our task is structured JSON generation from short inputs, not deep chain-of-thought reasoning. E2B is 3x faster, uses half the memory (~2GB vs ~4GB), and produces sufficient JSON compliance for our prompts. On a Galaxy Z Flip 6, inference completes in ~800ms.
- **LiteRT-LM over AICore/MediaPipe**: AICore Prompt API was unavailable on our Flip 6 test device. MediaPipe LLM Inference is deprecated. LiteRT-LM provided the most reliable on-device path with GPU acceleration (CPU fallback if GPU init fails).
- **YAMNet + Gemma dual-model**: YAMNet handles the "what sound is this?" question efficiently (real-time classification). Gemma handles the "what does this mean for the parent?" question intelligently. This separation keeps battery usage low while maximizing reasoning quality.

---

## Gemma 4 Integration Details

The `GemmaLiteRtProvider` manages the full Gemma lifecycle:

1. **Initialization**: GPU-first with CPU fallback. Model file (`gemma4-e2b.litertlm`, ~2.58GB) is loaded from external storage via `adb push`.
2. **Prompt engineering**: System prompts enforce JSON-only output, Korean language, hedged/cautious phrasing ("possibility" not "confirmed"), and character limits for watch text.
3. **Streaming response**: LiteRT-LM's async streaming API collects chunks into a buffer, then a robust JSON parser strips code fences, extracts the first `{...}` block, and validates required fields.
4. **Confidence gating**: A `ConfidenceGate` examines Gemma's output confidence and urgency to decide whether to alert (`CONFIDENT`), use cautious wording (`CAUTIOUS`), re-listen (`RELISTEN`), or abstain entirely (`ABSTAIN`). This prevents alert fatigue from low-confidence detections.
5. **Judge Mode**: For hackathon submission, `judgeMode = true` disables the Fake fallback. If Gemma fails, the error is shown transparently in the UI rather than silently substituted.

Gemma 4 is also used for two additional features:
- **Gesture-to-Speech**: Watch gestures (e.g., "hungry", "hurt", "mom_here") are sent to the phone, where Gemma generates a contextually appropriate spoken sentence (e.g., after a crying event: "It's okay, mommy is right here"), played through the phone speaker via TTS.
- **Proactive suggestion**: After each event, Gemma ranks available watch gestures by relevance (e.g., after crying -> "call_name" ranks first; after laughing -> "well_done" ranks first).

---

## Challenges Overcome

1. **LiteRT-LM GPU instability**: GPU initialization fails intermittently on certain devices. We implemented automatic CPU fallback with UI status indication so the user always knows which backend is active.

2. **JSON compliance**: Gemma occasionally wraps responses in markdown code fences or adds explanatory text. Our parser handles fence stripping, brace extraction, and field validation with graceful degradation.

3. **Alert fatigue**: Without gating, every detected sound triggers an alert. The `ConfidenceGate` + `TemporalSmoother` + cooldown timer combination ensures parents only receive meaningful, actionable notifications.

4. **YAMNet adult speech suppression**: YAMNet's "Speech" class fires on all human vocalizations, drowning out baby-specific detections. We apply a 70% confidence suppression factor to `ADULT_SPEECH` when any baby-related label is present.

5. **Watch communication reliability**: Wearable MessageClient can silently fail. SoundCue detects delivery failure and falls back to phone-speaker TTS for gesture-to-speech, ensuring the feature always works.

---

## Safety Design

- All alert text uses hedged language ("possibility", "may be") -- never definitive claims
- Repeated crying (3+ in recent history) triggers a safety escalation alert suggesting the parent check temperature, diaper, and pain signs
- A persistent disclaimer reminds users this is an assistive tool, not a medical device
- Night mode reduces TTS pitch and speed to avoid startling a sleeping baby

---

## Beyond Monitoring: Giving Parents a Voice

SoundCue is not just a baby monitor. For parents who are both deaf and speech-impaired, the greatest fear isn't missing a cry -- it's that their child will grow up without hearing a parent's voice. They worry: "Will my child struggle with language development because of me?"

**Sign-to-Speech** addresses this directly. The parent performs sign language gestures on the Galaxy Watch. Gemma 4 interprets the gesture context -- combining the sign with the baby's current state (crying? calm? just fed?) -- and generates a natural spoken phrase via TTS through the phone speaker.

A parent signs "comfort" -> Gemma generates "It's okay, sweetie. Mommy is right here." -- not a dictionary lookup, but a contextual, warm sentence that adapts to the baby's name, age, and current situation. The same sign during a cry event produces a different phrase than during calm play.

This transforms the watch from an alert receiver into a **voice prosthetic** -- letting speech-impaired parents speak to their babies in natural language, supporting early language development during the critical 0-3 year window.

---

## Our Vision

Ultimately, we want SoundCue to detect the moment a baby says "mama" or "papa" for the first time. Deaf parents will never hear that sound -- but they deserve to feel that moment. A vibration on the wrist, a message on the screen: "Your baby just called you Mom." That is the future we are building toward.

---

## Impact

SoundCue demonstrates that on-device AI can bridge a critical accessibility gap. By running entirely offline on a consumer smartphone, it works in any environment -- no Wi-Fi, no cloud dependency, no privacy concerns from streaming baby audio to external servers.

The same architecture generalizes to driving mode (siren/horn detection), disaster mode (gunshot/explosion/scream-like sounds), and daily life mode (doorbell, fire alarm) -- all designed and stubbed in the app, ready for future expansion.

**One sentence**: SoundCue turns sounds deaf parents cannot hear into understanding they can act on, powered by Gemma 4 running entirely in their pocket.

---

## Technical Stack

| Component | Technology |
|---|---|
| Language | Kotlin 2.0, Jetpack Compose |
| On-device LLM | Gemma 4 E2B via LiteRT-LM (GPU/CPU) |
| Audio classification | YAMNet (TFLite, 521-class AudioSet) |
| Watch communication | Wearable Data Layer MessageClient |
| Target devices | Galaxy Z Flip 6 + Galaxy Watch 7 Ultra |
| Min SDK | Android 8.0 (API 26) |

---

## Links

- **Code Repository**: [GitHub - SoundCue](https://github.com/SAM-572/soundcue)
- **Video Demo**: [YouTube](https://youtube.com/YOUR_VIDEO_HERE)
- **Live Demo**: [APK Downloads](https://github.com/SAM-572/soundcue/releases/tag/v1.0.0)

---

*Built for the Gemma 4 Impact Challenge. All inference runs on-device. No cloud APIs were used for sound detection or reasoning.*
