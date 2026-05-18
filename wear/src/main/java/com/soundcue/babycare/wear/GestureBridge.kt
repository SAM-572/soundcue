package com.soundcue.babycare.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/**
 * 워치에서 감지된 제스처를 폰으로 전송.
 * 폰(SoundCue)이 Gemma 4로 문장 생성 → 폰 스피커 TTS 재생.
 *
 * 현재는 전송 스캐폴드만 제공. 제스처 인식 모델은 다음 단계에서 붙임.
 */
object GestureBridge {

    const val GESTURE_PATH = "/soundcue/gesture"
    private const val TAG = "GestureBridge"

    /**
     * 제스처 이름(예: "hungry", "sleepy", "love", "wait") 을 폰으로 전송한다.
     */
    fun send(
        context: Context,
        gesture: String,
        confidence: Float = 1f,
        sequence: List<String> = emptyList()
    ): Int {
        val json = JSONObject().apply {
            put("gesture", gesture)
            put("confidence", confidence)
            if (sequence.isNotEmpty()) {
                put("sequence", sequence.joinToString(","))
            }
            put("timestamp", System.currentTimeMillis())
        }
        val data = json.toString().toByteArray(Charsets.UTF_8)

        val nodes = try {
            val task: Task<List<com.google.android.gms.wearable.Node>> =
                Wearable.getNodeClient(context).connectedNodes
            Tasks.await(task)
        } catch (t: Throwable) {
            Log.w(TAG, "No connected phone: ${t.message}")
            return 0
        }

        val client = Wearable.getMessageClient(context)
        var success = 0
        for (node in nodes) {
            runCatching { Tasks.await(client.sendMessage(node.id, GESTURE_PATH, data)) }
                .onSuccess { success++ }
                .onFailure { Log.w(TAG, "sendMessage failed: ${it.message}") }
        }
        Log.i(TAG, "Gesture '$gesture' sent to $success/${nodes.size} nodes")
        return success
    }
}
