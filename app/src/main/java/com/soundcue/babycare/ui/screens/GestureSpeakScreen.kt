package com.soundcue.babycare.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.soundcue.babycare.domain.OutputLang
import com.soundcue.babycare.presentation.MainViewModel
import com.soundcue.babycare.ui.theme.MintPrimary

private val GESTURE_LABELS_KO = listOf(
    "call_name" to "📣 이름 부르기",
    "name_here" to "🏠 OO아, 여깄어",
    "hungry"    to "🍼 배고프니?",
    "awake"     to "👀 일어났니?",
    "hurt"      to "🤕 어디 아파?",
    "well_done" to "👍 잘했어",
    "love"      to "❤️ 사랑해",
    "mom_here"  to "💖 엄마 여깄어",
    "dad_here"  to "👨 아빠 여깄어",
    "wait"      to "✋ 잠깐만"
)

private val GESTURE_LABELS_EN = listOf(
    "call_name" to "📣 Call name",
    "name_here" to "🏠 I'm here",
    "hungry"    to "🍼 Hungry?",
    "awake"     to "👀 You're up!",
    "hurt"      to "🤕 Where's the hurt?",
    "well_done" to "👍 Well done!",
    "love"      to "❤️ I love you",
    "mom_here"  to "💖 Mom is here",
    "dad_here"  to "👨 Dad is here",
    "wait"      to "✋ Just a moment"
)

@Composable
fun GestureSpeakScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val isEn = state.outputLang == OutputLang.EN

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(top = 24.dp, start = 8.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
            Text(
                if (isEn) "Speak with gestures" else "수화로 말하기",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Text(
                text = if (isEn)
                    "Watch detects your gesture → Gemma 4 generates a baby-friendly sentence → phone speaks via TTS."
                else
                    "워치가 제스처를 감지하면 Gemma 4가 영유아 말투 문장을 만들어 폰 스피커로 들려줍니다.",
                fontSize = 13.sp,
                color = Color(0xFF555555),
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(20.dp))

            // 마지막 발화 결과
            if (state.gesture.lastSpoken != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFE0F2F1))
                        .padding(16.dp)
                ) {
                    Text(
                        if (isEn) "Last spoken" else "최근 발화",
                        fontSize = 11.sp,
                        color = MintPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.gesture.lastSpoken!!,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1A1A1A)
                    )
                    state.gesture.lastGesture?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            (if (isEn) "from gesture: " else "제스처: ") + it,
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // Gemma 추천 순서가 있으면 그 순서로, 없으면 기본 순서
            val baseLabels = if (isEn) GESTURE_LABELS_EN else GESTURE_LABELS_KO
            val suggestedOrder = state.gesture.suggestedOrder
            val orderedLabels = if (suggestedOrder.isNotEmpty()) {
                suggestedOrder.mapNotNull { label ->
                    baseLabels.firstOrNull { it.first == label }
                }
            } else {
                baseLabels
            }

            if (suggestedOrder.isNotEmpty() && state.currentAlert != null) {
                Text(
                    if (isEn) "Gemma suggests trying these first"
                    else "지금 상황에 맞는 순서로 추천해요",
                    fontSize = 12.sp,
                    color = MintPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
            }

            Text(
                if (isEn) "Speak to baby" else "아이에게 말하기",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF333333)
            )
            Spacer(Modifier.height(12.dp))

            orderedLabels.chunked(2).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    row.forEach { (label, display) ->
                        Button(
                            onClick = { viewModel.testGesture(label) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MintPrimary,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) {
                            Text(display, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = if (isEn)
                    "Tip: pair Watch 7 Ultra → wear app → Gestures → Calibrate (3x each) → Live."
                else
                    "팁: Watch 7 Ultra → wear 앱 → 제스처 → 캘리브레이션(각 3회) → 라이브 감지 시작",
                fontSize = 11.sp,
                color = Color(0xFF888888),
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
    }
}
