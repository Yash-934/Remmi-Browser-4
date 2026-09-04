package com.remmi.browser.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.storage.SettingsRepository
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random

@Composable
fun GlitchText(
  text: String,
  modifier: Modifier = Modifier,
  fontSize: TextUnit = 20.sp,
  color: Color = ThemeCyber.colors.primary,
  glitchEnabled: Boolean = true, // default parameter kept for compatibility
  fontWeight: FontWeight = FontWeight.Bold,
  fontFamily: FontFamily = CyberMonoFamily,
) {
  var offsetX by remember { mutableFloatStateOf(0f) }
  var isGlitching by remember { mutableStateOf(false) }
  
  val context = LocalContext.current
  val settingsRepo = remember { SettingsRepository.getInstance(context) }
  val settings by settingsRepo.settings.collectAsState()
  
  val isGlitchActive = settings.glitchAnimationEnabled && glitchEnabled

  LaunchedEffect(isGlitchActive) {
    if (!isGlitchActive) return@LaunchedEffect
    while (isActive) {
      delay(Random.nextLong(3000, 6000))
      if (!isActive) break
      // Sudden jitter glitch
      offsetX = (Random.nextFloat() - 0.5f) * 6f
      isGlitching = true
      delay(60)
      if (!isActive) break
      offsetX = -offsetX * 0.7f
      delay(50)
      offsetX = 0f
      isGlitching = false
    }
  }

  Box(modifier = modifier) {
    // Secondary chromatic aberration layer
    if (isGlitching) {
      Text(
        text = text,
        fontSize = fontSize,
        color = ThemeCyber.colors.secondary.copy(alpha = 0.75f),
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        modifier = Modifier.offset(x = (offsetX * 1.5f).dp, y = (1).dp),
      )
    }

    // Tertiary chromatic aberration layer
    if (isGlitching) {
      Text(
        text = text,
        fontSize = fontSize,
        color = ThemeCyber.colors.tertiary.copy(alpha = 0.65f),
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        modifier = Modifier.offset(x = (-offsetX).dp, y = (-1).dp),
      )
    }

    // Main sharp layer
    Text(
      text = text,
      fontSize = fontSize,
      color = color,
      fontWeight = fontWeight,
      fontFamily = fontFamily,
      modifier = Modifier.offset(x = offsetX.dp),
    )
  }
}
