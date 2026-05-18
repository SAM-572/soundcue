package com.soundcue.babycare.data

import com.soundcue.babycare.domain.AcousticFeatures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * WAV PCM16 Mono 16kHz 버퍼에서 간단한 음향 특징을 추출.
 * Gemma에 보조 신호로 제공하여 더 합리적인 추론을 유도한다.
 */
object AudioFeatureExtractor {

    private const val SAMPLE_RATE = 16000
    private const val WAV_HEADER_BYTES = 44

    fun extract(wavBytes: ByteArray): AcousticFeatures {
        val pcmBytes = (wavBytes.size - WAV_HEADER_BYTES).coerceAtLeast(0)
        val n = pcmBytes / 2
        if (n <= 0) return empty()

        val samples = ShortArray(n)
        val bb = ByteBuffer.wrap(wavBytes, WAV_HEADER_BYTES, pcmBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) samples[i] = bb.short

        return fromPcm(samples)
    }

    fun fromPcm(samples: ShortArray): AcousticFeatures {
        val n = samples.size
        if (n == 0) return empty()

        var sumSquares = 0.0
        var peak = 0
        var zeroCrossings = 0
        var prevSign = 0

        for (i in 0 until n) {
            val v = samples[i].toInt()
            val av = abs(v)
            if (av > peak) peak = av
            sumSquares += (v * v).toDouble()

            val sign = if (v > 0) 1 else if (v < 0) -1 else 0
            if (sign != 0 && prevSign != 0 && sign != prevSign) zeroCrossings++
            if (sign != 0) prevSign = sign
        }

        val rms = kotlin.math.sqrt(sumSquares / n) / 32767.0
        val peakNorm = peak / 32767.0
        val zcr = zeroCrossings.toFloat() / n.coerceAtLeast(1)
        val durationMs = n.toLong() * 1000L / SAMPLE_RATE

        val notes = buildString {
            when {
                peakNorm < 0.03 -> append("very quiet; ")
                peakNorm > 0.6 -> append("loud peak; ")
            }
            when {
                zcr > 0.25f -> append("noisy/transient; ")
                zcr < 0.05f -> append("tonal/voiced; ")
            }
            when {
                durationMs < 400 -> append("short burst; ")
                durationMs > 3000 -> append("sustained; ")
            }
        }.trim()

        return AcousticFeatures(
            durationMs = durationMs,
            rmsEnergy = rms.toFloat().coerceIn(0f, 1f),
            peakAmplitude = peakNorm.toFloat().coerceIn(0f, 1f),
            zeroCrossingRate = zcr.coerceIn(0f, 1f),
            notes = notes
        )
    }

    private fun empty() = AcousticFeatures(0, 0f, 0f, 0f, "empty")
}
