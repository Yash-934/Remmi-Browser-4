package com.remmi.browser.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class BrowserFont(
  val id: String,
  val displayName: String,
  val subtitle: String,
  val fontFamily: FontFamily,
  val previewSample: String,
  val category: String,
) {
  CHROME_SANS(
    id = "chrome_sans",
    displayName = "Google / Chrome Sans",
    subtitle = "Modern, clean and geometric sans-serif",
    fontFamily = FontFamily.SansSerif,
    previewSample = "Google Chrome 125 • Fast & Secure Browsing",
    category = "CLEAN & MODERN"
  ),
  CYBERPUNK_MATRIX(
    id = "cyber_matrix",
    displayName = "Cyberpunk Matrix Terminal",
    subtitle = "Futuristic neon hacker console monospace",
    fontFamily = FontFamily.Monospace,
    previewSample = "REMMI_V4.2 // PROXY_NODE [ESTABLISHED]",
    category = "CYBERPUNK & HACKER"
  ),
  HACKER_CODE(
    id = "hacker_code",
    displayName = "Retro Terminal Code",
    subtitle = "Classic green-screen console monospace",
    fontFamily = FontFamily.Monospace,
    previewSample = "root@node:~# socks5://127.0.0.1:9050",
    category = "CYBERPUNK & HACKER"
  ),
  EDITORIAL_SERIF(
    id = "editorial_serif",
    displayName = "Editorial News / Serif",
    subtitle = "Classic NYT & magazine reader serif font",
    fontFamily = FontFamily.Serif,
    previewSample = "The Global Cryptographic Gazette • Vol. 88",
    category = "CLASSIC & EDITORIAL"
  ),
  STYLIZED_CURSIVE(
    id = "stylized_cursive",
    displayName = "Retro Chic Cursive",
    subtitle = "Handwritten signature script aesthetic",
    fontFamily = FontFamily.Cursive,
    previewSample = "Remmi Stealth Network Edition",
    category = "CREATIVE & CASUAL"
  ),
  SYSTEM_NATIVE(
    id = "system_native",
    displayName = "Android System Native",
    subtitle = "Device OEM default native typography",
    fontFamily = FontFamily.Default,
    previewSample = "Standard Android 14 System Typography",
    category = "SYSTEM DEFAULT"
  ),
  TACTICAL_HUD(
    id = "tactical_hud",
    displayName = "Tactical HUD Monospace",
    subtitle = "High-density technical monospace HUD font",
    fontFamily = FontFamily.Monospace,
    previewSample = "[STATUS: OK] ENCRYPTED_TUNNEL::ACTIVE",
    category = "CYBERPUNK & HACKER"
  );

  companion object {
    fun fromId(id: String?): BrowserFont {
      return entries.find { it.id.equals(id, ignoreCase = true) } ?: CHROME_SANS
    }
  }
}

val ChromeFontFamily = FontFamily.SansSerif

val CyberMonoFamily: FontFamily
  @Composable
  get() = LocalCyberFontFamily.current

fun getBrowserTypography(fontFamily: FontFamily, isNormal: Boolean): Typography {
  return Typography(
    displayLarge =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = if (isNormal) 28.sp else 30.sp,
        lineHeight = if (isNormal) 34.sp else 36.sp,
        letterSpacing = 0.sp,
        color = if (isNormal) Color.Unspecified else NeonColors.NeonCyan,
      ),
    displayMedium =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
        color = if (isNormal) Color.Unspecified else NeonColors.NeonCyan,
      ),
    titleLarge =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        color = if (isNormal) Color.Unspecified else NeonColors.TextPrimary,
      ),
    titleMedium =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        color = if (isNormal) Color.Unspecified else NeonColors.TextPrimary,
      ),
    bodyLarge =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
        color = if (isNormal) Color.Unspecified else NeonColors.TextPrimary,
      ),
    bodyMedium =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        color = if (isNormal) Color.Unspecified else NeonColors.TextSecondary,
      ),
    labelSmall =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
        color = if (isNormal) Color.Unspecified else NeonColors.NeonCyan,
      ),
    labelMedium =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        color = if (isNormal) Color.Unspecified else NeonColors.NeonYellow,
      ),
  )
}


