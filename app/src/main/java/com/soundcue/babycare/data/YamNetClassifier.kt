package com.soundcue.babycare.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.soundcue.babycare.domain.BabySoundLabel
import com.soundcue.babycare.domain.SoundPrediction
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * YAMNet TFLite 기반 온디바이스 오디오 이벤트 분류기.
 *
 * - AudioSet 521 클래스 사전학습 모델 사용
 * - 0.975초(15600 샘플) 윈도우 단위 추론
 * - 긴 클립은 슬라이딩 윈도우 → 스코어 평균
 * - YAMNet 라벨 → BabySoundLabel 매핑
 */
class YamNetClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var outputSize: Int = 521

    /**
     * YAMNet 라벨 → BabySoundLabel 매핑.
     * YAMNet 라벨명은 AudioSet 공식 ontology 기준.
     */
    private val labelMapping: Map<String, BabySoundLabel> = buildMap {
        // Baby sounds — cry
        put("Baby cry, infant cry", BabySoundLabel.BABY_CRY)
        put("Crying, sobbing", BabySoundLabel.BABY_CRY)
        put("Whimper", BabySoundLabel.BABY_CRY)
        put("Wail, moan", BabySoundLabel.BABY_CRY)
        put("Screaming", BabySoundLabel.BABY_CRY)
        // Baby sounds — laugh (아기 모니터링 맥락에서 웃음은 아기 웃음으로 분류)
        put("Baby laughter", BabySoundLabel.BABY_LAUGH)
        put("Giggle", BabySoundLabel.BABY_LAUGH)
        put("Laughter", BabySoundLabel.BABY_LAUGH)
        put("Snicker", BabySoundLabel.BABY_LAUGH)
        put("Belly laugh", BabySoundLabel.BABY_LAUGH)
        put("Chuckle, chortle", BabySoundLabel.BABY_LAUGH)
        // Babble / child speech → 1차에서는 BABBLE_GENERIC, Gemma가 mama/baba 세분화
        put("Babbling", BabySoundLabel.BABBLE_GENERIC)
        put("Child speech, kid speaking", BabySoundLabel.BABBLE_GENERIC)
        put("Children shouting", BabySoundLabel.BABBLE_GENERIC)
        put("Children playing", BabySoundLabel.BABBLE_GENERIC)
        // Crash / clang / shatter
        put("Crash", BabySoundLabel.CRASH_CLANG)
        put("Breaking", BabySoundLabel.CRASH_CLANG)
        put("Shatter", BabySoundLabel.CRASH_CLANG)
        put("Glass", BabySoundLabel.CRASH_CLANG)
        put("Clang", BabySoundLabel.CRASH_CLANG)
        put("Slam", BabySoundLabel.CRASH_CLANG)
        put("Dishes, pots, and pans", BabySoundLabel.CRASH_CLANG)
        put("Clatter", BabySoundLabel.CRASH_CLANG)
        put("Chink, clink", BabySoundLabel.CRASH_CLANG)
        put("Jingle, tinkle", BabySoundLabel.CRASH_CLANG)
        put("Smash, crash", BabySoundLabel.CRASH_CLANG)
        // Body sounds
        put("Cough", BabySoundLabel.COUGH)
        put("Burping, eructation", BabySoundLabel.BURP)
        put("Hiccup", BabySoundLabel.HICCUP)
        put("Fart", BabySoundLabel.FART)
        // Adult speech
        put("Speech", BabySoundLabel.ADULT_SPEECH)
        put("Male speech, man speaking", BabySoundLabel.ADULT_SPEECH)
        put("Female speech, woman speaking", BabySoundLabel.ADULT_SPEECH)
        put("Conversation", BabySoundLabel.ADULT_SPEECH)
        put("Narration, monologue", BabySoundLabel.ADULT_SPEECH)
        // Loud
        put("Explosion", BabySoundLabel.LOUD_NOISE)
        put("Bang", BabySoundLabel.LOUD_NOISE)
        put("Gunshot, gunfire", BabySoundLabel.LOUD_NOISE)
        put("Fireworks", BabySoundLabel.LOUD_NOISE)
        put("Thump, thud", BabySoundLabel.LOUD_NOISE)
        // Silence
        put("Silence", BabySoundLabel.SILENCE)
    }

    fun initialize(): Boolean {
        return try {
            val model = loadModelFile()
            val options = Interpreter.Options().apply {
                numThreads = 2
            }
            interpreter = Interpreter(model, options)

            // 모델 output shape 동적 확인
            val outShape = interpreter!!.getOutputTensor(0).shape()
            outputSize = if (outShape.size >= 2) outShape[1] else outShape[0]

            labels = loadLabels()
            if (labels.size != outputSize) {
                Log.w(TAG, "Label count(${labels.size}) != output size($outputSize), padding")
                labels = labels + List((outputSize - labels.size).coerceAtLeast(0)) { "unknown_$it" }
            }

            Log.i(TAG, "YAMNet initialized: $outputSize classes, ${labels.size} labels")
            true
        } catch (e: Exception) {
            Log.e(TAG, "YAMNet init failed", e)
            false
        }
    }

    val isReady: Boolean get() = interpreter != null

    /**
     * WAV 바이트 → top-K BabySoundLabel 예측.
     * 44바이트 헤더 스킵 → PCM 16-bit LE → float [-1,1] 변환 → 윈도우 추론.
     */
    fun classifyWav(wavBytes: ByteArray, topK: Int = 5): List<SoundPrediction> {
        val start = SystemClock.elapsedRealtime()
        val pcmBytes = wavBytes.size - WAV_HEADER
        if (pcmBytes <= 0) return emptyList()

        val numSamples = pcmBytes / 2
        val samples = FloatArray(numSamples)
        val bb = ByteBuffer.wrap(wavBytes, WAV_HEADER, pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            samples[i] = bb.short.toFloat() / MAX_INT16
        }

        val predictions = classifyPcmFloat(samples, topK)
        val elapsed = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "YAMNet classify: ${elapsed}ms, top=${predictions.firstOrNull()?.label?.tag}")
        return predictions
    }

    /**
     * Float PCM [-1,1] 16kHz mono → 분류.
     * 0.975초 윈도우 슬라이딩, 스코어 평균 후 top-K 반환.
     */
    fun classifyPcmFloat(samples: FloatArray, topK: Int = 5): List<SoundPrediction> {
        val interp = interpreter ?: return emptyList()

        // 짧은 클립은 제로 패딩
        val input = if (samples.size < WINDOW_SAMPLES) {
            FloatArray(WINDOW_SAMPLES).also { samples.copyInto(it) }
        } else samples

        val numWindows = input.size / WINDOW_SAMPLES
        val extraSamples = input.size % WINDOW_SAMPLES
        val hasExtra = extraSamples > WINDOW_SAMPLES / 2
        val totalWindows = numWindows + if (hasExtra) 1 else 0

        if (totalWindows == 0) return emptyList()

        val avgScores = FloatArray(outputSize)

        // 각 윈도우 추론
        for (w in 0 until numWindows) {
            val window = input.copyOfRange(w * WINDOW_SAMPLES, (w + 1) * WINDOW_SAMPLES)
            val scores = runSingleWindow(interp, window)
            for (i in scores.indices) avgScores[i] += scores[i]
        }

        // 잔여 샘플 (절반 이상이면 패딩 처리)
        if (hasExtra) {
            val padded = FloatArray(WINDOW_SAMPLES)
            input.copyInto(padded, 0, numWindows * WINDOW_SAMPLES, input.size)
            val scores = runSingleWindow(interp, padded)
            for (i in scores.indices) avgScores[i] += scores[i]
        }

        // 평균
        for (i in avgScores.indices) avgScores[i] /= totalWindows

        // Top-K 추출 + BabySoundLabel 매핑
        return avgScores.indices
            .sortedByDescending { avgScores[it] }
            .take(topK * 2) // 매핑 후 중복 제거를 위해 넉넉히
            .filter { avgScores[it] > MIN_SCORE }
            .map { idx ->
                val yamNetLabel = if (idx < labels.size) labels[idx] else "unknown"
                val babySoundLabel = labelMapping[yamNetLabel] ?: BabySoundLabel.OTHER
                SoundPrediction(
                    label = babySoundLabel,
                    confidence = avgScores[idx],
                    rawYamNetLabel = yamNetLabel
                )
            }
            // 같은 BabySoundLabel끼리 confidence 합산
            .groupBy { it.label }
            .map { (label, preds) ->
                SoundPrediction(
                    label = label,
                    confidence = preds.maxOf { it.confidence },
                    rawYamNetLabel = preds.maxByOrNull { it.confidence }?.rawYamNetLabel ?: ""
                )
            }
            .let { grouped -> suppressAdultSpeech(grouped) }
            .sortedByDescending { it.confidence }
            .take(topK)
    }

    /**
     * 아기 관련 라벨(cry, laugh, babble)이 top-K에 있으면
     * ADULT_SPEECH의 confidence를 감쇄해서 아기 소리가 우선되도록 한다.
     * YAMNet의 "Speech" 클래스가 모든 사람 발성에 높은 점수를 주는 문제를 보정.
     */
    private fun suppressAdultSpeech(
        predictions: List<SoundPrediction>
    ): List<SoundPrediction> {
        val babyLabels = setOf(
            BabySoundLabel.BABY_CRY,
            BabySoundLabel.BABY_LAUGH,
            BabySoundLabel.BABBLE_GENERIC,
            BabySoundLabel.BABBLE_MAMA,
            BabySoundLabel.BABBLE_BABA
        )
        val hasBabySignal = predictions.any { it.label in babyLabels && it.confidence > BABY_SIGNAL_THRESHOLD }
        if (!hasBabySignal) return predictions

        return predictions.map { pred ->
            if (pred.label == BabySoundLabel.ADULT_SPEECH) {
                pred.copy(confidence = pred.confidence * SPEECH_SUPPRESSION_FACTOR)
            } else {
                pred
            }
        }
    }

    private fun runSingleWindow(interp: Interpreter, window: FloatArray): FloatArray {
        // 입력: [1, 15600] float32
        val inputBuffer = ByteBuffer.allocateDirect(WINDOW_SAMPLES * 4)
            .order(ByteOrder.nativeOrder())
        for (s in window) inputBuffer.putFloat(s)
        inputBuffer.rewind()

        // 출력: [1, outputSize] float32
        val outputBuffer = Array(1) { FloatArray(outputSize) }

        return try {
            interp.run(inputBuffer, outputBuffer)
            outputBuffer[0]
        } catch (e: Exception) {
            Log.e(TAG, "YAMNet inference error", e)
            FloatArray(outputSize)
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_FILE)
        val stream = FileInputStream(fd.fileDescriptor)
        val channel = stream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    private fun loadLabels(): List<String> {
        return try {
            context.assets.open(LABELS_FILE).bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "$LABELS_FILE not found, using indexed labels")
            (0 until outputSize).map { "class_$it" }
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    companion object {
        private const val TAG = "YamNet"
        private const val MODEL_FILE = "yamnet.tflite"
        private const val LABELS_FILE = "yamnet_labels.txt"
        private const val WAV_HEADER = 44
        private const val MAX_INT16 = 32768f
        private const val MIN_SCORE = 0.02f

        /** YAMNet은 16kHz에서 0.975초 = 15600 샘플 윈도우 사용 */
        const val WINDOW_SAMPLES = 15600
        const val SAMPLE_RATE = 16000

        /** 아기 관련 라벨이 이 임계값 이상이면 ADULT_SPEECH 억제 적용 */
        private const val BABY_SIGNAL_THRESHOLD = 0.05f
        /** ADULT_SPEECH confidence에 곱해서 감쇄 (0.3 = 70% 감소) */
        private const val SPEECH_SUPPRESSION_FACTOR = 0.3f
    }
}
