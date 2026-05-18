package com.soundcue.babycare.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
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
import com.soundcue.babycare.data.DailySummary
import com.soundcue.babycare.data.EventRepository
import com.soundcue.babycare.data.Profile
import com.soundcue.babycare.data.UserProfileStore
import com.soundcue.babycare.domain.OutputLang
import com.soundcue.babycare.ui.theme.DangerRed
import com.soundcue.babycare.ui.theme.MintPrimary

@Composable
fun ReportScreen(
    eventRepo: EventRepository,
    profileStore: UserProfileStore,
    lang: OutputLang,
    onBack: () -> Unit
) {
    val summary by eventRepo.todaySummary.collectAsState()
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
                if (isEn) "Today's Report" else "오늘의 리포트",
                fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            HeaderCard(profile, summary, isEn)
            Spacer(Modifier.height(16.dp))
            CountGrid(summary, isEn)
            Spacer(Modifier.height(16.dp))
            CryDetail(summary, isEn)
            Spacer(Modifier.height(16.dp))
            GemmaComment(profile, summary, isEn)
            Spacer(Modifier.height(16.dp))
            AverageComparison(profile, summary, isEn)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun HeaderCard(profile: Profile, summary: DailySummary, isEn: Boolean) {
    val name = profile.babyName.ifBlank { if (isEn) "Baby" else "아기" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text(
            if (isEn) "📋 Today's $name" else "📋 오늘의 ${name}",
            fontSize = 17.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        val ageStr = if (isEn) "${profile.babyMonths} mo ${profile.babyDays % 30} days"
                     else "${profile.babyMonths}개월 ${profile.babyDays % 30}일"
        Text(
            "${summary.date} · $ageStr",
            fontSize = 12.sp, color = Color(0xFF888888)
        )
    }
}

@Composable
private fun CountGrid(s: DailySummary, isEn: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text(
            if (isEn) "Event Summary" else "이벤트 요약",
            fontSize = 14.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        val items = if (isEn) listOf(
            "🍼 Cry"     to s.cryCount,
            "😊 Laugh"   to s.laughCount,
            "🤧 Cough"   to s.coughCount,
            "🫧 Burp"    to s.burpCount,
            "💨 Fart"    to s.fartCount,
            "💬 1st word" to s.firstWordCount,
            "🍼 Feed"    to s.feedingCount,
            "🧷 Diaper"  to s.diaperCount,
            "😴 Sleep"   to s.sleepCount
        ) else listOf(
            "🍼 울음"    to s.cryCount,
            "😊 웃음"    to s.laughCount,
            "🤧 기침"    to s.coughCount,
            "🫧 트림"    to s.burpCount,
            "💨 방귀"    to s.fartCount,
            "💬 첫 말 후보" to s.firstWordCount,
            "🍼 수유"    to s.feedingCount,
            "🧷 기저귀"  to s.diaperCount,
            "😴 수면"    to s.sleepCount
        )
        items.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { (label, count) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 6.dp)
                    ) {
                        Text(
                            "$count",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (count > 0) Color(0xFF1A1A1A) else Color(0xFFCCCCCC)
                        )
                        Text(label, fontSize = 10.sp, color = Color(0xFF888888))
                    }
                }
                if (row.size < 3) {
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CryDetail(s: DailySummary, isEn: Boolean) {
    if (s.cryCount == 0) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFFFF3F0))
            .padding(16.dp)
    ) {
        Text(
            if (isEn) "Cry Detail" else "울음 상세",
            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DangerRed
        )
        Spacer(Modifier.height(8.dp))
        s.cryBySubtype.forEach { (subtype, count) ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(subtype, fontSize = 13.sp, color = Color(0xFF333333),
                    modifier = Modifier.weight(1f))
                Text(
                    if (isEn) "${count}x" else "${count}회",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        s.peakCryHour?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                if (isEn) "⏰ Peak crying: ${it}:00–${it + 1}:00"
                else "⏰ 가장 많이 운 시간대: ${it}시~${it + 1}시",
                fontSize = 12.sp, color = Color(0xFF666666)
            )
        }
    }
}

@Composable
private fun GemmaComment(profile: Profile, s: DailySummary, isEn: Boolean) {
    val name   = profile.babyName.ifBlank { if (isEn) "Baby" else "아기" }
    val months = profile.babyMonths

    val comment = if (isEn) buildString {
        append("$name cried ${s.cryCount} time(s) today. ")
        if (months in 1..12) {
            val avg = averageCryForMonth(months)
            append("Average for ${months}-month-olds is about $avg times a day. ")
            if (s.cryCount > avg + 2) {
                append("A bit more than usual — check how ${name} is feeling. ")
            } else if (s.cryCount < avg - 1) {
                append("A calm day for $name! ")
            }
        }
        if (s.firstWordCount > 0) {
            append("${s.firstWordCount} possible first word(s) detected — how exciting! 🎉 ")
        }
        if (s.feedingCount > 0) {
            append("${s.feedingCount} feeding(s) logged. ")
        }
    } else buildString {
        append("${name}는 오늘 ${s.cryCount}번 울었어요. ")
        if (months in 1..12) {
            val avg = averageCryForMonth(months)
            append("${months}개월 아기 평균은 하루 약 ${avg}회 정도예요. ")
            if (s.cryCount > avg + 2) {
                append("평소보다 좀 많이 울었네요. 컨디션을 살펴봐 주세요. ")
            } else if (s.cryCount < avg - 1) {
                append("오늘은 차분한 하루였어요. ")
            }
        }
        if (s.firstWordCount > 0) {
            append("첫 말 후보가 ${s.firstWordCount}번 감지됐어요! 축하드려요 🎉 ")
        }
        if (s.feedingCount > 0) {
            append("수유 ${s.feedingCount}회 기록됐어요. ")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFE0F2F1))
            .padding(16.dp)
    ) {
        Text(
            if (isEn) "💬 Gemma Comment" else "💬 Gemma 코멘트",
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MintPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(comment, fontSize = 13.sp, color = Color(0xFF333333), lineHeight = 20.sp)
    }
}

@Composable
private fun AverageComparison(profile: Profile, s: DailySummary, isEn: Boolean) {
    val months = profile.babyMonths
    if (months <= 0) return

    val avgCry  = averageCryForMonth(months)
    val avgFeed = averageFeedForMonth(months)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text(
            if (isEn) "📊 vs. ${months}-month average" else "📊 ${months}개월 평균 대비",
            fontSize = 14.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        CompareRow(
            label = if (isEn) "Cry" else "울음",
            actual = s.cryCount, average = avgCry, isEn = isEn
        )
        CompareRow(
            label = if (isEn) "Feed" else "수유",
            actual = s.feedingCount, average = avgFeed, isEn = isEn
        )
    }
}

@Composable
private fun CompareRow(label: String, actual: Int, average: Int, isEn: Boolean) {
    val diff    = actual - average
    val diffStr = when {
        diff > 0 -> "+${diff}${if (isEn) "x" else "회"}"
        diff < 0 -> "${diff}${if (isEn) "x" else "회"}"
        else     -> if (isEn) "avg" else "동일"
    }
    val color = when {
        diff > 2  -> DangerRed
        diff < -1 -> MintPrimary
        else      -> Color(0xFF888888)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("$actual", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            if (isEn) " / avg $average" else " / 평균 $average",
            fontSize = 12.sp, color = Color(0xFF888888)
        )
        Spacer(Modifier.width(8.dp))
        Text(diffStr, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

private fun averageCryForMonth(months: Int): Int = when {
    months <= 1  -> 8
    months <= 3  -> 6
    months <= 6  -> 5
    months <= 12 -> 4
    months <= 24 -> 3
    else         -> 2
}

private fun averageFeedForMonth(months: Int): Int = when {
    months <= 1  -> 10
    months <= 3  -> 8
    months <= 6  -> 7
    months <= 12 -> 5
    else         -> 4
}
