package com.soundcue.babycare.wear.gesture

import kotlin.math.abs
import kotlin.math.min

/**
 * 간단한 DTW(Dynamic Time Warping) 기반 제스처 매칭.
 * feature: [accelMag, gyroMag] 2차원.
 */
object GestureMatcher {

    data class Match(val label: String, val distance: Float, val confidence: Float)

    /** 임계값 이하면 매칭 성공으로 간주. 0 에 가까울수록 유사. */
    const val DEFAULT_THRESHOLD: Float = 1.2f

    /**
     * 쿼리 시퀀스의 가속도 에너지가 이 값 미만이면 "정적 상태"로 판단, 매칭 스킵.
     * 가만히 있을 때 노이즈가 제스처로 오인되는 것을 방지.
     */
    private const val MIN_ENERGY: Float = 1.5f

    fun match(
        query: List<FloatArray>,
        templates: Map<String, GestureTemplate>,
        threshold: Float = DEFAULT_THRESHOLD
    ): Match? {
        if (query.isEmpty() || templates.isEmpty()) return null

        // 정적 상태 필터: 가속도 분산이 너무 낮으면 제스처 아님
        val accelValues = query.map { it[0] }
        val mean = accelValues.average().toFloat()
        val variance = accelValues.map { (it - mean) * (it - mean) }.average().toFloat()
        if (variance < MIN_ENERGY) return null

        // 정규화
        val qNorm = normalize(query)

        var best: Pair<String, Float>? = null
        for ((label, tpl) in templates) {
            if (tpl.samples.isEmpty()) continue
            val tNorm = normalize(tpl.samples)
            val dist = dtw(qNorm, tNorm) / maxOf(qNorm.size, tNorm.size)
            if (best == null || dist < best.second) best = label to dist
        }
        val b = best ?: return null
        if (b.second > threshold) return null

        // confidence = 1 - (dist / threshold), clamp 0..1
        val conf = (1f - (b.second / threshold)).coerceIn(0f, 1f)
        return Match(label = b.first, distance = b.second, confidence = conf)
    }

    /** 각 feature 를 각자의 max 로 나눠 0~1 범위로 스케일 */
    private fun normalize(seq: List<FloatArray>): List<FloatArray> {
        if (seq.isEmpty()) return seq
        val dim = seq.first().size
        val maxVals = FloatArray(dim) { 1e-6f }
        for (row in seq) for (d in 0 until dim) {
            val v = abs(row[d])
            if (v > maxVals[d]) maxVals[d] = v
        }
        return seq.map { row ->
            FloatArray(dim) { d -> row[d] / maxVals[d] }
        }
    }

    private fun dtw(a: List<FloatArray>, b: List<FloatArray>): Float {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }
        dp[0][0] = 0f
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = distance(a[i - 1], b[j - 1])
                dp[i][j] = cost + min(dp[i - 1][j - 1], min(dp[i - 1][j], dp[i][j - 1]))
            }
        }
        return dp[n][m]
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (d in a.indices) {
            val diff = a[d] - b[d]
            sum += diff * diff
        }
        return kotlin.math.sqrt(sum)
    }
}
