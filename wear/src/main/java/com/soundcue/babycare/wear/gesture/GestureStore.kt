package com.soundcue.babycare.wear.gesture

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File

/**
 * 녹화된 제스처 템플릿을 파일에 영구 저장. 다중 샘플 평균을 템플릿으로 사용.
 */
class GestureStore(context: Context) {

    private val file: File = File(context.filesDir, "gesture_templates.json")

    // 라벨 -> 녹화된 샘플 목록 (라벨당 여러 번 녹화 가능, 평균이 매칭 템플릿)
    private val raw: MutableMap<String, MutableList<GestureTemplate>> = mutableMapOf()

    val templates = MutableStateFlow<Map<String, GestureTemplate>>(emptyMap())

    init {
        load()
    }

    @Synchronized
    fun add(template: GestureTemplate) {
        val list = raw.getOrPut(template.label) { mutableListOf() }
        list.add(template)
        if (list.size > 5) list.removeAt(0)  // 최근 5개만 유지
        recomputeAveraged()
        save()
    }

    @Synchronized
    fun clear(label: String) {
        raw.remove(label)
        recomputeAveraged()
        save()
    }

    @Synchronized
    fun clearAll() {
        raw.clear()
        recomputeAveraged()
        save()
    }

    @Synchronized
    fun sampleCount(label: String): Int = raw[label]?.size ?: 0

    private fun recomputeAveraged() {
        val averaged = raw.mapNotNull { (label, samples) ->
            if (samples.isEmpty()) null
            else label to averageTemplate(label, samples)
        }.toMap()
        templates.value = averaged
    }

    /** 동일 라벨의 여러 녹화본을 시간축으로 정렬 후 평균해 하나의 템플릿 생성. */
    private fun averageTemplate(label: String, samples: List<GestureTemplate>): GestureTemplate {
        if (samples.size == 1) return samples.first()
        val refLen = samples.first().samples.size
        if (refLen == 0) return samples.first()
        val featureDim = samples.first().samples.first().size

        val avg = Array(refLen) { FloatArray(featureDim) }
        for (s in samples) {
            for (i in 0 until refLen) {
                val idx = (i.toFloat() / refLen.coerceAtLeast(1) * s.samples.size.coerceAtLeast(1))
                    .toInt().coerceIn(0, s.samples.size - 1)
                val src = s.samples[idx]
                for (d in 0 until featureDim) avg[i][d] += src[d]
            }
        }
        for (i in 0 until refLen) {
            for (d in 0 until featureDim) avg[i][d] /= samples.size
        }
        return GestureTemplate(label, avg.toList())
    }

    @Synchronized
    private fun save() {
        val out = JSONArray()
        for ((label, list) in raw) {
            for (t in list) {
                out.put(t.toJson().apply { put("label", label) })
            }
        }
        runCatching { file.writeText(out.toString()) }
            .onFailure { Log.w(TAG, "save failed: ${it.message}") }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val t = GestureTemplate.fromJson(arr.getJSONObject(i))
                raw.getOrPut(t.label) { mutableListOf() }.add(t)
            }
            recomputeAveraged()
        }.onFailure { Log.w(TAG, "load failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "GestureStore"
    }
}
