package com.soundcue.babycare.data

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 짧은 버스트 이벤트(기침·트림·방귀·재채기) 전용 고속 감지기.
 *
 * 메인 VAD 와 병렬 실행. 에너지 비율(현재/최근평균)로 순간 스파이크를 감지.
 * 절대 에너지가 낮아도 조용한 환경에서의 약한 트림 같은 게 상대적으로 튀면 잡힘.
 *
 * 구조:
 *   AudioCaptureManager 가 5ms 청크마다 feedChunk() 호출
 *   → 에너지 비율 스파이크 감지 시 onBurstDetected 콜백
 *   → AudioCaptureManager 가 pre-roll + burst + tail 추출해 별도 emit
 */
class BurstDetector(
    private val sampleRate: Int = 16000,
    // 에너지 비율 임계값: 더 민감하게 (2.2배 스파이크만 있어도 감지)
    private val spikeRatio: Float = 2.2f,
    // 최소 절대 에너지 — 더 낮게 (조용한 트림·방귀도 포착)
    private val minAbsoluteRms: Float = 0.004f,
    // 쿨다운 단축 (연속 이벤트 간격 0.8초만)
    private val cooldownMs: Long = 800L,
    private val avgWindowSize: Int = 30
) {
    // 최근 에너지 이동 평균
    private val energyHistory = ArrayDeque<Float>(avgWindowSize + 5)
    private var lastBurstAt: Long = 0L

    var onBurstDetected: ((rms: Float, ratio: Float) -> Unit)? = null

    /**
     * 5~10ms 단위 PCM 청크를 받아 에너지 스파이크 판정.
     * @param chunk 16-bit PCM mono
     * @param n 유효 샘플 수
     * @param timestampMs 현재 시각 (SystemClock.elapsedRealtime)
     */
    fun feedChunk(chunk: ShortArray, n: Int, timestampMs: Long) {
        if (n <= 0) return
        val rms = rmsOf(chunk, n)

        energyHistory.addLast(rms)
        while (energyHistory.size > avgWindowSize) energyHistory.removeFirst()

        // 이동 평균 계산
        if (energyHistory.size < 5) return // 초기 워밍업
        val avg = energyHistory.average().toFloat().coerceAtLeast(0.001f)
        val ratio = rms / avg

        // 스파이크 판정
        if (ratio >= spikeRatio && rms >= minAbsoluteRms) {
            // 쿨다운 체크
            if (timestampMs - lastBurstAt < cooldownMs) return
            lastBurstAt = timestampMs
            Log.i(TAG, "BURST detected! rms=%.4f avg=%.4f ratio=%.1f".format(rms, avg, ratio))
            onBurstDetected?.invoke(rms, ratio)
        }
    }

    fun reset() {
        energyHistory.clear()
        lastBurstAt = 0L
    }

    private fun rmsOf(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) {
            val v = abs(buf[i].toInt()).toDouble()
            sum += v * v
        }
        val mean = sum / n
        return (sqrt(mean) / 32767.0).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "BurstDetector"
    }
}
