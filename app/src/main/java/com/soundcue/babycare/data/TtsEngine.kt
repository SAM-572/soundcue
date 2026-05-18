package com.soundcue.babycare.data

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.soundcue.babycare.domain.OutputLang
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Android TextToSpeech 래퍼.
 * 제스처 → Gemma 문장 생성 → 이 엔진을 통해 폰 스피커로 재생.
 *
 * 해커톤 데모에서는 청각장애 부모가 손가락으로 간단 제스처(예: "사랑해") 하면
 * 워치가 감지 → 폰 Gemma 가 영유아 말투 문장 생성 → 폰 스피커로 아이에게 들려줌.
 */
class TtsEngine(private val appContext: Context) {

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready: Boolean = false
    @Volatile private var currentLang: OutputLang = OutputLang.KO

    suspend fun init(lang: OutputLang = OutputLang.KO): Boolean =
        suspendCancellableCoroutine { cont ->
            currentLang = lang
            // 시스템 기본 TTS 엔진 사용 (폰 설정에서 Samsung TTS 선택하면 그걸 씀)
            val engine = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    val engineName = tts?.defaultEngine ?: "unknown"
                    Log.i(TAG, "TTS OK, engine=$engineName")
                    applyLocale(lang)
                    // 초기화 직후 부모 성별 음성 적용
                    applyVoiceForParent(parentTitle)
                } else {
                    Log.e(TAG, "TTS init failed: $status")
                }
                if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS)
            }
            tts = engine
        }

    fun setLanguage(lang: OutputLang) {
        currentLang = lang
        applyLocale(lang)
    }

    var parentTitle: String = "엄마"

    private fun applyLocale(lang: OutputLang) {
        val locale = if (lang == OutputLang.EN) Locale.US else Locale.KOREAN
        tts?.language = locale
    }

    /** 부모 성별에 맞는 음성 설정. 가능하면 남성/여성 Voice 직접 선택. */
    fun applyVoiceForParent(parent: String) {
        parentTitle = parent
        val t = tts ?: return
        if (!ready) return

        // 사용 가능한 Voice 목록에서 한국어 + 성별 매칭 시도
        runCatching {
            val voices = t.voices ?: return
            val targetGender = if (parent == "아빠") "male" else "female"
            val koVoices = voices.filter { v ->
                v.locale.language == "ko" && !v.isNetworkConnectionRequired
            }

            Log.i(TAG, "Available Korean voices: ${koVoices.map { "${it.name} (${it.features})" }}")

            // 성별 키워드로 필터 (Samsung TTS 는 이름에 male/female 포함하는 경우 있음)
            val genderMatch = koVoices.firstOrNull { v ->
                v.name.lowercase().contains(targetGender) ||
                    v.features?.contains(targetGender) == true
            }

            if (genderMatch != null) {
                t.voice = genderMatch
                Log.i(TAG, "Selected voice: ${genderMatch.name} for $parent")
                return
            }

            // 성별 매칭 안 되면 품질 높은 한국어 음성이라도 선택
            val bestKo = koVoices.maxByOrNull { it.quality }
            if (bestKo != null) {
                t.voice = bestKo
                Log.i(TAG, "Selected best Korean voice: ${bestKo.name} (gender match unavailable)")
            }
        }.onFailure {
            Log.w(TAG, "Voice selection failed: ${it.message}")
        }
    }

    private fun parentPitch(): Float = if (parentTitle == "아빠") 0.75f else 1.0f

    fun speak(text: String, utteranceId: String = "soundcue_${System.currentTimeMillis()}") {
        val t = tts ?: return
        if (!ready) return
        val clean = cleanForTts(text)
        t.setPitch(parentPitch())
        t.setSpeechRate(1.0f)
        t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun speakWithTone(
        text: String,
        pitch: Float = 1.0f,
        speed: Float = 1.0f,
        utteranceId: String = "soundcue_${System.currentTimeMillis()}"
    ) {
        val t = tts ?: return
        if (!ready) return
        val clean = cleanForTts(text)
        // 야간 피치 조정에 부모 성별 피치 곱함
        val finalPitch = (pitch * parentPitch()).coerceIn(0.5f, 2.0f)
        t.setPitch(finalPitch)
        t.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
        t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** TTS가 읽으면 안 되는 특수문자 정리 */
    private fun cleanForTts(text: String): String {
        return text
            .replace("~", "")
            .replace("～", "")
            .replace("♥", "")
            .replace("♡", "")
            .replace("🎉", "")
            .replace("💡", "")
            .replace("⚠", "")
            .replace("*", "")
            .replace("#", "")
            .replace("```", "")
            .replace("\"", "")
            .replace(Regex("[\\p{So}\\p{Sk}]"), "") // 이모지·기호 전체 제거
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private var mediaPlayer: MediaPlayer? = null

    /** 퀵 버튼용: 사전 녹음 MP3 있으면 재생, 없으면 시스템 TTS */
    fun speakQuick(label: String, fallbackText: String) {
        val gender = if (parentTitle == "아빠") "male" else "female"
        val assetPath = "voice/$gender/$label.mp3"
        val played = runCatching {
            val afd: AssetFileDescriptor = appContext.assets.openFd(assetPath)
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                start()
            }
            true
        }.getOrDefault(false)
        if (!played) speak(fallbackText)
    }

    fun stop() {
        runCatching { tts?.stop() }
        runCatching { mediaPlayer?.release() }
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "TtsEngine"
    }
}
