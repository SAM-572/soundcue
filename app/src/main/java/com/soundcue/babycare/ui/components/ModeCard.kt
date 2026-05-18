package com.soundcue.babycare.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundcue.babycare.ui.theme.MintPrimary
import com.soundcue.babycare.ui.theme.MintSurface
import com.soundcue.babycare.ui.theme.SoftGray
import com.soundcue.babycare.ui.theme.TextSecondary

@Composable
fun ModeCard(
    title: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeLabel: String = "활성",
    comingSoonLabel: String = "준비 중"
) {
    val bg = if (enabled) MintSurface else Color(0xFFF2F2F2)
    val border = if (enabled) MintPrimary else SoftGray
    val titleColor = if (enabled) Color(0xFF0F3B38) else TextSecondary
    val descColor = if (enabled) Color(0xFF2E5F5B) else Color(0xFF9E9E9E)
    val iconTint = if (enabled) MintPrimary else Color(0xFFBDBDBD)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.5.dp, border, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (enabled) Color.White else Color(0xFFE8E8E8)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(34.dp))
        }

        Spacer(Modifier.width(18.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor
                )
                Spacer(Modifier.width(10.dp))
                StatusBadge(
                    enabled = enabled,
                    activeLabel = activeLabel,
                    comingSoonLabel = comingSoonLabel
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = description,
                fontSize = 14.sp,
                color = descColor
            )
        }
    }
}

@Composable
private fun StatusBadge(
    enabled: Boolean,
    activeLabel: String,
    comingSoonLabel: String
) {
    val text = if (enabled) activeLabel else comingSoonLabel
    val bg = if (enabled) MintPrimary else Color(0xFFBDBDBD)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
