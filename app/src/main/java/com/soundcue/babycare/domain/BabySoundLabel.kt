package com.soundcue.babycare.domain

/**
 * ML 모델 기반 아기 소리 분류 라벨.
 * YAMNet 1차 분류 + Gemma babble 2차 분류 결과를 이 enum으로 통합한다.
 */
enum class BabySoundLabel(
    val tag: String,
    val displayKo: String,
    val displayEn: String,
    val defaultUrgency: Urgency
) {
    BABY_CRY(
        "baby_cry", "아기 울음", "Baby crying",
        Urgency.HIGH
    ),
    BABY_LAUGH(
        "baby_laugh", "아기 웃음", "Baby laughing",
        Urgency.LOW
    ),
    BABBLE_MAMA(
        "babble_mama", "맘마 옹알이", "Babble: mama",
        Urgency.MEDIUM
    ),
    BABBLE_BABA(
        "babble_baba", "빠빠 옹알이", "Babble: baba/papa",
        Urgency.MEDIUM
    ),
    BABBLE_GENERIC(
        "babble_generic", "옹알이", "Babbling",
        Urgency.LOW
    ),
    CRASH_CLANG(
        "crash_clang", "쩅그랑/깨지는 소리", "Crash/clang",
        Urgency.HIGH
    ),
    COUGH(
        "cough", "기침", "Cough",
        Urgency.MEDIUM
    ),
    BURP(
        "burp", "트림", "Burp",
        Urgency.LOW
    ),
    HICCUP(
        "hiccup", "딸꾹질", "Hiccup",
        Urgency.LOW
    ),
    FART(
        "fart", "방귀", "Fart",
        Urgency.LOW
    ),
    ADULT_SPEECH(
        "adult_speech", "어른 말소리", "Adult speech",
        Urgency.LOW
    ),
    LOUD_NOISE(
        "loud_noise", "큰 소리", "Loud noise",
        Urgency.HIGH
    ),
    SILENCE(
        "silence", "조용함", "Silence",
        Urgency.LOW
    ),
    OTHER(
        "other", "기타 소리", "Other sound",
        Urgency.LOW
    );

    fun display(lang: OutputLang): String =
        if (lang == OutputLang.EN) displayEn else displayKo

    companion object {
        fun fromTag(tag: String): BabySoundLabel =
            entries.firstOrNull { it.tag == tag } ?: OTHER
    }
}

/**
 * ML 분류기의 단일 예측 결과.
 */
data class SoundPrediction(
    val label: BabySoundLabel,
    val confidence: Float,
    val rawYamNetLabel: String = ""
)

/**
 * 전체 파이프라인 분류 결과.
 * YAMNet 1차 + (옵션) Gemma babble 2차 + temporal smoothing 적용 후 최종.
 */
data class BabySoundResult(
    val primary: SoundPrediction,
    val topK: List<SoundPrediction>,
    val isBabbleRefined: Boolean = false,
    val clipDurationMs: Long = 0L,
    val inferenceTimeMs: Long = 0L
)
