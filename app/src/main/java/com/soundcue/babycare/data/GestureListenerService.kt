package com.soundcue.babycare.data

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject

/**
 * 워치에서 온 제스처 이벤트 수신.
 * 수신된 제스처는 GestureEventBus 로 흘려보내고, ViewModel 이 구독해
 * Gemma 4 로 아이-친화 문장 생성 → TextToSpeech 로 폰 스피커 재생.
 *
 * 제스처 인식 모델·센서 스트림은 wear 모듈에서 다음 단계에서 붙임.
 */
class GestureListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != GESTURE_PATH) return
        val json = runCatching {
            JSONObject(String(event.data, Charsets.UTF_8))
        }.getOrNull() ?: return

        val gesture = json.optString("gesture")
        val confidence = json.optDouble("confidence", 1.0).toFloat()
        val sequence = json.optString("sequence")
            .takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim() }
            ?: listOf(gesture)

        Log.i(TAG, "Gesture received: $gesture (seq=$sequence, conf=$confidence)")

        if (gesture.startsWith("manual_")) {
            // 수동 기록 이벤트 — Gemma 문장 생성 안 하고 바로 로그만
            ManualEventBus.events.tryEmit(gesture.removePrefix("manual_"))
        } else {
            GestureEventBus.events.tryEmit(
                GestureEvent(
                    gesture = gesture,
                    sequence = sequence,
                    confidence = confidence,
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
            )
        }
    }

    companion object {
        private const val TAG = "GestureListener"
        const val GESTURE_PATH = "/soundcue/gesture"
    }
}

data class GestureEvent(
    val gesture: String,
    val sequence: List<String>,
    val confidence: Float,
    val timestamp: Long
)

object GestureEventBus {
    val events = MutableSharedFlow<GestureEvent>(extraBufferCapacity = 8)
}

object ManualEventBus {
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
}
