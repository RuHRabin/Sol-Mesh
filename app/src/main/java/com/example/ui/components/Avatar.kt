package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AvatarColors = listOf(
    0xFF5B8DEFL, 0xFF8E7CF0L, 0xFF2FA58DL, 0xFFD9784FL, 0xFFC2589AL, 0xFF4A9BC7L, 0xFFA8892FL
)

/**
 * Round avatar showing the first letter of [name]. The color is derived from [seed] (for example the
 * peer id), so the same person always gets the same color.
 */
@Composable
fun ContactAvatar(
    name: String,
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    val color = Color(AvatarColors[(seed.hashCode() and 0x7FFFFFFF) % AvatarColors.size])
    val initial = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
