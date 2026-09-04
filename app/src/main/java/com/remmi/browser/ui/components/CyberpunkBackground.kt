package com.remmi.browser.ui.components

import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.remmi.browser.ui.theme.ThemeCyber
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object BackgroundTypes {
  const val LIGHT_AURA_MESH = "LIGHT_AURA_MESH"
  const val LIGHT_FLOATING_ORBS = "LIGHT_FLOATING_ORBS"
  const val LIGHT_GEOMETRIC_DOTS = "LIGHT_GEOMETRIC_DOTS"
  const val LIGHT_CONSTELLATION = "LIGHT_CONSTELLATION"
  const val CYBERPUNK_GRID = "CYBERPUNK_GRID"
  const val MATRIX_RAIN = "MATRIX_RAIN"
  const val NEON_PARTICLES = "NEON_PARTICLES"
  const val DIGITAL_AURORA = "DIGITAL_AURORA"
  const val MINIMAL_GRADIENT = "MINIMAL_GRADIENT"
  const val CUSTOM_IMAGE = "CUSTOM_IMAGE"
}

@Composable
fun CyberpunkBackground(
  backgroundType: String = BackgroundTypes.LIGHT_AURA_MESH,
  customWallpaperUri: String? = null,
  wallpaperDimLevel: Float = 0.0f,
  wallpaperScaleMode: String = "CROP",
  modifier: Modifier = Modifier,
) {
  val primaryColor = ThemeCyber.colors.primary
  val secondaryColor = ThemeCyber.colors.secondary
  val tertiaryColor = ThemeCyber.colors.tertiary
  val backgroundColor = ThemeCyber.colors.background
  val torPurple = ThemeCyber.colors.torPurple
  val isLight = ThemeCyber.colors.isLight

  val contentScale = when (wallpaperScaleMode.uppercase()) {
    "FIT" -> ContentScale.Fit
    "FILL" -> ContentScale.FillBounds
    else -> ContentScale.Crop
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(backgroundColor)
  ) {
    if (customWallpaperUri != null && (backgroundType == BackgroundTypes.CUSTOM_IMAGE || customWallpaperUri.isNotBlank())) {
      val context = LocalContext.current
      val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
          .data(Uri.parse(customWallpaperUri))
          .crossfade(true)
          .build()
      )
      Image(
        painter = painter,
        contentDescription = "Custom Wallpaper",
        modifier = Modifier.fillMaxSize(),
        contentScale = contentScale,
      )

      // Only apply overlay if dimming is requested (wallpaperDimLevel > 0.01f)
      // When wallpaperDimLevel == 0.0f (Full Visibility), wallpaper is 100% crystal clear without any fog/mask
      if (wallpaperDimLevel > 0.01f) {
        val dimFactor = wallpaperDimLevel.coerceIn(0f, 1f)
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors = if (isLight) {
                  listOf(
                    Color.White.copy(alpha = dimFactor * 0.60f),
                    Color.White.copy(alpha = dimFactor * 0.35f),
                    Color.White.copy(alpha = dimFactor * 0.70f),
                  )
                } else {
                  listOf(
                    Color.Black.copy(alpha = dimFactor * 0.70f),
                    Color.Black.copy(alpha = dimFactor * 0.45f),
                    Color.Black.copy(alpha = dimFactor * 0.80f),
                  )
                }
              )
            )
        )
      }
    } else {
      when (backgroundType) {
        BackgroundTypes.LIGHT_AURA_MESH -> {
          LightAuraMeshAnimation(primaryColor, secondaryColor, backgroundColor)
        }
        BackgroundTypes.LIGHT_FLOATING_ORBS -> {
          LightFloatingOrbsAnimation(primaryColor, secondaryColor, backgroundColor)
        }
        BackgroundTypes.LIGHT_GEOMETRIC_DOTS -> {
          LightGeometricDotsAnimation(primaryColor, backgroundColor)
        }
        BackgroundTypes.LIGHT_CONSTELLATION -> {
          LightConstellationAnimation(primaryColor, secondaryColor, backgroundColor)
        }
        BackgroundTypes.CYBERPUNK_GRID -> {
          if (isLight) {
            LightAuraMeshAnimation(primaryColor, secondaryColor, backgroundColor)
          } else {
            CyberGridAnimation(primaryColor, torPurple, backgroundColor)
          }
        }
        BackgroundTypes.MATRIX_RAIN -> {
          MatrixRainAnimation(primaryColor, backgroundColor)
        }
        BackgroundTypes.NEON_PARTICLES -> {
          NeonParticlesAnimation(primaryColor, secondaryColor, backgroundColor)
        }
        BackgroundTypes.DIGITAL_AURORA -> {
          DigitalAuroraAnimation(primaryColor, torPurple, secondaryColor, backgroundColor)
        }
        else -> {
          // Minimal gradient
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(
                Brush.verticalGradient(
                  colors = listOf(
                    primaryColor.copy(alpha = 0.08f),
                    backgroundColor,
                  )
                )
              )
          )
        }
      }
    }
  }
}

@Composable
private fun CyberGridAnimation(
  primaryColor: Color,
  accentColor: Color,
  backgroundColor: Color,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "cyber_grid")
  val progress by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(2400, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "grid_flow"
  )

  val horizonGlow by infiniteTransition.animateFloat(
    initialValue = 0.2f,
    targetValue = 0.5f,
    animationSpec = infiniteRepeatable(
      animation = tween(3000, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "horizon_glow"
  )

  Canvas(modifier = Modifier.fillMaxSize()) {
    val width = size.width
    val height = size.height
    val horizonY = height * 0.45f

    // Sky gradient
    drawRect(
      brush = Brush.verticalGradient(
        colors = listOf(
          backgroundColor,
          accentColor.copy(alpha = 0.12f),
          primaryColor.copy(alpha = horizonGlow * 0.35f),
        ),
        startY = 0f,
        endY = horizonY,
      ),
      size = androidx.compose.ui.geometry.Size(width, horizonY)
    )

    // Floor gradient
    drawRect(
      brush = Brush.verticalGradient(
        colors = listOf(
          backgroundColor.copy(alpha = 0.3f),
          backgroundColor,
        ),
        startY = horizonY,
        endY = height,
      ),
      topLeft = Offset(0f, horizonY),
      size = androidx.compose.ui.geometry.Size(width, height - horizonY)
    )

    // Horizon glowing line
    drawLine(
      brush = Brush.horizontalGradient(
        colors = listOf(
          Color.Transparent,
          primaryColor.copy(alpha = horizonGlow),
          accentColor.copy(alpha = horizonGlow),
          Color.Transparent,
        )
      ),
      start = Offset(0f, horizonY),
      end = Offset(width, horizonY),
      strokeWidth = 2.5f
    )

    // Perspective floor horizontal moving lines
    val floorHeight = height - horizonY
    val numHozLines = 14
    for (i in 0 until numHozLines) {
      val t = (i.toFloat() + progress) / numHozLines
      val curvedT = t * t // exponential perspective
      val y = horizonY + curvedT * floorHeight
      val alpha = (curvedT * 0.35f).coerceIn(0f, 0.4f)

      drawLine(
        color = primaryColor.copy(alpha = alpha),
        start = Offset(0f, y),
        end = Offset(width, y),
        strokeWidth = 1f + (curvedT * 1.5f)
      )
    }

    // Perspective floor vertical converging lines
    val numVertLines = 16
    val vanishingX = width * 0.5f
    for (i in -numVertLines / 2..numVertLines / 2) {
      val bottomX = vanishingX + (i * (width / 5f))
      drawLine(
        brush = Brush.verticalGradient(
          colors = listOf(
            primaryColor.copy(alpha = 0.05f),
            primaryColor.copy(alpha = 0.25f),
            Color.Transparent,
          ),
          startY = horizonY,
          endY = height,
        ),
        start = Offset(vanishingX, horizonY),
        end = Offset(bottomX, height),
        strokeWidth = 1.2f
      )
    }
  }
}

@Composable
private fun MatrixRainAnimation(
  primaryColor: Color,
  backgroundColor: Color,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "matrix_rain")
  val progress by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1000f,
    animationSpec = infiniteRepeatable(
      animation = tween(12000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "rain_step"
  )

  val columns = remember {
    List(24) { index ->
      MatrixColumn(
        xRatio = (index + 0.5f) / 24f,
        speed = 0.6f + (Random.nextFloat() * 0.8f),
        offset = Random.nextFloat() * 1000f,
        length = 12 + Random.nextInt(14),
      )
    }
  }

  Canvas(modifier = Modifier.fillMaxSize()) {
    val width = size.width
    val height = size.height

    // Background fill
    drawRect(color = backgroundColor)

    columns.forEach { col ->
      val x = col.xRatio * width
      val headY = ((progress * col.speed + col.offset) % (height + 300f)) - 150f

      for (j in 0 until col.length) {
        val y = headY - (j * 18f)
        if (y in -20f..height + 20f) {
          val alpha = if (j == 0) 0.85f else ((col.length - j).toFloat() / col.length) * 0.35f
          val color = if (j == 0) Color.White else primaryColor.copy(alpha = alpha)
          drawCircle(
            color = color,
            radius = if (j == 0) 2.2f else 1.4f,
            center = Offset(x, y)
          )
        }
      }
    }
  }
}

private data class MatrixColumn(
  val xRatio: Float,
  val speed: Float,
  val offset: Float,
  val length: Int,
)

@Composable
private fun NeonParticlesAnimation(
  primaryColor: Color,
  secondaryColor: Color,
  backgroundColor: Color,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "neon_particles")
  val time by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(15000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "time"
  )

  val particles = remember {
    List(18) {
      NeonParticle(
        baseX = Random.nextFloat(),
        baseY = Random.nextFloat(),
        speedX = (Random.nextFloat() - 0.5f) * 0.08f,
        speedY = (Random.nextFloat() - 0.5f) * 0.08f,
        radius = 2f + Random.nextFloat() * 2.5f,
        isSecondary = Random.nextBoolean()
      )
    }
  }

  Canvas(modifier = Modifier.fillMaxSize()) {
    val width = size.width
    val height = size.height

    drawRect(color = backgroundColor)

    val xCoords = FloatArray(particles.size)
    val yCoords = FloatArray(particles.size)

    for (i in particles.indices) {
      val p = particles[i]
      val rad = Math.toRadians((time + (p.baseX * 100f)).toDouble()).toFloat()
      val x = ((p.baseX + sin(rad) * 0.05f + (p.speedX * time * 0.05f)) % 1f + 1f) % 1f * width
      val y = ((p.baseY + (p.speedY * time * 0.05f)) % 1f + 1f) % 1f * height
      xCoords[i] = x
      yCoords[i] = y
      val color = if (p.isSecondary) secondaryColor else primaryColor
      drawCircle(
        color = color.copy(alpha = 0.45f),
        radius = p.radius,
        center = Offset(x, y)
      )
    }

    // Connect close particles with faint neon lines
    val maxDistSq = 90f * 90f
    for (i in 0 until particles.size) {
      for (j in i + 1 until particles.size) {
        val dx = xCoords[i] - xCoords[j]
        val dy = yCoords[i] - yCoords[j]
        val distSq = dx * dx + dy * dy
        if (distSq < maxDistSq) {
          val alpha = (1f - (distSq / maxDistSq)) * 0.18f
          drawLine(
            color = primaryColor.copy(alpha = alpha),
            start = Offset(xCoords[i], yCoords[i]),
            end = Offset(xCoords[j], yCoords[j]),
            strokeWidth = 1f
          )
        }
      }
    }
  }
}

private data class NeonParticle(
  val baseX: Float,
  val baseY: Float,
  val speedX: Float,
  val speedY: Float,
  val radius: Float,
  val isSecondary: Boolean,
)

@Composable
private fun DigitalAuroraAnimation(
  primaryColor: Color,
  torPurple: Color,
  secondaryColor: Color,
  backgroundColor: Color,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "digital_aurora")
  val phase by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 6.28f,
    animationSpec = infiniteRepeatable(
      animation = tween(8000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "aurora_phase"
  )

  Canvas(modifier = Modifier.fillMaxSize()) {
    val width = size.width
    val height = size.height

    drawRect(color = backgroundColor)

    val offset1 = Offset(width * (0.3f + 0.2f * sin(phase)), height * (0.3f + 0.15f * sin(phase + 1f)))
    val offset2 = Offset(width * (0.7f - 0.2f * sin(phase + 2f)), height * (0.7f - 0.2f * sin(phase)))

    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(
          primaryColor.copy(alpha = 0.22f),
          Color.Transparent,
        ),
        center = offset1,
        radius = width * 0.65f
      ),
      center = offset1,
      radius = width * 0.65f
    )

    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(
          torPurple.copy(alpha = 0.25f),
          Color.Transparent,
        ),
        center = offset2,
        radius = width * 0.7f
      ),
      center = offset2,
      radius = width * 0.7f
    )
  }
}

@Composable
private fun LightAuraMeshAnimation(
  primaryColor: Color,
  secondaryColor: Color,
  backgroundColor: Color,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "light_aura_mesh")
  val phase by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 6.28318f,
    animationSpec = infiniteRepeatable(
      animation = tween(9000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "aura_phase"
  )

  val pulse by infiniteTransition.animateFloat(
    initialValue = 0.85f,
    targetValue = 1.15f,
    animationSpec = infiniteRepeatable(
      animation = tween(4500, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "aura_pulse"
  )

  Canvas(modifier = Modifier.fillMaxSize()) {
    val w = size.width
    val h = size.height

    // Clean warm light canvas
    drawRect(color = backgroundColor)

    // Orb 1: Soft Sky / Primary blue flowing
    val orb1Center = Offset(
      x = w * (0.25f + 0.15f * kotlin.math.cos(phase)),
      y = h * (0.22f + 0.12f * kotlin.math.sin(phase))
    )
    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(
          primaryColor.copy(alpha = 0.14f * pulse),
          primaryColor.copy(alpha = 0.05f),
          Color.Transparent
        ),
        center = orb1Center,
        radius = w * 0.75f * pulse
      ),
      center = orb1Center,
      radius = w * 0.75f * pulse
    )

    // Orb 2: Soft Violet / Lavender aura flowing
    val orb2Center = Offset(
      x = w * (0.75f - 0.18f * kotlin.math.sin(phase + 1f)),
      y = h * (0.45f + 0.15f * kotlin.math.cos(phase + 0.5f))
    )
    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(
          Color(0xFF7C4DFF).copy(alpha = 0.11f * pulse),
          Color(0xFF7C4DFF).copy(alpha = 0.03f),
          Color.Transparent
        ),
        center = orb2Center,
        radius = w * 0.7f * pulse
      ),
      center = orb2Center,
      radius = w * 0.7f * pulse
    )

    // Orb 3: Soft Amber / Sunlight warmth flowing at bottom
    val orb3Center = Offset(
      x = w * (0.4f + 0.2f * kotlin.math.sin(phase * 0.7f)),
      y = h * (0.8f - 0.12f * kotlin.math.cos(phase * 0.7f))
    )
    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(
          Color(0xFFFFB300).copy(alpha = 0.10f * pulse),
          Color(0xFFFFB300).copy(alpha = 0.02f),
          Color.Transparent
        ),
        center = orb3Center,
        radius = w * 0.8f * pulse
      ),
      center = orb3Center,
      radius = w * 0.8f * pulse
    )
  }
}

@Composable
private fun LightFloatingOrbsAnimation(
  primaryColor: Color,
  secondaryColor: Color,
  backgroundColor: Color,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "floating_orbs")
  val progress by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(12000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "orbs_progress"
  )

  val orbs = remember {
    List(12) {
      LightOrb(
        xRatio = Random.nextFloat(),
        yRatio = Random.nextFloat(),
        speed = 0.4f + Random.nextFloat() * 0.6f,
        radiusDp = 18f + Random.nextFloat() * 32f,
        isAmber = it % 3 == 0,
        isViolet = it % 3 == 1,
      )
    }
  }

  Canvas(modifier = Modifier.fillMaxSize()) {
    drawRect(color = backgroundColor)
    val w = size.width
    val h = size.height

    orbs.forEach { orb ->
      val currentY = ((orb.yRatio - progress * orb.speed) % 1f + 1f) % 1f * h
      val currentX = (orb.xRatio + 0.05f * kotlin.math.sin(progress * 6.28f * orb.speed)) * w
      val orbCenter = Offset(currentX, currentY)
      val radiusPx = orb.radiusDp * density

      val tint = when {
        orb.isAmber -> Color(0xFFFF9800)
        orb.isViolet -> Color(0xFF9C27B0)
        else -> primaryColor
      }

      // Soft glow aura
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(
            tint.copy(alpha = 0.15f),
            tint.copy(alpha = 0.04f),
            Color.Transparent
          ),
          center = orbCenter,
          radius = radiusPx * 2.2f
        ),
        center = orbCenter,
        radius = radiusPx * 2.2f
      )

      // Bubble core
      drawCircle(
        color = tint.copy(alpha = 0.08f),
        center = orbCenter,
        radius = radiusPx
      )
      drawCircle(
        color = tint.copy(alpha = 0.22f),
        center = orbCenter,
        radius = radiusPx,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f * density)
      )
    }
  }
}

private data class LightOrb(
  val xRatio: Float,
  val yRatio: Float,
  val speed: Float,
  val radiusDp: Float,
  val isAmber: Boolean,
  val isViolet: Boolean,
)

@Composable
private fun LightGeometricDotsAnimation(
  primaryColor: Color,
  backgroundColor: Color,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "geometric_dots")
  val wavePhase by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 6.28318f,
    animationSpec = infiniteRepeatable(
      animation = tween(5000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "dots_wave"
  )

  Canvas(modifier = Modifier.fillMaxSize()) {
    drawRect(color = backgroundColor)
    val w = size.width
    val h = size.height
    val spacing = 40.dp.toPx()

    val cols = (w / spacing).toInt() + 1
    val rows = (h / spacing).toInt() + 1
    val halfW = w / 2f
    val halfH = h / 2f

    for (r in 0 until rows) {
      val y = r * spacing
      val dy = (y - halfH) / h
      for (c in 0 until cols) {
        val x = c * spacing
        val dx = (x - halfW) / w
        val distFromCenter = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val pulse = (sin((wavePhase - distFromCenter * 4f).toDouble()).toFloat() + 1f) * 0.5f
        val dotRadius = 1.2.dp.toPx() + pulse * 1.2.dp.toPx()
        val alpha = (0.05f + pulse * 0.12f).coerceIn(0f, 1f)

        drawCircle(
          color = primaryColor.copy(alpha = alpha),
          radius = dotRadius,
          center = Offset(x, y)
        )
      }
    }
  }
}

@Composable
private fun LightConstellationAnimation(
  primaryColor: Color,
  secondaryColor: Color,
  backgroundColor: Color,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "constellation")
  val time by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 6.28318f,
    animationSpec = infiniteRepeatable(
      animation = tween(10000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "constellation_time"
  )

  val nodes = remember {
    List(14) {
      Offset(
        x = Random.nextFloat(),
        y = Random.nextFloat()
      )
    }
  }

  Canvas(modifier = Modifier.fillMaxSize()) {
    drawRect(color = backgroundColor)
    val w = size.width
    val h = size.height

    val xCoords = FloatArray(nodes.size)
    val yCoords = FloatArray(nodes.size)

    for (idx in nodes.indices) {
      val pt = nodes[idx]
      val offsetX = 0.03f * sin((time + idx).toDouble()).toFloat()
      val offsetY = 0.03f * kotlin.math.cos((time + idx * 0.8f).toDouble()).toFloat()
      xCoords[idx] = (pt.x + offsetX).coerceIn(0.05f, 0.95f) * w
      yCoords[idx] = (pt.y + offsetY).coerceIn(0.05f, 0.95f) * h
    }

    // Connect close nodes
    val maxDist = 110.dp.toPx()
    val maxDistSq = maxDist * maxDist
    for (i in 0 until nodes.size) {
      for (j in i + 1 until nodes.size) {
        val dx = xCoords[i] - xCoords[j]
        val dy = yCoords[i] - yCoords[j]
        val distSq = dx * dx + dy * dy
        if (distSq < maxDistSq) {
          val dist = sqrt(distSq.toDouble()).toFloat()
          val alpha = ((1f - dist / maxDist) * 0.12f).coerceIn(0f, 1f)
          drawLine(
            color = primaryColor.copy(alpha = alpha),
            start = Offset(xCoords[i], yCoords[i]),
            end = Offset(xCoords[j], yCoords[j]),
            strokeWidth = 1.dp.toPx()
          )
        }
      }
    }

    // Draw node dots
    val dotRadius = 2.5.dp.toPx()
    val auraRadius = 6.dp.toPx()
    for (idx in nodes.indices) {
      val center = Offset(xCoords[idx], yCoords[idx])
      drawCircle(
        color = primaryColor.copy(alpha = 0.28f),
        radius = dotRadius,
        center = center
      )
      drawCircle(
        color = primaryColor.copy(alpha = 0.08f),
        radius = auraRadius,
        center = center
      )
    }
  }
}
