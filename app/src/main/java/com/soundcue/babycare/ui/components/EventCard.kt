package com.soundcue.babycare.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundcue.babycare.domain.AlertPayload
import com.soundcue.babycare.domain.EventSeverity
import com.soundcue.babycare.domain.OutputLang
import com.soundcue.babycare.ui.theme.DangerRed
import com.soundcue.babycare.ui.theme.MintPrimary
import com.soundcue.babycare.ui.theme.MintSurface

@Composable
fun EventHeroCard(
    payload: AlertPayload?,
    secondsAgo: Long,
    lang: OutputLang = OutputLang.KO
) {
    val isActive = payload != null
    val bgColor by animateColorAsState(
        if (!isActive) Color(0xFFF5F5F5)
        else when (payload!!.severity) {
            EventSeverity.HIGH -> Color(0xFFFFF3F0)
            EventSeverity.MEDIUM -> Color(0xFFFFF8E1)
            EventSeverity.LOW -> MintSurface
        }, label = "bg"
    )
    val accent = when (payload?.severity) {
        EventSeverity.HIGH -> DangerRed
        EventSeverity.MEDIUM -> Color(0xFFFFA000)
        else -> MintPrimary
    }

    val scale by animateFloatAsState(if (isActive) 1f else 0.98f, label = "scale")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(24.dp))
            .background(bgColor)
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = when {
                    isActive && lang == OutputLang.EN -> "Just detected"
                    isActive -> "방금 감지됨"
                    lang == OutputLang.EN -> "Listening"
                    else -> "감지 대기 중"
                },
                fontSize = 13.sp,
                color = accent,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = payload?.title
                ?: if (lang == OutputLang.EN) "All quiet" else "조용한 상태입니다",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1A1A)
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = payload?.body
                ?: if (lang == OutputLang.EN)
                    "Tap a demo button below to trigger an event."
                else "데모 버튼을 눌러 이벤트를 발생시킬 수 있습니다.",
            fontSize = 15.sp,
            color = Color(0xFF555555),
            lineHeight = 22.sp
        )

        if (isActive) {
            payload!!.subtype?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (lang == OutputLang.EN) "Subtype: $it" else "세부: $it",
                    fontSize = 13.sp,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
            payload.reasoning?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (lang == OutputLang.EN) "Reasoning: $it" else "근거: $it",
                    fontSize = 12.sp,
                    color = Color(0xFF666666),
                    lineHeight = 18.sp
                )
            }
            payload.suggestion?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (lang == OutputLang.EN) "💡 $it" else "💡 $it",
                    fontSize = 13.sp,
                    color = Color(0xFF1A1A1A),
                    fontWeight = FontWeight.Medium,
                    lineHeight = 20.sp
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = if (lang == OutputLang.EN)
                    "${secondsAgo}s ago · ${payload.inferenceSource}"
                else "${secondsAgo}초 전 · ${payload.inferenceSource}",
                fontSize = 12.sp,
                color = Color(0xFF888888)
            )
        }
    }
}

@Composable
fun EventHistoryRow(payload: AlertPayload, secondsAgo: Long) {
    val accent = when (payload.severity) {
        EventSeverity.HIGH -> DangerRed
        EventSeverity.MEDIUM -> Color(0xFFFFA000)
        EventSeverity.LOW -> MintPrimary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(accent)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(payload.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(payload.watchText, fontSize = 12.sp, color = Color(0xFF888888))
        }
        Text("${secondsAgo}초 전", fontSize = 11.sp, color = Color(0xFF9E9E9E))
    }
}
