package com.soundcue.babycare.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.soundcue.babycare.data.VadPhase
import com.soundcue.babycare.domain.BabyEventType
import com.soundcue.babycare.domain.NonSpeechCandidate
import com.soundcue.babycare.domain.OutputLang
import com.soundcue.babycare.domain.Urgency
import com.soundcue.babycare.domain.WordingMode
import com.soundcue.babycare.presentation.MainViewModel
import com.soundcue.babycare.ui.components.EventHeroCard
import com.soundcue.babycare.ui.components.EventHistoryRow
import com.soundcue.babycare.ui.theme.MintPrimary
import com.soundcue.babycare.ui.theme.MintSurface

@Composable
fun BabyDashboardScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onRequestMic: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
    ) {
        TopBar(
            onBack = onBack,
            backend = state.ai.backend,
            isReady = state.ai.isReady,
            latencyMs = state.ai.lastLatencyMs,
            lang = state.outputLang,
            onToggleLang = viewModel::toggleLanguage
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            if (!state.ai.isReady || state.ai.lastError != null) {
                item {
                    ErrorBanner(
                        lang = state.outputLang,
                        message = state.ai.lastError
                            ?: if (state.outputLang == OutputLang.EN)
                                "Gemma 4 model is not ready."
                            else "Gemma 4 모델이 준비되지 않았습니다.",
                        modelPath = state.ai.modelPath
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            item {
                EventHeroCard(
                    payload = state.currentAlert,
                    secondsAgo = state.secondsSinceLatest,
                    lang = state.outputLang
                )
                Spacer(Modifier.height(14.dp))
            }

            item {
                LiveListenPanel(
                    listening = state.listen.listening,
                    phase = state.listen.phase,
                    gemmaReady = state.ai.isReady,
                    rms = state.listen.rms,
                    lastLabel = state.listen.lastLabel,
                    lastConfidence = state.listen.lastConfidence,
                    lastRawResponse = state.listen.lastRawResponse,
                    lastClipMs = state.listen.lastClipDurationMs,
                    lastInferenceMs = state.listen.lastInferenceMs,
                    lastHeard = state.listen.lastHeard,
                    lastTopCandidates = state.listen.lastTopCandidates,
                    lastUrgency = state.listen.lastUrgency,
                    lastCareHint = state.listen.lastCareHint,
                    lastWordingMode = state.listen.lastWordingMode,
                    error = state.listen.error,
                    lang = state.outputLang,
                    onStart = onRequestMic,
                    onStop = viewModel::stopListening
                )
                Spacer(Modifier.height(24.dp))
            }

            item {
                Text(
                    if (state.outputLang == OutputLang.EN) "Demo Events" else "데모 이벤트",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )
                Spacer(Modifier.height(12.dp))
                DemoButtonsGrid(
                    lang = state.outputLang,
                    onSimulate = viewModel::simulate
                )
                Spacer(Modifier.height(24.dp))
            }

            item {
                Text(
                    if (state.outputLang == OutputLang.EN) "Recent Events" else "최근 이벤트",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )
                Spacer(Modifier.height(10.dp))
            }

            if (state.history.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFF3F3F3))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (state.outputLang == OutputLang.EN)
                                "No events detected yet"
                            else "아직 감지된 이벤트가 없습니다",
                            color = Color(0xFF888888),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                items(state.history) { item ->
                    EventHistoryRow(item.payload, item.secondsAgo)
                    Spacer(Modifier.height(8.dp))
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (state.outputLang == OutputLang.EN)
                        "This is an assistive tool. Always use your own judgment."
                    else "안전 판단은 반드시 사용자가 직접 수행하세요.",
                    fontSize = 11.sp,
                    color = Color(0xFFAAAAAA)
                )
            }
        }
    }
}

@Composable
private fun LiveListenPanel(
    listening: Boolean,
    phase: VadPhase,
    gemmaReady: Boolean,
    rms: Float,
    lastLabel: String?,
    lastConfidence: Float,
    lastRawResponse: String?,
    lastClipMs: Long,
    lastInferenceMs: Long,
    lastHeard: String?,
    lastTopCandidates: List<NonSpeechCandidate>,
    lastUrgency: Urgency,
    lastCareHint: String?,
    lastWordingMode: WordingMode,
    error: String?,
    lang: OutputLang,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val isEn = lang == OutputLang.EN
    val bg = when {
        !gemmaReady -> Color(0xFFF5F5F5)
        phase == VadPhase.ANALYZING -> Color(0xFFFFF4E0)
        phase == VadPhase.CAPTURING -> Color(0xFFFDEBD0)
        listening -> Color(0xFFE0F2F1)
        else -> Color.White
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isEn) "Live mic → Gemma 4 audio"
                else "실시간 마이크 → Gemma 4 오디오",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF555555)
            )
            Spacer(Modifier.weight(1f))
            val status = when {
                !gemmaReady -> if (isEn) "Gemma not ready" else "Gemma 미준비"
                phase == VadPhase.ANALYZING -> if (isEn) "ANALYZING" else "분석 중"
                phase == VadPhase.CAPTURING -> if (isEn) "CAPTURING" else "소리 포착 중"
                listening -> if (isEn) "WAITING" else "대기 중"
                else -> if (isEn) "IDLE" else "정지"
            }
            val statusColor = when {
                !gemmaReady -> Color(0xFF9E9E9E)
                phase == VadPhase.ANALYZING -> Color(0xFFE67E22)
                phase == VadPhase.CAPTURING -> Color(0xFFD35400)
                listening -> Color(0xFF00796B)
                else -> Color(0xFF888888)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(statusColor)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(status, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(10.dp))

        // 음량 바
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFE0E0E0))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(rms.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF26A69A))
            )
        }

        Spacer(Modifier.height(10.dp))

        if (lastLabel != null) {
            Text(
                text = if (isEn)
                    "Last: $lastLabel · ${(lastConfidence * 100).toInt()}%"
                else
                    "최근 감지: $lastLabel · ${(lastConfidence * 100).toInt()}%",
                fontSize = 12.sp,
                color = Color(0xFF444444)
            )
        } else {
            Text(
                text = if (isEn) "No detections yet" else "감지 기록 없음",
                fontSize = 12.sp,
                color = Color(0xFF888888)
            )
        }

        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 11.sp, color = Color(0xFFB71C1C))
        }

        Spacer(Modifier.height(12.dp))

        Row {
            val btnColor = if (listening) Color(0xFFE53935) else Color(0xFF26A69A)
            val isAnalyzing = phase == VadPhase.ANALYZING
            val btnEnabled = gemmaReady && !isAnalyzing
            Button(
                onClick = { if (listening) onStop() else onStart() },
                enabled = btnEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAnalyzing) Color(0xFFE67E22) else btnColor,
                    contentColor = Color.White,
                    disabledContainerColor = if (isAnalyzing) Color(0xFFE67E22) else Color(0xFFBDBDBD)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                Text(
                    text = when {
                        isAnalyzing && isEn -> "🔄 Analyzing..."
                        isAnalyzing -> "🔄 Gemma 분석 중..."
                        !gemmaReady && isEn -> "Gemma not ready"
                        !gemmaReady -> "Gemma 미준비"
                        listening && isEn -> "⏹ Stop Listening"
                        listening -> "⏹ 청취 중지"
                        isEn -> "🎙 Start Listening"
                        else -> "🎙 청취 시작"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = if (isEn)
                "Auto VAD: detects sound onset → captures until silence → Gemma 4 analyzes"
            else
                "자동 VAD: 소리 시작 감지 → 조용해질 때까지 녹음 → Gemma 4 분석",
            fontSize = 10.sp,
            color = Color(0xFF888888)
        )

        if (lastHeard != null || lastTopCandidates.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CareReasoningBox(
                lang = lang,
                heard = lastHeard,
                topCandidates = lastTopCandidates,
                urgency = lastUrgency,
                careHint = lastCareHint,
                wordingMode = lastWordingMode
            )
        }

        if (lastRawResponse != null) {
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isEn) "Gemma heard / responded:" else "Gemma가 들은 내용·응답:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFBDBDBD)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "clip ${lastClipMs}ms · infer ${lastInferenceMs}ms",
                        fontSize = 9.sp,
                        color = Color(0xFF888888)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = lastRawResponse.take(800),
                    fontSize = 11.sp,
                    color = Color(0xFFE0E0E0),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun CareReasoningBox(
    lang: OutputLang,
    heard: String?,
    topCandidates: List<NonSpeechCandidate>,
    urgency: Urgency,
    careHint: String?,
    wordingMode: WordingMode
) {
    val isEn = lang == OutputLang.EN
    val (bg, accent, modeLabel) = when (wordingMode) {
        WordingMode.STRONG -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828),
            if (isEn) "Pretty sure" else "확실해요")
        WordingMode.CAUTIOUS -> Triple(Color(0xFFFFF8E1), Color(0xFFEF6C00),
            if (isEn) "Maybe…" else "아마도…")
        WordingMode.RELISTEN -> Triple(Color(0xFFE8EAF6), Color(0xFF3949AB),
            if (isEn) "Let me listen again" else "조금만 더 들어볼게요")
        WordingMode.ABSTAIN -> Triple(Color(0xFFF5F5F5), Color(0xFF616161),
            if (isEn) "Something…?" else "뭔가 들렸는데…")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(accent)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(modeLabel, color = Color.White, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            val urgencyLabel = when (urgency) {
                Urgency.HIGH -> if (isEn) "might need you now" else "지금 확인해 주세요"
                Urgency.MEDIUM -> if (isEn) "worth a check" else "한번 살펴봐 주세요"
                Urgency.LOW -> if (isEn) "all calm" else "일단 편안한 것 같아요"
            }
            Text(urgencyLabel, fontSize = 10.sp, color = Color(0xFF555555))
        }

        heard?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            val prefix = when (wordingMode) {
                WordingMode.STRONG -> if (isEn) "I heard: " else "들려온 소리: "
                WordingMode.CAUTIOUS -> if (isEn) "Sounded like: " else "이런 소리 같았어요: "
                WordingMode.ABSTAIN -> if (isEn) "Something like: " else "무슨 소리가 난 것 같은데… "
                WordingMode.RELISTEN -> if (isEn) "Not sure yet: " else "아직 확실치 않지만… "
            }
            Text(
                prefix + it,
                fontSize = 12.sp, color = Color(0xFF333333),
                fontWeight = FontWeight.SemiBold,
                lineHeight = 17.sp
            )
        }

        if (topCandidates.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (isEn) "What it might be" else "아마 이런 소리일 거예요",
                fontSize = 10.sp, color = Color(0xFF888888),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            topCandidates.take(3).forEach { c ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(c.event, fontSize = 11.sp, color = Color(0xFF222222),
                        modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .fillMaxWidth(0.45f)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFE0E0E0))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(c.confidence.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .background(accent)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${(c.confidence * 100).toInt()}%",
                        fontSize = 10.sp,
                        color = Color(0xFF555555),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        careHint?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                "💡 $it",
                fontSize = 12.sp, color = Color(0xFF1A1A1A),
                fontWeight = FontWeight.Medium, lineHeight = 18.sp
            )
        }

        if (wordingMode == WordingMode.RELISTEN) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (isEn) "I'll keep listening a bit longer…"
                else "조금만 더 귀 기울여볼게요…",
                fontSize = 10.sp, color = accent
            )
        }
        if (wordingMode == WordingMode.ABSTAIN) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (isEn) "Not sure enough to alert you — still watching."
                else "알림 보낼 만큼 확실하진 않아요. 옆에서 지켜보고 있을게요.",
                fontSize = 10.sp, color = accent
            )
        }
    }
}

@Composable
private fun ErrorBanner(lang: OutputLang, message: String, modelPath: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFFFEBEE))
            .padding(16.dp)
    ) {
        Text(
            if (lang == OutputLang.EN) "⚠ Gemma 4 unavailable"
            else "⚠ Gemma 4 동작 불가",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB71C1C)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            fontSize = 12.sp,
            color = Color(0xFF333333)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (lang == OutputLang.EN) "Model path: $modelPath"
            else "모델 경로: $modelPath",
            fontSize = 10.sp,
            color = Color(0xFF666666)
        )
    }
}

@Composable
private fun TopBar(
    onBack: () -> Unit,
    backend: String,
    isReady: Boolean,
    latencyMs: Long?,
    lang: OutputLang,
    onToggleLang: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(top = 24.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }
            Text(
                if (lang == OutputLang.EN) "Baby Care" else "육아 모드",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            LangToggle(lang = lang, onToggle = onToggleLang)
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isReady) MintSurface else Color(0xFFFFE4E1))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isReady) "Gemma 4 · $backend" else
                        if (lang == OutputLang.EN) "Not ready" else "Gemma 미준비",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isReady) MintPrimary else Color(0xFFB71C1C)
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        latencyMs?.let {
            Text(
                text = if (lang == OutputLang.EN) "Last inference: ${it} ms"
                else "최근 추론: ${it} ms",
                fontSize = 11.sp,
                color = Color(0xFF888888),
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun LangToggle(lang: OutputLang, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFEFEFEF))
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = if (lang == OutputLang.KO) "🇰🇷 KO" else "🇺🇸 EN",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF333333)
        )
    }
}

@Composable
private fun DemoButtonsGrid(
    lang: OutputLang,
    onSimulate: (BabyEventType) -> Unit
) {
    val items = if (lang == OutputLang.EN) listOf(
        BabyEventType.BABY_CRY to "🍼 Crying",
        BabyEventType.PARENT_CALL to "👶 Calling mom",
        BabyEventType.FIRST_WORD to "💬 First word",
        BabyEventType.BABY_LAUGH to "😊 Laughing",
        BabyEventType.COUGH to "🤧 Cough",
        BabyEventType.BURP to "🫧 Burp",
        BabyEventType.FART to "💨 Fart",
        BabyEventType.HICCUP to "🌀 Hiccup",
        BabyEventType.LOUD_NOISE to "💥 Loud noise"
    ) else listOf(
        BabyEventType.BABY_CRY to "🍼 아기 울음",
        BabyEventType.PARENT_CALL to "👶 엄마 부름",
        BabyEventType.FIRST_WORD to "💬 첫 말!",
        BabyEventType.BABY_LAUGH to "😊 아기 웃음",
        BabyEventType.COUGH to "🤧 기침",
        BabyEventType.BURP to "🫧 트림",
        BabyEventType.FART to "💨 방귀",
        BabyEventType.HICCUP to "🌀 딸꾹질",
        BabyEventType.LOUD_NOISE to "💥 큰 소리"
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { (type, label) ->
                    Button(
                        onClick = { onSimulate(type) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MintPrimary,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

