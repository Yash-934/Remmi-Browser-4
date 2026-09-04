package com.remmi.browser.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class CyberTheme(
  val id: String,
  val displayName: String,
  val subtitle: String,
  val primaryAccent: Color,
  val secondaryAccent: Color,
  val tertiaryAccent: Color,
  val glowColor: Color,
  val hexCode: String,
  val isLight: Boolean = false,
  val isNormalTheme: Boolean = false,
) {
  NORMAL_DEFAULT(
    id = "NORMAL_DEFAULT",
    displayName = "NORMAL DEFAULT",
    subtitle = "Standard Light Chrome Browser",
    primaryAccent = Color(0xFF1A73E8),
    secondaryAccent = Color(0xFF1A73E8),
    tertiaryAccent = Color(0xFF1A73E8),
    glowColor = Color(0x00000000), // No glow
    hexCode = "#1A73E8",
    isLight = true,
    isNormalTheme = true,
  ),
  MINIMAL_DARK(
    id = "MINIMAL_DARK",
    displayName = "NORMAL (DARK)",
    subtitle = "Standard dark browser theme",
    primaryAccent = Color(0xFF8AB4F8),
    secondaryAccent = Color(0xFF8AB4F8),
    tertiaryAccent = Color(0xFF8AB4F8),
    glowColor = Color(0x00000000), // No glow
    hexCode = "#8AB4F8",
    isLight = false,
    isNormalTheme = true,
  ),
  JARVIS(
    id = "JARVIS",
    displayName = "J.A.R.V.I.S",
    subtitle = "Tactical Hologram & Neural Core",
    primaryAccent = Color(0xFF00E5FF),
    secondaryAccent = Color(0xFFFF007F),
    tertiaryAccent = Color(0xFFFFE600),
    glowColor = Color(0x4000E5FF),
    hexCode = "#00E5FF",
    isLight = false,
    isNormalTheme = false,
  ),
  STARK_IND(
    id = "STARK_IND",
    displayName = "STARK IND",
    subtitle = "Arc Reactor Amber & High Voltage",
    primaryAccent = Color(0xFFFFC400),
    secondaryAccent = Color(0xFFFF3D00),
    tertiaryAccent = Color(0xFF00E5FF),
    glowColor = Color(0x40FFC400),
    hexCode = "#FFC400",
    isLight = false,
    isNormalTheme = false,
  ),
  VERONICA(
    id = "VERONICA",
    displayName = "VERONICA",
    subtitle = "Hulkbuster Crimson & Orbital Defense",
    primaryAccent = Color(0xFFFF1744),
    secondaryAccent = Color(0xFFD500F9),
    tertiaryAccent = Color(0xFFFFD600),
    glowColor = Color(0x40FF1744),
    hexCode = "#FF1744",
    isLight = false,
    isNormalTheme = false,
  ),
  CYBER_MATRIX(
    id = "CYBER_MATRIX",
    displayName = "CYBER MATRIX",
    subtitle = "Subroutine Emerald & Mainframe Feed",
    primaryAccent = Color(0xFF00E676),
    secondaryAccent = Color(0xFF00E5FF),
    tertiaryAccent = Color(0xFF76FF03),
    glowColor = Color(0x4000E676),
    hexCode = "#00E676",
    isLight = false,
    isNormalTheme = false,
  );

  companion object {
    fun fromId(id: String?): CyberTheme {
      return when (id?.uppercase()) {
        "NORMAL_DEFAULT", "NORMAL DEFAULT", "MINIMAL_LIGHT", "NORMAL (LIGHT)", "DEFAULT", "LIGHT" -> NORMAL_DEFAULT
        "MINIMAL_DARK", "NORMAL_DARK", "NORMAL (DARK)", "DARK" -> MINIMAL_DARK
        "STARK_IND", "STARK" -> STARK_IND
        "VERONICA" -> VERONICA
        "CYBER_MATRIX", "MATRIX" -> CYBER_MATRIX
        "JARVIS" -> JARVIS
        else -> entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: NORMAL_DEFAULT
      }
    }
  }
}

data class CyberColorScheme(
  val primary: Color,
  val secondary: Color,
  val tertiary: Color,
  val background: Color,
  val backgroundDarker: Color,
  val surface: Color,
  val surfaceLight: Color,
  val surfaceBorder: Color,
  val glow: Color,
  val textPrimary: Color,
  val textSecondary: Color,
  val textMuted: Color,
  val neonCyan: Color = Color(0xFF00FFFF),
  val dangerRed: Color = Color(0xFFD93025),
  val successGreen: Color = Color(0xFF137333),
  val warningYellow: Color = Color(0xFFE37400),
  val torPurple: Color = Color(0xFF7B1FA2),
  val successContainer: Color = Color(0xFFE6F4EA),
  val dangerContainer: Color = Color(0xFFFCE8E6),
  val isLight: Boolean = true,
  val isNormalTheme: Boolean = true,
)

val LocalCyberColors = staticCompositionLocalOf {
  CyberColorScheme(
    primary = CyberTheme.NORMAL_DEFAULT.primaryAccent,
    secondary = CyberTheme.NORMAL_DEFAULT.secondaryAccent,
    tertiary = CyberTheme.NORMAL_DEFAULT.tertiaryAccent,
    background = Color(0xFFF8F9FA),
    backgroundDarker = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceLight = Color(0xFFF1F3F4),
    surfaceBorder = Color(0xFFDADCE0),
    glow = Color(0x00000000),
    textPrimary = Color(0xFF202124),
    textSecondary = Color(0xFF5F6368),
    textMuted = Color(0xFF70757A),
    isLight = true,
    isNormalTheme = true,
  )
}

val LocalCyberFontFamily = staticCompositionLocalOf<androidx.compose.ui.text.font.FontFamily> {
  androidx.compose.ui.text.font.FontFamily.SansSerif
}

object ThemeCyber {
  val colors: CyberColorScheme
    @Composable
    @ReadOnlyComposable
    get() = LocalCyberColors.current

  val fontFamily: androidx.compose.ui.text.font.FontFamily
    @Composable
    @ReadOnlyComposable
    get() = LocalCyberFontFamily.current
}
