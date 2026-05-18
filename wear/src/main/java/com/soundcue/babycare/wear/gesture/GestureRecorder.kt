package com.soundcue.babycare.wear.gesture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

/**
 * 워치 IMU(가속도계·자이로) 를 실시간으로 수집해 고정 길이 시퀀스로 내보낸다.
 * - 녹화 모드: 시작 → N초 후 stop → 자동 반환
 * - 스트리밍 모드: 롤링 윈도우로 계속 emit
 */
class GestureRecorder(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = run {
        val lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        Log.i(TAG, "LINEAR_ACCEL=${lin?.name}, ACCELEROMETER=${acc?.name}")
        lin ?: acc
    }
    private val gyro: Sensor? = run {
        val g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        Log.i(TAG, "GYROSCOPE=${g?.name}")
        g
    }

    @Volatile private var lastAccelMag: Float = 0f
    @Volatile private var lastGyroMag: Float = 0f
    @Volatile private var lastEmitTs: Long = 0L

    private val samplePeriodMs: Long = 20L  // 50Hz
    private val windowSamples: Int = 100    // 2초 @ 50Hz

    private val _rolling = ArrayDeque<FloatArray>()
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    @Volatile private var recordingTarget: Int = 0
    @Volatile private var recorded: MutableList<FloatArray>? = null
    @Volatile private var recordDeferred: CompletableDeferred<List<FloatArray>>? = null

    /** 스트리밍 시작 (매 샘플마다 롤링 윈도우로 축적). */
    fun startStreaming() {
        if (_isRunning.value) return
        recordingTarget = 0
        _rolling.clear()
        register()
    }

    fun getRollingWindow(): List<FloatArray> = synchronized(_rolling) {
        _rolling.toList()
    }

    fun hasSensors(): Boolean = accel != null || gyro != null

    /** 녹화 모드: durationMs 동안 샘플 모아서 반환. 타임아웃 시 부분 반환. */
    suspend fun record(durationMs: Long = 2000L): List<FloatArray> {
        if (!hasSensors()) {
            Log.e(TAG, "No accel/gyro sensors available on this device")
            return emptyList()
        }
        val nSamples = ((durationMs / samplePeriodMs).toInt()).coerceAtLeast(20)
        val deferred = CompletableDeferred<List<FloatArray>>()

        // 반드시 메인 스레드에서 세팅+등록 (스레드 가시성 보장)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            stop() // 이전 상태 정리
            recordingTarget = nSamples
            recorded = mutableListOf()
            recordDeferred = deferred
            sensorEventCount = 0
            Log.i(TAG, "Recording: target=$nSamples, timeout=${durationMs + 3000}ms")
            register()
        }

        val result = withTimeoutOrNull(durationMs + 3000L) {
            deferred.await()
        }
        if (result == null) {
            val got = recorded?.size ?: 0
            Log.w(TAG, "Timed out. Got $got/$nSamples samples, sensorEvents=$sensorEventCount")
            val partial = recorded?.toList() ?: emptyList()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { stop() }
            return partial
        }
        return result
    }

    companion object {
        private const val TAG = "GestureRec"
    }

    fun stop() {
        sm.unregisterListener(this)
        _isRunning.value = false
        recordingTarget = 0
        recorded = null
        recordDeferred?.cancel()
        recordDeferred = null
    }

    private var sensorEventCount = 0

    private fun register() {
        _isRunning.value = true
        sensorEventCount = 0
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        if (accel != null) {
            val ok = sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST, handler)
            Log.i(TAG, "accel register=$ok sensor=${accel.name} vendor=${accel.vendor}")
        } else {
            Log.e(TAG, "NO accelerometer on this device!")
        }
        if (gyro != null) {
            val ok = sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, handler)
            Log.i(TAG, "gyro register=$ok sensor=${gyro.name} vendor=${gyro.vendor}")
        } else {
            Log.w(TAG, "No gyroscope — using accel only")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        sensorEventCount++
        if (sensorEventCount == 1) Log.i(TAG, "First sensor event received! type=${event.sensor.type}")
        if (sensorEventCount % 500 == 0) Log.d(TAG, "Sensor events so far: $sensorEventCount")

        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                lastAccelMag = sqrt(x * x + y * y + z * z)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                lastGyroMag = sqrt(x * x + y * y + z * z)
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmitTs < samplePeriodMs) return
        lastEmitTs = now

        val sample = floatArrayOf(lastAccelMag, lastGyroMag)

        if (recordingTarget > 0) {
            val rec = recorded ?: return
            rec.add(sample)
            if (rec.size >= recordingTarget) {
                val result = rec.toList()
                val def = recordDeferred
                stop()
                def?.complete(result)
            }
        } else {
            synchronized(_rolling) {
                _rolling.addLast(sample)
                while (_rolling.size > windowSamples) _rolling.removeFirst()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
}
