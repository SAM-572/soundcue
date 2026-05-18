package com.soundcue.babycare.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.soundcue.babycare.wear.GestureBridge
import com.soundcue.babycare.wear.gesture.GestureCatalog
import com.soundcue.babycare.wear.gesture.GestureMode
import com.soundcue.babycare.wear.gesture.GestureViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val gestureVm: GestureViewModel by viewModels()

    private val sensorPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    // 센서 직접 테스트용
    private var testSensorCount = 0
    private var testListener: SensorEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ensureAllPermissions()
        GestureCatalog.init(this)
        WatchTts.init(this)
        setContent {
            MaterialTheme {
                WearApp(gestureVm, onTestSensor = { runSensorDiagnostic() })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WatchTts.shutdown()
        stopSensorTest()
    }

    private fun ensureAllPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BODY_SENSORS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        if (needed.isNotEmpty()) {
            sensorPermissionLauncher.launch(needed.first())
        }
    }

    /** Activity 에서 직접 센서 등록해서 테스트 — ViewModel 경유 안 함 */
    private fun runSensorDiagnostic() {
        stopSensorTest()
        testSensorCount = 0
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // 모든 센서 목록 로그
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        Log.i("SensorDiag", "=== ALL SENSORS ON THIS WATCH (${all.size}) ===")
        all.forEach { Log.i("SensorDiag", "  ${it.type}: ${it.name} (${it.vendor})") }

        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        Log.i("SensorDiag", "ACCEL=$accel, GYRO=$gyro")

        if (accel == null && gyro == null) {
            Toast.makeText(this, "센서 없음!", Toast.LENGTH_LONG).show()
            return
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                testSensorCount++
                if (testSensorCount == 1) {
                    Log.i("SensorDiag", "✅ FIRST EVENT! type=${event.sensor.type} vals=${event.values.toList()}")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "센서 작동 확인! ($testSensorCount)", Toast.LENGTH_SHORT).show()
                    }
                }
                if (testSensorCount == 50) {
                    Log.i("SensorDiag", "✅ 50 events received, sensor works fine")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "센서 정상! 50개 이벤트 수신", Toast.LENGTH_SHORT).show()
                    }
                    sm.unregisterListener(this)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        testListener = listener

        accel?.let {
            val ok = sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i("SensorDiag", "Accel register=$ok")
        }
        gyro?.let {
            val ok = sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i("SensorDiag", "Gyro register=$ok")
        }

        Toast.makeText(this, "센서 테스트 시작... 흔들어주세요", Toast.LENGTH_SHORT).show()

        // 5초 후 자동 중지 + 결과
        android.os.Handler(mainLooper).postDelayed({
            Log.i("SensorDiag", "Test ended. Total events: $testSensorCount")
            if (testSensorCount == 0) {
                Toast.makeText(this, "❌ 센서 이벤트 0개. 권한/하드웨어 문제", Toast.LENGTH_LONG).show()
            }
            stopSensorTest()
        }, 5000)
    }

    private fun stopSensorTest() {
        testListener?.let {
            val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sm.unregisterListener(it)
        }
        testListener = null
    }
}

@Composable
fun WearApp(vm: GestureViewModel, onTestSensor: () -> Unit = {}) {
    val isEn by GestureCatalog.useEnglishFlow.collectAsState()
    val alert by AlertStore.latest.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 2 })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(state = pagerState) { page ->
            when (page) {
                0 -> AlertsPage(alert = alert, isEn = isEn)
                1 -> GesturesPage(vm = vm, onTestSensor = onTestSensor, isEn = isEn)
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
        ) {
            DotIndicator(active = pagerState.currentPage == 0) { }
            Spacer(Modifier.width(8.dp))
            DotIndicator(active = pagerState.currentPage == 1) { }
        }
    }
}

/** 한글 마지막 글자에 받침(종성)이 있는지 */
private fun hasKoreanFinalConsonant(text: String): Boolean {
    val last = text.lastOrNull() ?: return false
    if (last.code < 0xAC00 || last.code > 0xD7A3) return false
    return (last.code - 0xAC00) % 28 != 0
}

@Composable
private fun DotIndicator(active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (active) 10.dp else 7.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) Color(0xFF4DB6AC) else Color(0xFF555555))
            .clickable { onClick() }
    )
}

// ─── Alerts page (기존 알림 표시) ────────────────────────
@Composable
private fun AlertsPage(alert: WatchAlert?, isEn: Boolean = false) {
    if (alert == null) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(20.dp)
        ) {
            Icon(
                Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = Color(0xFF4DB6AC),
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (isEn) "Monitoring baby" else "아기 상태 모니터링 중",
                color = Color(0xFFBDBDBD),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isEn) "→ Swipe: Gestures" else "→ 옆으로 슬라이드: 제스처",
                color = Color(0xFF666666),
                fontSize = 9.sp
            )
        }
        return
    }

    val (bg, accent) = when (alert.severity) {
        "HIGH" -> Color(0xFF3D0E0E) to Color(0xFFFF5252)
        "MEDIUM" -> Color(0xFF3B2E0E) to Color(0xFFFFB300)
        else -> Color(0xFF0E332E) to Color(0xFF4DB6AC)
    }

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    alert.severity,
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        item {
            Spacer(Modifier.height(6.dp))
            Text(
                alert.watchText,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        alert.subtype?.let {
            item {
                Spacer(Modifier.height(4.dp))
                Text(it, color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center)
            }
        }
        alert.reasoning?.let {
            item {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFDDDDDD), fontSize = 11.sp,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

// ─── Gestures page (캘리브 + 라이브) ─────────────────────
@Composable
private fun GesturesPage(vm: GestureViewModel, onTestSensor: () -> Unit = {}, isEn: Boolean = false) {
    val state by vm.state.collectAsState()

    when (state.mode) {
        GestureMode.IDLE -> GestureIdleScreen(state, vm, onTestSensor, isEn)
        GestureMode.CALIBRATING -> CalibrationScreen(state, vm, isEn)
        GestureMode.LIVE -> LiveDetectScreen(state, vm, isEn)
    }
}

@Composable
private fun GestureIdleScreen(
    state: com.soundcue.babycare.wear.gesture.GestureUiState,
    vm: GestureViewModel,
    onTestSensor: () -> Unit = {},
    isEn: Boolean = false
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { Spacer(Modifier.height(6.dp)) }

        // ─── 라이브 감지 (제일 위) ───────────────────
        item {
            ListButton(
                text = if (isEn) "▶ Start Live Detection" else "▶ 라이브 감지 시작",
                bg = Color(0xFF1B5E20),
                onClick = { vm.startLive() }
            )
        }
        item { Spacer(Modifier.height(8.dp)) }

        // ─── 바로 말하기 ──────────────────────────
        item {
            Text(if (isEn) "Quick Speak" else "바로 말하기",
                color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.Bold)
        }
        item { Spacer(Modifier.height(4.dp)) }

        // 자장가 버튼 (특별 처리 — 토글 재생/정지)
        item {
            val isPlaying = WatchTts.lullabyPlaying
            val lullabyBg = if (isPlaying) Color(0xFFE53935) else Color(0xFF5C6BC0)
            val lullabyText = if (isPlaying) {
                if (isEn) "🎵 Stop Lullaby" else "🎵 자장가 정지"
            } else {
                if (isEn) "🎵 Lullaby" else "🎵 자장가"
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(lullabyBg)
                    .clickable {
                        WatchTts.toggleLullaby()
                    }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(lullabyText, color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(5.dp))
        }

        // 퀵 버튼 — 워치 스피커로 즉시 TTS + 폰에도 전송
        items(GestureCatalog.DEFAULT.filter { it != "lullaby" }) { label ->
            val display = GestureCatalog.DISPLAY[label] ?: label
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF26A69A))
                    .clickable {
                        WatchTts.speakQuick(label)
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            GestureBridge.send(
                                context = vm.getApplication(),
                                gesture = label,
                                confidence = 1f
                            )
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(display, color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(5.dp))
        }

        // ─── 기록하기 ──────────────────────────
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Text(if (isEn) "Log Event" else "기록하기",
                color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Bold)
        }
        item { Spacer(Modifier.height(4.dp)) }
        val manualItems = if (isEn) listOf(
            "feeding" to "🍼 Feeding Done",
            "diaper" to "🧷 Diaper Changed",
            "sleep_start" to "😴 Sleep Start",
            "sleep_end" to "☀️ Sleep End"
        ) else listOf(
            "feeding" to "🍼 수유 완료",
            "diaper" to "🧷 기저귀 교체",
            "sleep_start" to "😴 수면 시작",
            "sleep_end" to "☀️ 수면 종료"
        )
        items(manualItems) { (event, display) ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF37474F))
                    .clickable {
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            GestureBridge.send(
                                context = vm.getApplication(),
                                gesture = "manual_$event",
                                confidence = 1f
                            )
                        }
                        WatchTts.speak(if (isEn) "Logged" else "기록했어요")
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(display, color = Color.White, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
        }

        // ─── 캘리브레이션 ──────────────────────────
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Text(if (isEn) "Calibration (3 each)" else "캘리브레이션 (각 3회)",
                color = Color(0xFF888888), fontSize = 9.sp)
        }
        items(GestureCatalog.DEFAULT) { label ->
            val count = state.templateCounts[label] ?: 0
            GestureLabelRow(
                label = label,
                count = count,
                onClick = { vm.startCalibration(label) }
            )
            Spacer(Modifier.height(4.dp))
        }

        state.error?.let {
            item { Spacer(Modifier.height(6.dp)) }
            item { Text(it, color = Color(0xFFFF5252), fontSize = 10.sp) }
        }

        // ─── 설정 영역 ──────────────────────────
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Text(if (isEn) "Settings" else "설정",
                color = Color(0xFFAAAAAA), fontSize = 10.sp)
        }
        item { Spacer(Modifier.height(4.dp)) }

        // 영어/한국어 전환
        item {
            ListButton(
                text = if (isEn) "🌐 한국어로 전환" else "🌐 Switch to English",
                bg = Color(0xFF0277BD),
                onClick = {
                    val newVal = !GestureCatalog.useEnglish
                    GestureCatalog.useEnglish = newVal
                    WatchTts.speak(if (newVal) "English mode" else "한국어 모드")
                }
            )
        }
        item { Spacer(Modifier.height(4.dp)) }

        // 제스처 전체 초기화
        item {
            ListButton(
                text = if (isEn) "🗑 Reset All Gestures" else "🗑 제스처 전체 초기화",
                bg = Color(0xFFB71C1C),
                onClick = { vm.clearAll() }
            )
        }
        item { Spacer(Modifier.height(4.dp)) }

        // 센서 진단
        item {
            ListButton(
                text = if (isEn) "🔧 Sensor Diagnostic" else "🔧 센서 진단 테스트",
                bg = Color(0xFF616161),
                onClick = onTestSensor
            )
        }
    }
}

@Composable
private fun GestureLabelRow(label: String, count: Int, onClick: () -> Unit) {
    val display = GestureCatalog.DISPLAY[label] ?: label
    val color = if (count >= 3) Color(0xFF26A69A)
        else if (count > 0) Color(0xFFFFB300)
        else Color(0xFF555555)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(display, color = Color.White, fontSize = 12.sp,
            modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(color)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("$count/3", color = Color.White, fontSize = 10.sp,
                fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CalibrationScreen(
    state: com.soundcue.babycare.wear.gesture.GestureUiState,
    vm: GestureViewModel,
    isEn: Boolean = false
) {
    val label = state.calibratingLabel ?: return
    val display = GestureCatalog.DISPLAY[label] ?: label
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(display, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (isEn) "Recorded ${state.calibrationProgress}/3"
            else "녹화 ${state.calibrationProgress}/3",
            color = Color(0xFFBDBDBD), fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))

        val recording = state.recordingActive
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (recording) Color(0xFFE53935) else Color(0xFF26A69A))
                .clickable(enabled = !recording) { vm.recordOne() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (recording) {
                    if (isEn) "● Recording (1.5s)" else "● 녹화 중 (1.5초)"
                } else {
                    if (isEn) "● Record Once" else "● 녹화 1회"
                },
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF424242))
                .clickable { vm.exitCalibration() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(if (isEn) "Done / Back" else "완료 / 뒤로",
                color = Color.White, fontSize = 12.sp)
        }

        state.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Color(0xFFFF5252), fontSize = 10.sp)
        }
    }
}

@Composable
private fun LiveDetectScreen(
    state: com.soundcue.babycare.wear.gesture.GestureUiState,
    vm: GestureViewModel,
    isEn: Boolean = false
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            if (isEn) "🎯 Detecting Gestures" else "🎯 제스처 감지 중",
            color = Color(0xFF4DB6AC), fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(14.dp))

        val last = state.lastDetected
        if (last != null) {
            val display = GestureCatalog.DISPLAY[last] ?: last
            Text(display, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (isEn) "Confidence ${(state.lastConfidence * 100).toInt()}%"
                else "신뢰도 ${(state.lastConfidence * 100).toInt()}%",
                color = Color(0xFFBDBDBD),
                fontSize = 11.sp
            )
        } else {
            Text(
                if (isEn) "Try a gesture with your wrist" else "손목으로 제스처를 해보세요",
                color = Color(0xFF888888), fontSize = 11.sp
            )
        }

        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFE53935))
                .clickable { vm.stopLive() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (isEn) "⏹ Stop Detection" else "⏹ 감지 중지",
                color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ListButton(text: String, bg: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
