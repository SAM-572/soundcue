package com.soundcue.babycare.domain

/**
 * 비음성 오디오 이벤트에 대한 Gemma 4의 구조화된 care reasoning 결과.
 * 단일 라벨 강제가 아니라 top-k 후보 + confidence + urgency + care hint + abstain 구조.
 */
data class CareEventResult(
    val track: CareTrack,
    val topCandidates: List<NonSpeechCandidate>,
    val selectedEvent: String,           // "baby_cry_general", "unknown_non_speech", "silence", ...
    val selectedConfidence: Float,       // 0..1
    val urgency: Urgency,
    val heard: String,                   // 들은 소리의 literal description
    val careHint: String,                // 부모 행동 힌트
    val action: ToolAction,              // notify_watch / speak_to_baby / log_milestone / request_relisten / none
    val rawJson: String = ""
)

enum class CareTrack { SPEECH, NON_SPEECH, UNKNOWN }

enum class Urgency(val level: Int) {
    LOW(1), MEDIUM(2), HIGH(3);

    companion object {
        fun parse(s: String?): Urgency = when (s?.lowercase()) {
            "high" -> HIGH
            "medium", "med" -> MEDIUM
            else -> LOW
        }
    }
}

data class NonSpeechCandidate(
    val event: String,
    val confidence: Float
)

enum class ToolAction {
    NOTIFY_WATCH,
    SPEAK_TO_BABY,
    LOG_MILESTONE,
    REQUEST_RELISTEN,
    NONE;

    companion object {
        fun parse(s: String?): ToolAction = when (s?.lowercase()) {
            "notify_watch" -> NOTIFY_WATCH
            "speak_to_baby" -> SPEAK_TO_BABY
            "log_milestone" -> LOG_MILESTONE
            "request_relisten" -> REQUEST_RELISTEN
            else -> NONE
        }
    }
}

/**
 * Gemma 입력용 보조 음향 특징. 원시 오디오만 넘기지 않고
 * 같이 제공해서 모델이 더 합리적 추론을 하도록 유도한다.
 */
data class AcousticFeatures(
    val durationMs: Long,
    val rmsEnergy: Float,        // 0..1
    val peakAmplitude: Float,    // 0..1
    val zeroCrossingRate: Float, // 0..1 (samples/total)
    val notes: String = ""
)

/**
 * ConfidenceGate 결정.
 * - strong: 즉시 알림·행동
 * - cautious: 알림 가능하지만 hedge 문구
 * - abstain: 알림 생략, UI에 "확실치 않음" 표시
 * - relisten: 재청취 요청
 */
enum class WordingMode { STRONG, CAUTIOUS, ABSTAIN, RELISTEN }

data class ConfidenceDecision(
    val finalEvent: String,
    val confidence: Float,
    val wordingMode: WordingMode,
    val shouldNotifyWatch: Boolean,
    val shouldRequestRelisten: Boolean,
    val urgency: Urgency
)
