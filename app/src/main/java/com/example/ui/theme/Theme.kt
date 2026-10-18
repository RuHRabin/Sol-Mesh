package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SolMeshDarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = TerminalCyan,
    onSecondary = Color(0xFF021B27),
    secondaryContainer = Color(0xFF12303F),
    onSecondaryContainer = TerminalCyan,
    tertiary = TerminalAmber,
    onTertiary = Color(0xFF2E1900),
    background = TerminalBlack,
    onBackground = TerminalTextPrimary,
    surface = TerminalBg,
    onSurface = TerminalTextPrimary,
    surfaceVariant = TerminalSurface,
    onSurfaceVariant = TerminalTextSecondary,
    surfaceContainer = TerminalBg,
    surfaceContainerHigh = TerminalSurface,
    outline = TerminalBorder,
    outlineVariant = TerminalBorderSubtle,
    error = TerminalRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // The app is dark only: its screens use fixed surface colors, so a light scheme would be unreadable.
    MaterialTheme(
        colorScheme = SolMeshDarkColorScheme,
        typography = Typography,
        content = content
    )
}
