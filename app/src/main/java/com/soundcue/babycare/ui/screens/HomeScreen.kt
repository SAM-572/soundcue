package com.soundcue.babycare.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.soundcue.babycare.domain.OutputLang
import com.soundcue.babycare.presentation.AiRuntimeState
import com.soundcue.babycare.ui.components.ModeCard
import com.soundcue.babycare.ui.theme.DangerRed
import com.soundcue.babycare.ui.theme.MintPrimary

@Composable
fun HomeScreen(
    aiState: AiRuntimeState,
    lang: OutputLang,
    onToggleLang: () -> Unit,
    onBabyCareClick: () -> Unit,
    onGestureSpeakClick: () -> Unit,
    onProfileClick: () -> Unit = {},
    onReportClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp)
            .padding(top = 36.dp, bottom = 40.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (lang == OutputLang.EN) "SoundCue" else "소리 알림 도우미",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1A1A)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (lang == OutputLang.EN)
                        "Important sounds, translated into vision & vibration"
                    else "중요한 소리를 화면과 진동으로 알려드립니다",
                    fontSize = 14.sp,
                    color = Color(0xFF666666)
                )
            }
            HomeLangToggle(lang = lang, onToggle = onToggleLang)
        }

        Spacer(Modifier.height(28.dp))

        ModeCard(
            title = if (lang == OutputLang.EN) "Baby Care" else "육아 모드",
            description = if (lang == OutputLang.EN)
                "Detects baby crying, laughing, calling"
            else "아기 울음, 웃음, 부름 소리를 감지합니다",
            icon = Icons.Filled.ChildCare,
            enabled = true,
            onClick = onBabyCareClick,
            activeLabel = if (lang == OutputLang.EN) "Active" else "활성",
            comingSoonLabel = if (lang == OutputLang.EN) "Coming soon" else "준비 중"
        )

        Spacer(Modifier.height(12.dp))

        ModeCard(
            title = if (lang == OutputLang.EN) "Speak with Gestures" else "수화로 말하기",
            description = if (lang == OutputLang.EN)
                "Watch gesture → Gemma 4 → phone speaks to baby"
            else "워치 제스처 → Gemma 4 → 폰 스피커로 아이에게 전달",
            icon = Icons.Filled.RecordVoiceOver,
            enabled = true,
            onClick = onGestureSpeakClick,
            activeLabel = if (lang == OutputLang.EN) "Active" else "활성",
            comingSoonLabel = if (lang == OutputLang.EN) "Coming soon" else "준비 중"
        )

        Spacer(Modifier.height(12.dp))

        ModeCard(
            title = if (lang == OutputLang.EN) "Driving" else "운전 모드",
            description = if (lang == OutputLang.EN)
                "Siren, horn, impact detection (coming soon)"
            else "사이렌, 경적, 충격음 감지 (준비 중)",
            icon = Icons.Filled.DirectionsCar,
            enabled = false,
            onClick = {
                toast(
                    context,
                    if (lang == OutputLang.EN) "Driving mode coming soon"
                    else "운전 모드는 준비 중입니다"
                )
            },
            activeLabel = if (lang == OutputLang.EN) "Active" else "활성",
            comingSoonLabel = if (lang == OutputLang.EN) "Coming soon" else "준비 중"
        )

        Spacer(Modifier.height(12.dp))

        ModeCard(
            title = if (lang == OutputLang.EN) "Daily Life" else "실생활 모드",
            description = if (lang == OutputLang.EN)
                "Doorbell, alarms, home sounds (coming soon)"
            else "초인종, 화재경보기, 생활 소리 감지 (준비 중)",
            icon = Icons.Filled.Home,
            enabled = false,
            onClick = {
                toast(
                    context,
                    if (lang == OutputLang.EN) "Daily Life coming soon"
                    else "실생활 모드는 준비 중입니다"
                )
            },
            activeLabel = if (lang == OutputLang.EN) "Active" else "활성",
            comingSoonLabel = if (lang == OutputLang.EN) "Coming soon" else "준비 중"
        )

        Spacer(Modifier.height(12.dp))

        ModeCard(
            title = if (lang == OutputLang.EN) "Emergency" else "재난 모드",
            description = if (lang == OutputLang.EN)
                "Gunshot·blast·scream-like detection (coming soon)"
            else "총성·폭발·비명 유사음 감지 (준비 중)",
            icon = Icons.Filled.Warning,
            enabled = false,
            onClick = {
                toast(
                    context,
                    if (lang == OutputLang.EN) "Emergency coming soon"
                    else "재난 모드는 준비 중입니다"
                )
            },
            activeLabel = if (lang == OutputLang.EN) "Active" else "활성",
            comingSoonLabel = if (lang == OutputLang.EN) "Coming soon" else "준비 중"
        )

        Spacer(Modifier.height(12.dp))

        ModeCard(
            title = if (lang == OutputLang.EN) "Daily Report" else "오늘의 리포트",
            description = if (lang == OutputLang.EN)
                "Cry, feeding, diaper counts + monthly comparison"
            else "울음·수유·기저귀 횟수 + 개월수 비교",
            icon = Icons.Filled.Assessment,
            enabled = true,
            onClick = onReportClick,
            activeLabel = if (lang == OutputLang.EN) "Active" else "활성",
            comingSoonLabel = if (lang == OutputLang.EN) "Coming soon" else "준비 중"
        )

        Spacer(Modifier.height(12.dp))

        ModeCard(
            title = if (lang == OutputLang.EN) "Profile" else "프로필 설정",
            description = if (lang == OutputLang.EN)
                "Baby name, age, parent title, tone style"
            else "아기 이름·나이, 부모 호칭, 말투 설정",
            icon = Icons.Filled.Person,
            enabled = true,
            onClick = onProfileClick,
            activeLabel = if (lang == OutputLang.EN) "Settings" else "설정",
            comingSoonLabel = ""
        )

        Spacer(Modifier.height(32.dp))

        AiEngineCard(aiState)

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AiEngineCard(ai: AiRuntimeState) {
    val statusColor = when {
        ai.isInitializing -> Color(0xFFFFA000)
        ai.isReady -> MintPrimary
        else -> DangerRed
    }
    val statusText = when {
        ai.isInitializing -> "LOADING"
        ai.isReady -> "READY"
        else -> "FAILED"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Local AI Engine",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF888888)
            )
            Spacer(Modifier.weight(1f))
            StatusPill(text = statusText, color = statusColor)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            ai.providerName,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1A1A1A)
        )
        Spacer(Modifier.height(10.dp))
        MetaRow("Backend", ai.backend)
        MetaRow("Model", ai.modelPath.substringAfterLast('/').ifEmpty { "-" })
        ai.lastLatencyMs?.let { MetaRow("Last latency", "${it} ms") }
        ai.lastError?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                fontSize = 11.sp,
                color = DangerRed,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(MintPrimary)
            Spacer(Modifier.width(8.dp))
            Text("Watch 7: 브리지 알림 사용 중", fontSize = 12.sp, color = Color(0xFF555555))
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            fontSize = 12.sp,
            color = Color(0xFF888888),
            modifier = Modifier.width(92.dp)
        )
        Text(
            value,
            fontSize = 12.sp,
            color = Color(0xFF333333),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

@Composable
private fun HomeLangToggle(lang: OutputLang, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFEFEFEF))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = if (lang == OutputLang.KO) "🇰🇷 KO" else "🇺🇸 EN",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF333333)
        )
    }
}

private fun toast(context: android.content.Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
