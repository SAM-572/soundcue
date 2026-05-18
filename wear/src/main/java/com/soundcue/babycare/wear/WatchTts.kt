package com.soundcue.babycare.wear

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.soundcue.babycare.wear.gesture.GestureCatalog
import androidx.compose.runtime.mutableStateOf
import java.util.Locale

object WatchTts {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var appContext: Context? = null
    private var mediaPlayer: MediaPlayer? = null

    fun init(context: Context) {
        if (tts != null) return
        appContext = context.applicationContext
        val ctx = context.applicationContext
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                ready = true
                Log.i("WatchTts", "TTS ready, engine=${tts?.defaultEngine}")
            } else {
                Log.e("WatchTts", "TTS init failed: $status")
            }
        }
    }

    /**
     * 퀵 버튼용: 사전 녹음 MP3 있으면 재생, 없으면 시스템 TTS 폴백.
     * Gemma 동적 문장은 speak() 으로 시스템 TTS.
     */
    fun speakQuick(label: String) {
        val ctx = appContext ?: run { speak(quickPhrase(label)); return }
        val gender = if (parentTitle == "아빠") "male" else "female"
        val assetPath = "voice/$gender/$label.mp3"

        val played = runCatching {
            val afd: AssetFileDescriptor = ctx.assets.openFd(assetPath)
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                start()
            }
            Log.i("WatchTts", "Playing MP3: $assetPath")
            true
        }.getOrElse {
            Log.w("WatchTts", "No MP3 for $assetPath, fallback to TTS")
            false
        }

        if (!played) speak(quickPhrase(label))
    }

    fun speak(text: String) {
        if (!ready) {
            Log.w("WatchTts", "TTS not ready, skip: $text")
            return
        }
        val clean = cleanForTts(text)
        // 영어모드면 영어 로케일, 아빠면 낮은 피치
        tts?.language = if (GestureCatalog.useEnglish) Locale.ENGLISH else Locale.KOREAN
        val pitch = if (parentTitle == "아빠" || parentTitle == "Daddy") 0.7f else 1.0f
        tts?.setPitch(pitch)

        // 자장가 재생 중이면 일시정지 → TTS 완료 후 재개
        val wasLullaby = lullabyPlaying && lullabyPlayer != null
        if (wasLullaby) {
            runCatching { lullabyPlayer?.pause() }
        }

        val uttId = "watch_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (wasLullaby) runCatching { lullabyPlayer?.start() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (wasLullaby) runCatching { lullabyPlayer?.start() }
            }
        })
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, uttId)
    }

    private fun hasKoreanBatchim(text: String): Boolean {
        val last = text.lastOrNull() ?: return false
        if (last.code < 0xAC00 || last.code > 0xD7A3) return false
        return (last.code - 0xAC00) % 28 != 0
    }

    private fun cleanForTts(text: String): String {
        return text
            .replace("~", "")
            .replace("～", "")
            .replace(Regex("[\\p{So}\\p{Sk}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private var lullabyPlayer: MediaPlayer? = null
    private val _lullabyPlaying = mutableStateOf(false)
    var lullabyPlaying: Boolean
        get() = _lullabyPlaying.value
        private set(value) { _lullabyPlaying.value = value }

    /**
     * 자장가 재생/정지 토글.
     * assets/voice/lullaby/lullaby.mp3 를 반복 재생한다.
     * 사용자가 직접 음원(YouTube 등)을 넣을 수 있도록 경로를 고정.
     */
    fun toggleLullaby() {
        if (lullabyPlaying) {
            stopLullaby()
            return
        }
        val ctx = appContext ?: return
        val assetPath = "voice/lullaby/lullaby.mp3"

        runCatching {
            val afd: AssetFileDescriptor = ctx.assets.openFd(assetPath)
            lullabyPlayer?.release()
            lullabyPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                prepare()
                start()
            }
            lullabyPlaying = true
            Log.i("WatchTts", "Lullaby started: $assetPath")
        }.onFailure {
            Log.w("WatchTts", "Lullaby MP3 not found ($assetPath), fallback TTS")
            speak("자장 자장 자장 자장 우리 아기 잘도 잔다")
            lullabyPlaying = true
        }
    }

    fun stopLullaby() {
        lullabyPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        lullabyPlayer = null
        lullabyPlaying = false
        Log.i("WatchTts", "Lullaby stopped")
    }

    fun shutdown() {
        stopLullaby()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    // 아기 이름 (폰에서 DataLayer 로 동기화하거나 워치 설정에서 입력)
    var babyName: String = ""
    var parentTitle: String = "엄마"

    fun quickPhrase(label: String): String {
        return if (GestureCatalog.useEnglish) quickPhraseEn(label) else quickPhraseKo(label)
    }

    private fun quickPhraseKo(label: String): String {
        val name = babyName.ifBlank { "아가" }
        val suffix = if (hasKoreanBatchim(name)) "아" else "야"
        val nameCall = if (babyName.isNotBlank()) "$name$suffix" else "아가"
        val parent = parentTitle
        return when (label) {
            "lullaby" -> "자장 자장 자장 자장 우리 $name 잘도 잔다"
            "call_name" -> "$nameCall, ${name}아"
            "name_here" -> "$nameCall, $parent 여깄어. ${name}아, $parent 왔어."
            "hungry" -> "$nameCall, 배고프니? 맘마 줄까?"
            "awake" -> "$nameCall, 일어났니? 잘 잤어?"
            "hurt" -> "왜 그래, $nameCall? 어디 아파?"
            "well_done" -> "$nameCall, 잘했어! 우리 $name 최고야!"
            "mom_here" -> "엄마야. $nameCall, 엄마 여깄어."
            "dad_here" -> "아빠야. $nameCall, 아빠 여깄어."
            "love" -> "사랑해. ${parent}가 우리 $name 너무 사랑해."
            "wait" -> "잠깐만, $parent 바로 갈게."
            else -> "$nameCall, $parent 여기 있어."
        }
    }

    private fun quickPhraseEn(label: String): String {
        val name = babyName.ifBlank { "baby" }
        val parent = if (parentTitle == "아빠") "Daddy" else "Mommy"
        return when (label) {
            "lullaby" -> "Hush little $name, don't you cry"
            "call_name" -> "$name, $name!"
            "name_here" -> "$name, $parent is here."
            "hungry" -> "Are you hungry, $name? Let's eat."
            "awake" -> "Good morning, $name! Did you sleep well?"
            "hurt" -> "What's wrong, $name? Does it hurt?"
            "well_done" -> "Good job, $name! You're the best!"
            "mom_here" -> "It's Mommy. $name, Mommy is here."
            "dad_here" -> "It's Daddy. $name, Daddy is here."
            "love" -> "I love you. $parent loves you so much, $name."
            "wait" -> "Just a moment, $parent will be right there."
            else -> "$name, $parent is here."
        }
    }

    // 하위 호환
    val QUICK_PHRASES: Map<String, String> get() = mapOf(
        "lullaby" to quickPhrase("lullaby"),
        "call_name" to quickPhrase("call_name"),
        "name_here" to quickPhrase("name_here"),
        "hungry" to quickPhrase("hungry"),
        "awake" to quickPhrase("awake"),
        "hurt" to quickPhrase("hurt"),
        "well_done" to quickPhrase("well_done"),
        "mom_here" to quickPhrase("mom_here"),
        "dad_here" to quickPhrase("dad_here"),
        "love" to quickPhrase("love"),
        "wait" to quickPhrase("wait")
    )
}
