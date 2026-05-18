package com.soundcue.babycare.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

enum class VadPhase { WAITING, CAPTURING, ANALYZING }

/**
 * 16kHz 모노 PCM 오디오 캡처 + 간단한 VAD (Voice Activity Detection).
 *
 * 동작:
 * 1) 계속 마이크 읽으며 RMS 추적
 * 2) RMS > startThreshold 이면 "이벤트 시작" → 버퍼 누적 (300ms pre-roll 포함)
 * 3) RMS < endThreshold 상태가 silenceMs 이상 지속되면 "이벤트 종료" → WAV 생성 후 emit
 * 4) 다시 WAITING 으로
 *
 * 사용자가 버튼 등으로 Gemma 를 점유 중이면 (onWindowReady 가 suspend) 자동 backpressure.
 */
class AudioCaptureManager(
    private val scope: CoroutineScope,
    private val startThreshold: Float = 0.025f,   // 낮춤: 조용한 트림·방귀도 감지
    private val endThreshold: Float = 0.015f,      // 낮춤
    private val silenceMs: Long = 400L,            // 짧은 소리 뒤 빠르게 종료
    private val minEventMs: Long = 80L,            // 아주 짧은 버스트(방귀)도 허용
    private val maxEventMs: Long = 8000L,
    private val preRollMs: Long = 200L
) {
    private val sampleRate = 16000
    private val channels = 1
    private val bytesPerSample = 2

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _rms = MutableStateFlow(0f)
    val rms: StateFlow<Float> = _rms.asStateFlow()

    private val _phase = MutableStateFlow(VadPhase.WAITING)
    val phase: StateFlow<VadPhase> = _phase.asStateFlow()

    private val _events = MutableSharedFlow<ByteArray>(extraBufferCapacity = 2)
    val events: SharedFlow<ByteArray> = _events.asSharedFlow()

    // 짧은 버스트 이벤트 전용 (기침·트림·방귀 등)
    private val burstDetector = BurstDetector(sampleRate = sampleRate)
    // pre-roll 링버퍼 (burst 감지 시 직전 오디오 포함)
    private val burstPreRollSamples = (sampleRate * 0.3).toInt() // 300ms
    private val burstTailSamples = (sampleRate * 0.5).toInt()    // 500ms
    private val burstPreRoll = ShortArray(burstPreRollSamples)
    private var burstPrWrite = 0
    private var burstPrFilled = 0

    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (_isListening.value) return true

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val bufferBytes = maxOf(minBuf, sampleRate * bytesPerSample * 2)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord init failed", t); return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release(); return false
        }
        try { record.startRecording() } catch (t: Throwable) {
            Log.e(TAG, "start failed", t); record.release(); return false
        }

        _isListening.value = true
        _phase.value = VadPhase.WAITING
        burstDetector.reset()

        // burst 감지 시: pre-roll + tail 을 별도 WAV로 즉시 emit
        var burstTriggered = false
        var burstTailRemaining = 0
        val burstCapture = ArrayList<Short>()

        burstDetector.onBurstDetected = { rms, ratio ->
            if (!burstTriggered) {
                burstTriggered = true
                burstCapture.clear()
                // pre-roll 덤프
                val start = if (burstPrFilled < burstPreRollSamples) 0 else burstPrWrite
                for (i in 0 until burstPrFilled) {
                    burstCapture.add(burstPreRoll[(start + i) % burstPreRollSamples])
                }
                burstTailRemaining = burstTailSamples
                Log.i("AudioCapture", "Burst capture started (pre-roll ${burstPrFilled} samples)")
            }
        }

        val chunkSamples = sampleRate / 20   // 50ms 청크
        val preRollSamples = (sampleRate * preRollMs / 1000L).toInt()
        val minSamples = (sampleRate * minEventMs / 1000L).toInt()
        val maxSamples = (sampleRate * maxEventMs / 1000L).toInt()
        val silenceSamples = (sampleRate * silenceMs / 1000L).toInt()

        job = scope.launch(Dispatchers.IO) {
            val chunk = ShortArray(chunkSamples)
            // Pre-roll 링버퍼
            val preRoll = ShortArray(preRollSamples)
            var prRollWrite = 0
            var prRollFilled = 0

            var capturing = false
            val captureBuf = ArrayList<Short>(maxSamples)
            var silenceAccum = 0
            var smoothedRms = 0f

            try {
                while (isActive) {
                    val n = record.read(chunk, 0, chunkSamples)
                    if (n <= 0) { delay(5); continue }

                    val r = rmsOf(chunk, n)
                    smoothedRms = 0.3f * smoothedRms + 0.7f * r
                    _rms.value = smoothedRms

                    // === Burst Detector (고속 에너지 스파이크) ===
                    burstDetector.feedChunk(chunk, n, android.os.SystemClock.elapsedRealtime())

                    // burst pre-roll 링버퍼 유지
                    for (i in 0 until n) {
                        burstPreRoll[burstPrWrite] = chunk[i]
                        burstPrWrite = (burstPrWrite + 1) % burstPreRollSamples
                        if (burstPrFilled < burstPreRollSamples) burstPrFilled++
                    }

                    // burst tail 수집 → 완료 시 별도 WAV emit
                    if (burstTriggered) {
                        for (i in 0 until n) burstCapture.add(chunk[i])
                        burstTailRemaining -= n
                        if (burstTailRemaining <= 0) {
                            val pcm = ShortArray(burstCapture.size) { burstCapture[it] }
                            val wav = buildWav(pcm)
                            Log.i(TAG, "Burst WAV: ${pcm.size} samples (${pcm.size * 1000 / sampleRate}ms)")
                            _events.tryEmit(wav)
                            _phase.value = VadPhase.ANALYZING
                            burstTriggered = false
                            burstCapture.clear()
                        }
                    }

                    // === Main VAD ===
                    if (!capturing) {
                        // Pre-roll 링버퍼에 쌓기
                        for (i in 0 until n) {
                            preRoll[prRollWrite] = chunk[i]
                            prRollWrite = (prRollWrite + 1) % preRollSamples
                            if (prRollFilled < preRollSamples) prRollFilled++
                        }
                        if (smoothedRms >= startThreshold) {
                            // 이벤트 시작 — pre-roll 먼저 플러시
                            capturing = true
                            silenceAccum = 0
                            captureBuf.clear()
                            // 링버퍼 순서대로 dump
                            val start = if (prRollFilled < preRollSamples) 0
                                else prRollWrite
                            for (i in 0 until prRollFilled) {
                                val idx = (start + i) % preRollSamples
                                captureBuf.add(preRoll[idx])
                            }
                            // 현재 청크도 추가
                            for (i in 0 until n) captureBuf.add(chunk[i])
                            _phase.value = VadPhase.CAPTURING
                        }
                    } else {
                        // Capturing 중
                        for (i in 0 until n) captureBuf.add(chunk[i])
                        if (smoothedRms < endThreshold) {
                            silenceAccum += n
                        } else {
                            silenceAccum = 0
                        }

                        val reachedSilence = silenceAccum >= silenceSamples
                        val reachedMax = captureBuf.size >= maxSamples

                        if (reachedSilence || reachedMax) {
                            capturing = false
                            if (captureBuf.size >= minSamples) {
                                _phase.value = VadPhase.ANALYZING
                                val pcm = ShortArray(captureBuf.size).also { arr ->
                                    for (i in arr.indices) arr[i] = captureBuf[i]
                                }
                                val wav = buildWav(pcm)
                                _events.emit(wav)
                            }
                            captureBuf.clear()
                            silenceAccum = 0
                            _phase.value = VadPhase.WAITING
                        }
                    }
                }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                _isListening.value = false
                _rms.value = 0f
                _phase.value = VadPhase.WAITING
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 외부(ViewModel)에서 분석 완료 시 호출해 phase 를 WAITING 으로 되돌린다. */
    fun onAnalyzeDone() {
        if (_isListening.value && _phase.value == VadPhase.ANALYZING) {
            _phase.value = VadPhase.WAITING
        }
    }

    private fun rmsOf(buf: ShortArray, n: Int): Float {
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val v = abs(buf[i].toInt()).toDouble()
            sum += v * v
        }
        val mean = sum / n
        return (Math.sqrt(mean) / 32767.0).toFloat().coerceIn(0f, 1f)
    }

    private fun buildWav(samples: ShortArray): ByteArray {
        val pcmLen = samples.size * 2
        val totalLen = pcmLen + 36
        val byteRate = sampleRate * channels * bytesPerSample

        val out = ByteArrayOutputStream(pcmLen + 44)
        out.write("RIFF".toByteArray())
        out.write(intLe(totalLen))
        out.write("WAVE".toByteArray())

        out.write("fmt ".toByteArray())
        out.write(intLe(16))
        out.write(shortLe(1))
        out.write(shortLe(channels))
        out.write(intLe(sampleRate))
        out.write(intLe(byteRate))
        out.write(shortLe(channels * bytesPerSample))
        out.write(shortLe(16))

        out.write("data".toByteArray())
        out.write(intLe(pcmLen))

        val bb = ByteBuffer.allocate(pcmLen).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort(s)
        out.write(bb.array())

        return out.toByteArray()
    }

    private fun intLe(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun shortLe(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    companion object {
        private const val TAG = "AudioCapture"
    }
}
