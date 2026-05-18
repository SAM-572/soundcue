package com.soundcue.babycare.data

import android.os.SystemClock
import android.util.Log
import com.soundcue.babycare.domain.BabySoundLabel
import com.soundcue.babycare.domain.BabySoundResult
import com.soundcue.babycare.domain.SoundPrediction

/**
 * 아기 소리 분류 파이프라인.
 *
 * 구조:
 *   WAV clip
 *     ↓
 *   YAMNet (1차: cry/laugh/crash/babble/silence 등)
 *     ↓
 *   babble 감지 시 → Gemma Audio (2차: mama/baba 구분)
 *     ↓
 *   TemporalSmoother (최근 윈도우 투표)
 *     ↓
 *   최종 BabySoundResult
 */
class BabySoundPipeline(
    private val yamnet: YamNetClassifier,
    private val gemma: GemmaLiteRtProvider?
) {
    private val smoother = TemporalSmoother()

    val isReady: Boolean get() = yamnet.isReady

    /**
     * WAV 바이트 → 최종 분류 결과.
     * @param wavBytes 16kHz mono PCM WAV (44바이트 헤더 포함)
     * @param tryGemmaBabble babble 감지 시 Gemma audio로 mama/baba 세분화 시도 여부
     */
    suspend fun classify(
        wavBytes: ByteArray,
        tryGemmaBabble: Boolean = true
    ): BabySoundResult {
        val start = SystemClock.elapsedRealtime()
        val clipDurationMs = estimateWavDurationMs(wavBytes)

        // 1) YAMNet 1차 분류
        val yamnetPredictions = yamnet.classifyWav(wavBytes, topK = 5)
        if (yamnetPredictions.isEmpty()) {
            return BabySoundResult(
                primary = SoundPrediction(BabySoundLabel.SILENCE, 0.5f),
                topK = listOf(SoundPrediction(BabySoundLabel.SILENCE, 0.5f)),
                clipDurationMs = clipDurationMs,
                inferenceTimeMs = SystemClock.elapsedRealtime() - start
            )
        }

        var primary = yamnetPredictions[0]
        var isBabbleRefined = false

        // 2) Babble 감지 시 Gemma Audio로 mama/baba 세분화
        if (tryGemmaBabble &&
            primary.label == BabySoundLabel.BABBLE_GENERIC &&
            primary.confidence > BABBLE_THRESHOLD &&
            gemma != null
        ) {
            val babbleResult = tryGemmaBabbleDetection(wavBytes)
            if (babbleResult != null) {
                primary = babbleResult
                isBabbleRefined = true
                Log.i(TAG, "Babble refined: ${babbleResult.label.tag} @ ${babbleResult.confidence}")
            }
        }

        // 3) Temporal smoothing (최근 결과 투표)
        val smoothed = smoother.submit(primary)

        val elapsed = SystemClock.elapsedRealtime() - start

        return BabySoundResult(
            primary = smoothed,
            topK = yamnetPredictions,
            isBabbleRefined = isBabbleRefined,
            clipDurationMs = clipDurationMs,
            inferenceTimeMs = elapsed
        )
    }

    /**
     * Gemma Audio (Content.AudioBytes)를 사용해 babble 오디오에서
     * mama/baba를 구분한다.
     *
     * LiteRT-LM Kotlin API의 AudioBytes 지원 여부에 따라 실패할 수 있으며,
     * 실패 시 null을 반환하고 BABBLE_GENERIC으로 유지한다.
     */
    private suspend fun tryGemmaBabbleDetection(wavBytes: ByteArray): SoundPrediction? {
        return try {
            gemma?.detectBabbleContent(wavBytes)
        } catch (e: Exception) {
            Log.w(TAG, "Gemma babble detection failed (AudioBytes may not be supported): ${e.message}")
            null
        }
    }

    fun reset() {
        smoother.reset()
    }

    private fun estimateWavDurationMs(wav: ByteArray): Long {
        val pcmBytes = (wav.size - 44).coerceAtLeast(0)
        return pcmBytes / 2 * 1000L / 16000L
    }

    companion object {
        private const val TAG = "BabySoundPipeline"
        private const val BABBLE_THRESHOLD = 0.15f
    }
}

/**
 * 최근 N개 윈도우의 분류 결과를 투표/평균해서 떨림(flicker) 방지.
 * - 동일 라벨이 windowSize 중 minVotes 이상 나오면 확정
 * - confidence는 이동평균
 */
class TemporalSmoother(
    private val windowSize: Int = 4,
    private val minVotes: Int = 2
) {
    private val history = ArrayDeque<SoundPrediction>(windowSize + 1)

    fun submit(prediction: SoundPrediction): SoundPrediction {
        history.addLast(prediction)
        while (history.size > windowSize) history.removeFirst()

        if (history.size < minVotes) return prediction

        // 라벨별 투표 집계
        val votes = history.groupBy { it.label }
        val bestGroup = votes.maxByOrNull { it.value.size } ?: return prediction

        val voteCount = bestGroup.value.size
        if (voteCount < minVotes) return prediction

        // confidence 이동평균
        val avgConf = bestGroup.value.map { it.confidence }.average().toFloat()

        return SoundPrediction(
            label = bestGroup.key,
            confidence = avgConf,
            rawYamNetLabel = bestGroup.value.last().rawYamNetLabel
        )
    }

    fun reset() {
        history.clear()
    }
}
