package com.soundcue.babycare.wear.gesture

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 제스처 한 개의 IMU 시계열 템플릿.
 * features: [accelMagnitude, gyroMagnitude] 를 시간축에 따라 N 샘플 정규화 저장.
 */
data class GestureTemplate(
    val label: String,
    val samples: List<FloatArray>  // 각 element: [accelMag, gyroMag]
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label)
        put("samples", JSONArray().also { arr ->
            for (s in samples) {
                arr.put(JSONArray().apply {
                    for (v in s) put(v.toDouble())
                })
            }
        })
    }

    companion object {
        fun fromJson(obj: JSONObject): GestureTemplate {
            val label = obj.getString("label")
            val samplesJson = obj.getJSONArray("samples")
            val samples = mutableListOf<FloatArray>()
            for (i in 0 until samplesJson.length()) {
                val row = samplesJson.getJSONArray(i)
                val arr = FloatArray(row.length())
                for (j in 0 until row.length()) arr[j] = row.getDouble(j).toFloat()
                samples.add(arr)
            }
            return GestureTemplate(label, samples)
        }
    }
}

/**
 * 미리 정의된 제스처 라벨. 사용자는 이 라벨에 대해 각각 3번씩 녹화.
 * 워치 라벨(짧은 영문 코드) ↔ 폰에서 한국어 문장은 Gemma 4 가 생성.
 *
 * 의도된 맥락:
 * - 폰 알림: "아기가 울어요", "아기가 웃어요", "트림했어요", "방귀 뀌었어요", "엄마를 불러요"
 * - 부모 제스처: 아래 라벨 중 하나
 * - Gemma 4: 최근 알림 + 제스처 조합 → 영유아 말투 문장 생성
 */
object GestureCatalog {
    val DEFAULT: List<String> = listOf(
        "lullaby",    // 자장가 — 검지로 Z 제스처 → 자장가 재생
        "call_name",  // "하은아~ 하은아~" — 아이 이름 부르기 (언어발달 핵심)
        "name_here",  // "하은아, 엄마 여깄어. 하은아, 엄마 왔어."
        "hungry",     // "아가 배고프니?" / "우리 아기 맘마 먹자"
        "awake",      // "아가 일어났니?" / "잘 잤어?"
        "hurt",       // "왜 그래? 어디 아파?"
        "well_done",  // "아이 잘했어" / "우리 아기 최고야"
        "love",       // "사랑해" / "엄마가 사랑해"
        "mom_here",   // "엄마야. 엄마 여깄어."
        "dad_here",   // "아빠야. 아빠 여깄어."
        "wait"        // "잠깐만, 엄마 바로 갈게"
    )

    val DISPLAY_KO: Map<String, String> = mapOf(
        "lullaby" to "🎵 자장가",
        "call_name" to "📣 이름 부르기",
        "name_here" to "🏠 OO아, 엄마/아빠 왔어",
        "hungry" to "🍼 배고프니?",
        "awake" to "👀 일어났니?",
        "hurt" to "🤕 어디 아파?",
        "well_done" to "👍 잘했어",
        "love" to "❤️ 사랑해",
        "mom_here" to "💖 엄마 여깄어",
        "dad_here" to "👨 아빠 여깄어",
        "wait" to "✋ 잠깐만"
    )

    val DISPLAY_EN: Map<String, String> = mapOf(
        "lullaby" to "🎵 Lullaby",
        "call_name" to "📣 Call Name",
        "name_here" to "🏠 Mommy/Daddy is here",
        "hungry" to "🍼 Are you hungry?",
        "awake" to "👀 Are you awake?",
        "hurt" to "🤕 Does it hurt?",
        "well_done" to "👍 Well done!",
        "love" to "❤️ I love you",
        "mom_here" to "💖 Mommy is here",
        "dad_here" to "👨 Daddy is here",
        "wait" to "✋ Wait a moment"
    )

    private var prefs: SharedPreferences? = null
    private const val PREFS_NAME = "soundcue_wear"
    private const val KEY_USE_ENGLISH = "use_english"

    private val _useEnglish = MutableStateFlow(false)
    val useEnglishFlow: StateFlow<Boolean> = _useEnglish

    /** Activity.onCreate에서 호출하여 SharedPreferences 초기화 + 저장값 복원 */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _useEnglish.value = prefs?.getBoolean(KEY_USE_ENGLISH, false) ?: false
    }

    var useEnglish: Boolean
        get() = _useEnglish.value
        set(value) {
            _useEnglish.value = value
            prefs?.edit()?.putBoolean(KEY_USE_ENGLISH, value)?.apply()
        }

    val DISPLAY: Map<String, String>
        get() = if (useEnglish) DISPLAY_EN else DISPLAY_KO
}
