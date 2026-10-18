package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Sol Mesh palette: calm dark slate surfaces, a blue accent for actions, green only for "online / OK".
// NOTE: the Terminal* names are legacy identifiers kept so every screen keeps compiling;
// only their values changed. Prefer Accent / the semantic names below in new code.

// Surfaces (darkest to lightest)
val TerminalBlack = Color(0xFF0F1115)          // screen background
val TerminalBg = Color(0xFF171A21)             // cards, bars
val TerminalSurface = Color(0xFF1E222B)        // inputs, nested containers
val TerminalSurfaceElevated = Color(0xFF262B36)
val TerminalBorder = Color(0xFF2E3440)
val TerminalBorderSubtle = Color(0xFF232833)

// Brand accent for primary actions, selected items and outgoing messages
val Accent = Color(0xFF5B8DEF)
val AccentStrong = Color(0xFF3D6FD8)           // outgoing message bubble
val AccentSoft = Color(0xFF1B2A4A)             // tinted container behind accent content
val OnAccent = Color(0xFFFFFFFF)

// Status colors
val TerminalGreen = Color(0xFF2FB578)          // success / online (strong)
val TerminalGreenBright = Color(0xFF3DD68C)    // success / online (text and dots)
val TerminalCyan = Color(0xFF5CC8FF)           // info
val TerminalAmber = Color(0xFFF2B94B)          // warning / waiting
val TerminalRed = Color(0xFFFF6B6B)            // error / failed

// Text tiers
val TerminalTextPrimary = Color(0xFFECEFF4)
val TerminalTextSecondary = Color(0xFFA9B1BE)
val TerminalTextMuted = Color(0xFF808996)
val TerminalTextPrompt = Accent

// Semantic references
val SuccessEmerald = TerminalGreenBright
val WarningAmber = TerminalAmber
val DangerCrimson = TerminalRed
