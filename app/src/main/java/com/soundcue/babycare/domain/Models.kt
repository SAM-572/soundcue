package com.soundcue.babycare.domain

enum class AppMode(val displayName: String, val enabled: Boolean) {
    BABYCARE("육아 모드", true),
    DRIVE("운전 모드", false),
    DAILY("실생활 모드", false),
    DISASTER("재난 모드", false)
}

enum class EventSeverity { LOW, MEDIUM, HIGH }

enum class OutputLang(val code: String, val displayName: String) {
    KO("ko", "한국어"),
    EN("en", "English")
}

enum class BabyEventType(
    val tag: String,
    val labelKo: String,
    val labelEn: String
) {
    BABY_CRY("baby_cry", "아기 울음", "Baby crying"),
    BABY_LAUGH("baby_laugh", "아기 웃음", "Baby laughing"),
    COUGH("cough", "기침", "Cough"),
    PARENT_CALL("parent_call", "아기가 엄마·아빠 부름", "Baby calling mom/dad"),
    FIRST_WORD("first_word", "아기 첫 말 순간", "Baby first words"),
    BURP("burp", "아기 트림", "Burp"),
    FART("fart", "아기 방귀", "Fart"),
    HICCUP("hiccup", "딸꾹질", "Hiccup"),
    LOUD_NOISE("loud_noise", "큰 소리", "Loud noise");

    // Backward-compat
    val label: String get() = labelKo

    fun label(lang: OutputLang) = if (lang == OutputLang.EN) labelEn else labelKo
}

data class SoundEvent(
    val id: String,
    val type: BabyEventType,
    val confidence: Float,
    val timestamp: Long
)

data class AlertPayload(
    val title: String,
    val body: String,
    val watchText: String,
    val severity: EventSeverity,
    val inferenceSource: String,
    val subtype: String? = null,
    val reasoning: String? = null,
    val suggestion: String? = null
)
