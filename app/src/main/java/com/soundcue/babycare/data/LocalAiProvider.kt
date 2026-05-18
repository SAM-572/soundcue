package com.soundcue.babycare.data

import com.soundcue.babycare.domain.AlertPayload
import com.soundcue.babycare.domain.BabyEventType
import com.soundcue.babycare.domain.EventSeverity
import com.soundcue.babycare.domain.OutputLang
import com.soundcue.babycare.domain.SoundEvent

interface LocalAiProvider {
    suspend fun isAvailable(): Boolean
    suspend fun summarize(event: SoundEvent, lang: OutputLang = OutputLang.KO): AlertPayload
    fun providerName(): String
}

class FakeLocalProvider : LocalAiProvider {
    override suspend fun isAvailable() = true
    override fun providerName() = "Fake fallback"

    override suspend fun summarize(event: SoundEvent, lang: OutputLang): AlertPayload {
        return if (lang == OutputLang.EN) summarizeEn(event) else summarizeKo(event)
    }

    private fun summarizeKo(event: SoundEvent): AlertPayload = when (event.type) {
        BabyEventType.BABY_CRY -> AlertPayload(
            "아기 울음 가능성",
            "아기가 울고 있을 가능성이 높아요. 지금 확인해 주세요.",
            "아기 울음",
            EventSeverity.HIGH,
            providerName()
        )
        BabyEventType.BABY_LAUGH -> AlertPayload(
            "아기 웃음 감지",
            "아기가 웃고 있어요. 기분이 좋아 보입니다.",
            "아기 웃음",
            EventSeverity.LOW,
            providerName()
        )
        BabyEventType.COUGH -> AlertPayload(
            "기침 소리 감지",
            "아기의 기침 소리가 들렸어요. 상태를 확인해 주세요.",
            "기침 감지",
            EventSeverity.MEDIUM,
            providerName()
        )
        BabyEventType.PARENT_CALL -> AlertPayload(
            "아기가 엄마·아빠를 불러요",
            "아이가 \"엄마\" 또는 \"아빠\" 부르는 듯한 소리가 들렸어요.",
            "엄마 불러요",
            EventSeverity.HIGH,
            providerName()
        )
        BabyEventType.FIRST_WORD -> AlertPayload(
            "아기 첫 말 가능성",
            "옹알이나 의미 있는 첫 말일 수 있어요. 가까이 가보세요.",
            "첫 말!",
            EventSeverity.MEDIUM,
            providerName()
        )
        BabyEventType.BURP -> AlertPayload(
            "아기 트림 감지",
            "수유 후 트림이 나왔어요. 다음 자세로 넘어가도 괜찮아요.",
            "트림",
            EventSeverity.LOW,
            providerName()
        )
        BabyEventType.FART -> AlertPayload(
            "아기 방귀 소리",
            "기저귀 상태를 한번 확인해 보세요.",
            "방귀",
            EventSeverity.LOW,
            providerName()
        )
        BabyEventType.HICCUP -> AlertPayload(
            "딸꾹질 감지",
            "잠깐 지속되면 분유·수분을 조금 주세요.",
            "딸꾹질",
            EventSeverity.LOW,
            providerName()
        )
        BabyEventType.LOUD_NOISE -> AlertPayload(
            "큰 소리 감지",
            "예상치 못한 큰 소리가 들렸어요. 아이 주변을 확인해 주세요.",
            "큰 소리",
            EventSeverity.HIGH,
            providerName()
        )
    }

    private fun summarizeEn(event: SoundEvent): AlertPayload = when (event.type) {
        BabyEventType.BABY_CRY -> AlertPayload(
            "Baby may be crying",
            "Your baby is likely crying. Please check on them now.",
            "Baby crying",
            EventSeverity.HIGH,
            providerName()
        )
        BabyEventType.BABY_LAUGH -> AlertPayload(
            "Baby laughing",
            "Your baby sounds happy.",
            "Laughing",
            EventSeverity.LOW,
            providerName()
        )
        BabyEventType.COUGH -> AlertPayload(
            "Cough detected",
            "A cough was detected. Please check your baby.",
            "Cough",
            EventSeverity.MEDIUM,
            providerName()
        )
        BabyEventType.PARENT_CALL -> AlertPayload(
            "Baby is calling you",
            "Your baby may be calling \"mom\" or \"dad\".",
            "Calling you",
            EventSeverity.HIGH,
            providerName()
        )
        BabyEventType.FIRST_WORD -> AlertPayload(
            "Possible first word!",
            "Babbling or a potential first word. Come listen.",
            "First word!",
            EventSeverity.MEDIUM,
            providerName()
        )
        BabyEventType.BURP -> AlertPayload(
            "Burp detected",
            "Baby burped — you can continue feeding.",
            "Burp",
            EventSeverity.LOW,
            providerName()
        )
        BabyEventType.FART -> AlertPayload(
            "Fart detected",
            "You may want to check the diaper.",
            "Fart",
            EventSeverity.LOW,
            providerName()
        )
        BabyEventType.HICCUP -> AlertPayload(
            "Hiccups detected",
            "Offer a little milk or water if persistent.",
            "Hiccups",
            EventSeverity.LOW,
            providerName()
        )
        BabyEventType.LOUD_NOISE -> AlertPayload(
            "Loud noise",
            "Unexpected loud noise. Check around the baby.",
            "Loud noise",
            EventSeverity.HIGH,
            providerName()
        )
    }
}
