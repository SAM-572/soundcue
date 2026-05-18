package com.soundcue.babycare.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class Profile(
    val babyName: String = "",
    val babyBirthDate: String = "",       // "2025-10-15"
    val parentTitle: String = "엄마",      // "엄마" or "아빠"
    val toneStyle: ToneStyle = ToneStyle.WARM,
    val nightStart: Int = 22,              // 22시
    val nightEnd: Int = 6,                 // 06시
    val feedingIntervalHours: Int = 3
) {
    val babyMonths: Int get() {
        if (babyBirthDate.isBlank()) return 0
        return runCatching {
            val birth = LocalDate.parse(babyBirthDate)
            ChronoUnit.MONTHS.between(birth, LocalDate.now()).toInt()
        }.getOrDefault(0)
    }

    val babyDays: Int get() {
        if (babyBirthDate.isBlank()) return 0
        return runCatching {
            val birth = LocalDate.parse(babyBirthDate)
            ChronoUnit.DAYS.between(birth, LocalDate.now()).toInt()
        }.getOrDefault(0)
    }

    val isNightMode: Boolean get() {
        val hour = LocalTime.now().hour
        return if (nightStart > nightEnd) {
            hour >= nightStart || hour < nightEnd
        } else {
            hour in nightStart until nightEnd
        }
    }

    val timeOfDayLabel: String get() {
        val hour = LocalTime.now().hour
        return when {
            hour in 6..11 -> "morning"
            hour in 12..17 -> "afternoon"
            hour in 18..21 -> "evening"
            else -> "night"
        }
    }

    /** Gemma 프롬프트에 삽입할 컨텍스트 블록 */
    fun toPromptContext(lang: String = "ko"): String {
        val name = babyName.ifBlank { if (lang == "en") "baby" else "아기" }
        val months = babyMonths
        val parent = parentTitle
        val tone = toneStyle
        val time = timeOfDayLabel
        val night = isNightMode

        return if (lang == "en") """
            Baby name: $name (${months} months old)
            Speaking as: $parent
            Tone style: ${tone.enDesc}
            Time: $time ${if (night) "(NIGHT MODE — whisper, minimal, gentle)" else ""}
        """.trimIndent() else """
            아기 이름: $name (${months}개월)
            말하는 사람: $parent
            말투: ${tone.koDesc}
            시간대: $time ${if (night) "(야간 — 속삭이듯, 짧게, 자극 최소)" else ""}
        """.trimIndent()
    }
}

enum class ToneStyle(val koDesc: String, val enDesc: String) {
    WARM("다정하고 따뜻한", "warm and affectionate"),
    CALM("차분하고 안정적인", "calm and steady"),
    BRIGHT("밝고 활기찬", "bright and cheerful")
}

class UserProfileStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("soundcue_profile", Context.MODE_PRIVATE)

    private val _profile = MutableStateFlow(load())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    fun update(block: Profile.() -> Profile) {
        val new = _profile.value.block()
        _profile.value = new
        save(new)
    }

    private fun load(): Profile = Profile(
        babyName = prefs.getString("baby_name", "") ?: "",
        babyBirthDate = prefs.getString("baby_birth", "") ?: "",
        parentTitle = prefs.getString("parent_title", "엄마") ?: "엄마",
        toneStyle = ToneStyle.entries.firstOrNull {
            it.name == prefs.getString("tone", "WARM")
        } ?: ToneStyle.WARM,
        nightStart = prefs.getInt("night_start", 22),
        nightEnd = prefs.getInt("night_end", 6),
        feedingIntervalHours = prefs.getInt("feed_interval", 3)
    )

    private fun save(p: Profile) {
        prefs.edit()
            .putString("baby_name", p.babyName)
            .putString("baby_birth", p.babyBirthDate)
            .putString("parent_title", p.parentTitle)
            .putString("tone", p.toneStyle.name)
            .putInt("night_start", p.nightStart)
            .putInt("night_end", p.nightEnd)
            .putInt("feed_interval", p.feedingIntervalHours)
            .apply()
    }
}
