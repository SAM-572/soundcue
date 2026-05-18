package com.soundcue.babycare.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundcue.babycare.data.Profile
import com.soundcue.babycare.data.ToneStyle
import com.soundcue.babycare.data.UserProfileStore
import com.soundcue.babycare.domain.OutputLang
import com.soundcue.babycare.ui.theme.MintPrimary

@Composable
fun ProfileScreen(
    profileStore: UserProfileStore,
    lang: OutputLang,
    onBack: () -> Unit
) {
    val profile by profileStore.profile.collectAsState()
    val isEn = lang == OutputLang.EN

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "back")
            }
            Text(
                if (isEn) "Profile Settings" else "프로필 설정",
                fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            SectionTitle(if (isEn) "Baby Info" else "아기 정보")

            var name by remember { mutableStateOf(profile.babyName) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; profileStore.update { copy(babyName = it) } },
                label = { Text(if (isEn) "Baby's name" else "아기 이름") },
                placeholder = { Text(if (isEn) "e.g. Haeun" else "예: 하은이") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(12.dp))

            var birth by remember { mutableStateOf(profile.babyBirthDate) }
            OutlinedTextField(
                value = birth,
                onValueChange = { birth = it; profileStore.update { copy(babyBirthDate = it) } },
                label = { Text(if (isEn) "Birth date (YYYY-MM-DD)" else "생년월일 (YYYY-MM-DD)") },
                placeholder = { Text("e.g. 2025-10-15") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (profile.babyMonths > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isEn) "${profile.babyMonths} mo ${profile.babyDays % 30} days"
                    else "현재 ${profile.babyMonths}개월 (${profile.babyDays}일)",
                    fontSize = 12.sp, color = MintPrimary
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle(if (isEn) "Parent Settings" else "부모 설정")

            Text(
                if (isEn) "Who speaks to baby?" else "누가 말하나요?",
                fontSize = 13.sp, color = Color(0xFF555555)
            )
            Spacer(Modifier.height(8.dp))
            // Internal values stay Korean ("엄마"/"아빠") for prompt compatibility;
            // display label changes by language
            val parentOptions = listOf(
                "엄마" to if (isEn) "Mom" else "엄마",
                "아빠" to if (isEn) "Dad" else "아빠"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                parentOptions.forEach { (value, displayLabel) ->
                    val selected = profile.parentTitle == value
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MintPrimary else Color(0xFFEFEFEF))
                            .clickable { profileStore.update { copy(parentTitle = value) } }
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Text(
                            displayLabel,
                            color = if (selected) Color.White else Color(0xFF333333),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                if (isEn) "Voice Style" else "말투 스타일",
                fontSize = 13.sp, color = Color(0xFF555555)
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToneStyle.entries.forEach { tone ->
                    val selected = profile.toneStyle == tone
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MintPrimary else Color(0xFFEFEFEF))
                            .clickable { profileStore.update { copy(toneStyle = tone) } }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            if (isEn) tone.enDesc else tone.koDesc,
                            fontSize = 12.sp,
                            color = if (selected) Color.White else Color(0xFF333333),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle(if (isEn) "Night Mode" else "야간 모드")

            Text(
                if (isEn)
                    "During nighttime hours Gemma whispers short sentences, and TTS volume/pitch are lowered."
                else
                    "야간 시간대에는 Gemma가 속삭이듯 짧은 문장을 생성하고, TTS 볼륨·피치도 낮아집니다.",
                fontSize = 12.sp, color = Color(0xFF888888), lineHeight = 17.sp
            )
            Spacer(Modifier.height(8.dp))
            val nightStatus = if (profile.isNightMode)
                if (isEn) " (now: night)" else "  (지금 야간)"
            else
                if (isEn) " (now: day)" else "  (지금 주간)"
            Text(
                if (isEn) "Night: ${profile.nightStart}:00 – ${profile.nightEnd}:00$nightStatus"
                else "야간: ${profile.nightStart}시 ~ ${profile.nightEnd}시$nightStatus",
                fontSize = 13.sp, color = Color(0xFF333333)
            )

            Spacer(Modifier.height(24.dp))
            SectionTitle(if (isEn) "Preview" else "미리보기")
            PreviewCard(profile, isEn)

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun PreviewCard(profile: Profile, isEn: Boolean) {
    val name   = profile.babyName.ifBlank { if (isEn) "baby" else "아기" }
    val parent = profile.parentTitle
    val night  = profile.isNightMode

    val example = if (isEn) {
        if (night) "Shh… $name, ${if (parent == "엄마") "mom" else "dad"} is here. Milk's coming quietly."
        else "$name, you're hungry! ${if (parent == "엄마") "Mom" else "Dad"} will get your bottle~"
    } else {
        if (night) "쉿… ${name}아, $parent 여깄어. 조용히 우유 줄게."
        else "${name}아, 배고프구나! ${parent}가 맘마 줄게~"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFE0F2F1))
            .padding(16.dp)
    ) {
        Text(
            if (isEn) "Gemma will speak like this" else "Gemma가 이런 식으로 말해요",
            fontSize = 11.sp, color = MintPrimary, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(example, fontSize = 15.sp, color = Color(0xFF1A1A1A),
            fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            if (isEn) "${profile.toneStyle.enDesc} · ${if (night) "night whisper" else "daytime"}"
            else "${profile.toneStyle.koDesc} · ${if (night) "야간 속삭임" else "주간"}",
            fontSize = 11.sp, color = Color(0xFF666666)
        )
    }
}
