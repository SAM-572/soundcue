package com.soundcue.babycare.wear.gesture

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundcue.babycare.wear.GestureBridge
import com.soundcue.babycare.wear.WatchTts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

enum class GestureMode { IDLE, CALIBRATING, LIVE }

data class GestureUiState(
    val mode: GestureMode = GestureMode.IDLE,
    val calibratingLabel: String? = null,
    val calibrationProgress: Int = 0,   // 몇 번 녹화 완료 (목표 3)
    val lastDetected: String? = null,
    val lastConfidence: Float = 0f,
    val recordingActive: Boolean = false,
    val labels: List<String> = GestureCatalog.DEFAULT,
    val templateCounts: Map<String, Int> = emptyMap(),
    val error: String? = null
)

class GestureViewModel(application: Application) : AndroidViewModel(application) {

    private val store = GestureStore(application)
    private val recorder = GestureRecorder(application)

    private val _state = MutableStateFlow(GestureUiState())
    val state: StateFlow<GestureUiState> = _state.asStateFlow()

    init {
        refreshCounts()
        viewModelScope.launch {
            store.templates.collect {
                refreshCounts()
            }
        }
    }

    private fun refreshCounts() {
        val counts = GestureCatalog.DEFAULT.associateWith { store.sampleCount(it) }
        _state.value = _state.value.copy(templateCounts = counts)
    }

    // ─── 캘리브레이션 ─────────────────────────────
    fun startCalibration(label: String) {
        _state.value = _state.value.copy(
            mode = GestureMode.CALIBRATING,
            calibratingLabel = label,
            calibrationProgress = store.sampleCount(label),
            error = null
        )
    }

    /**
     * GestureRecorder 없이 Activity 진단 테스트와 동일한 방식으로 직접 센서 등록.
     * 진단에서 50이벤트 수신 확인됐으므로 이 방식이면 반드시 동작.
     */
    fun recordOne() {
        val label = _state.value.calibratingLabel ?: return
        _state.value = _state.value.copy(recordingActive = true, error = null)

        viewModelScope.launch {
            val samples = directRecord(2000L)
            if (samples.size >= 10) {
                store.add(GestureTemplate(label, samples))
                refreshCounts()
                _state.value = _state.value.copy(
                    calibrationProgress = store.sampleCount(label),
                    recordingActive = false,
                    error = null
                )
                Log.i(TAG, "Calibration OK: $label, ${samples.size} samples")
            } else {
                _state.value = _state.value.copy(
                    recordingActive = false,
                    error = "녹화 실패 (샘플 ${samples.size}개)"
                )
                Log.w(TAG, "Calibration FAIL: $label, ${samples.size} samples")
            }
        }
    }

    /** 메인 스레드에서 직접 센서 등록 → 샘플 수집 → 반환. 진단 테스트와 동일 패턴. */
    private suspend fun directRecord(durationMs: Long): List<FloatArray> {
        val sm = getApplication<Application>()
            .getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (accel == null) {
            Log.e(TAG, "directRecord: no accelerometer")
            return emptyList()
        }

        val result = mutableListOf<FloatArray>()
        var lastAccel = 0f
        var lastGyro = 0f
        var eventCount = 0
        val deferred = CompletableDeferred<List<FloatArray>>()
        val targetSamples = ((durationMs / 20).toInt()).coerceAtLeast(20) // ~100 at 50Hz

        val listener = object : SensorEventListener {
            var lastEmitMs = 0L

            override fun onSensorChanged(event: SensorEvent) {
                eventCount++
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val (x, y, z) = event.values
                        lastAccel = sqrt(x * x + y * y + z * z)
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val (x, y, z) = event.values
                        lastGyro = sqrt(x * x + y * y + z * z)
                    }
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastEmitMs < 20) return  // 50Hz 샘플링
                lastEmitMs = now

                result.add(floatArrayOf(lastAccel, lastGyro))

                if (result.size >= targetSamples && !deferred.isCompleted) {
                    deferred.complete(result.toList())
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // 메인 스레드에서 등록 (진단 테스트와 동일)
        withContext(Dispatchers.Main) {
            val handler = Handler(Looper.getMainLooper())
            sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_NORMAL, handler)
            gyro?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
            Log.i(TAG, "directRecord: sensors registered, waiting ${durationMs + 2000}ms")
        }

        // 대기
        val collected = withTimeoutOrNull(durationMs + 2000) {
            deferred.await()
        }

        // 정리
        withContext(Dispatchers.Main) {
            sm.unregisterListener(listener)
        }

        Log.i(TAG, "directRecord done: events=$eventCount, samples=${result.size}")

        return collected ?: result.toList()
    }

    fun clearLabel(label: String) {
        store.clear(label)
        refreshCounts()
    }

    fun clearAll() {
        store.clearAll()
        refreshCounts()
    }

    fun exitCalibration() {
        recorder.stop()
        _state.value = _state.value.copy(
            mode = GestureMode.IDLE,
            calibratingLabel = null,
            recordingActive = false
        )
    }

    // ─── 라이브 감지 ──────────────────────────────
    private var liveJob: kotlinx.coroutines.Job? = null

    fun startLive() {
        if (_state.value.mode == GestureMode.LIVE) return
        val templates = store.templates.value
        if (templates.isEmpty()) {
            _state.value = _state.value.copy(error = "먼저 캘리브레이션을 진행해 주세요")
            return
        }
        _state.value = _state.value.copy(mode = GestureMode.LIVE, lastDetected = null, lastConfidence = 0f, error = null)
        recorder.startStreaming()
        liveJob = viewModelScope.launch(Dispatchers.Default) {
            var lastSentAt = 0L
            val cooldownMs = 3000L
            while (isActive) {
                delay(500)
                val window = recorder.getRollingWindow()
                if (window.size < 30) continue
                val match = GestureMatcher.match(window, templates)
                if (match != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastSentAt < cooldownMs) continue
                    lastSentAt = now

                    _state.value = _state.value.copy(
                        lastDetected = match.label,
                        lastConfidence = match.confidence
                    )
                    // 자장가 제스처(Z 모양)이면 자장가 토글
                    if (match.label == "lullaby") {
                        WatchTts.toggleLullaby()
                    }
                    // 폰으로 전송
                    runCatching {
                        GestureBridge.send(
                            context = getApplication(),
                            gesture = match.label,
                            confidence = match.confidence
                        )
                    }.onFailure { Log.w(TAG, "send failed: ${it.message}") }
                }
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        recorder.stop()
        _state.value = _state.value.copy(mode = GestureMode.IDLE)
    }

    override fun onCleared() {
        super.onCleared()
        recorder.stop()
        liveJob?.cancel()
    }

    companion object { private const val TAG = "GestureVM" }
}
