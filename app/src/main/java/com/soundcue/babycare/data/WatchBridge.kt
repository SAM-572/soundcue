package com.soundcue.babycare.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.soundcue.babycare.domain.AlertPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

object WatchBridge {

    private const val PATH = "/soundcue/alert"
    private const val TAG = "WatchBridge"

    suspend fun send(
        context: Context,
        payload: AlertPayload,
        subtype: String? = null,
        reasoning: String? = null
    ): Int = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("title", payload.title)
            put("body", payload.body)
            put("watchText", payload.watchText)
            put("severity", payload.severity.name)
            put("timestamp", System.currentTimeMillis())
            subtype?.let { put("subtype", it) }
            reasoning?.let { put("reasoning", it) }
        }
        val data = json.toString().toByteArray(Charsets.UTF_8)

        val nodes = try {
            Wearable.getNodeClient(context).connectedNodes.await()
        } catch (t: Throwable) {
            Log.w(TAG, "connectedNodes failed: ${t.message}")
            emptyList()
        }

        if (nodes.isEmpty()) {
            Log.w(TAG, "No connected watch nodes")
            return@withContext 0
        }

        val client = Wearable.getMessageClient(context)
        var success = 0
        for (node in nodes) {
            runCatching { client.sendMessage(node.id, PATH, data).await() }
                .onSuccess { success++ }
                .onFailure { Log.w(TAG, "sendMessage to ${node.displayName} failed: ${it.message}") }
        }
        Log.i(TAG, "Sent to $success / ${nodes.size} watches")
        success
    }

    private const val SPEAK_PATH = "/soundcue/speak"

    /** Gemma가 생성한 TTS 텍스트를 워치로 전송 → 워치 스피커에서 재생 */
    suspend fun sendSpeakText(context: Context, text: String): Int = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("action", "speak")
            put("text", text)
        }
        val data = json.toString().toByteArray(Charsets.UTF_8)

        val nodes = try {
            Wearable.getNodeClient(context).connectedNodes.await()
        } catch (t: Throwable) { emptyList() }

        val client = Wearable.getMessageClient(context)
        var ok = 0
        for (node in nodes) {
            runCatching { client.sendMessage(node.id, SPEAK_PATH, data).await() }
                .onSuccess { ok++ }
        }
        ok
    }
}
