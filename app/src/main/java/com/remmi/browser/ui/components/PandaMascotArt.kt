package com.remmi.browser.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A stylized, high-polish 3D-inspired Panda Mascot for REMMI Browser.
 * Features rounded ears, gentle breathing animation, glossy eye highlights,
 * cute rosy cheeks, and playful curiosity.
 */
@Composable
fun PandaMascotArt(
  modifier: Modifier = Modifier,
  size: Dp = 96.dp,
  isDarkTheme: Boolean = false,
  accentColor: Color = Color(0xFF00E5FF),
) {
  val infiniteTransition = rememberInfiniteTransition(label = "panda_anim")

  // Gentle breathing / head bobbing
  val headBob by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = -3.5f,
    animationSpec = infiniteRepeatable(
      animation = tween(2400, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "head_bob"
  )

  // Gentle ear wiggle
  val earTilt by infiniteTransition.animateFloat(
    initialValue = -2f,
    targetValue = 2f,
    animationSpec = infiniteRepeatable(
      animation = tween(3200, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "ear_tilt"
  )

  Box(modifier = modifier.size(size)) {
    Canvas(modifier = Modifier.matchParentSize()) {
      val w = this.size.width
      val h = this.size.height

      val headCenterX = w * 0.52f
      val headCenterY = (h * 0.56f) + (headBob * density)
      val headRadiusX = w * 0.38f
      val headRadiusY = h * 0.35f

      // 1. Soft Ambient Shadow below panda
      drawOval(
        brush = Brush.radialGradient(
          colors = listOf(
            Color.Black.copy(alpha = if (isDarkTheme) 0.35f else 0.14f),
            Color.Transparent
          ),
          center = Offset(headCenterX, h * 0.92f),
          radius = w * 0.44f
        ),
        topLeft = Offset(headCenterX - w * 0.44f, h * 0.85f),
        size = Size(w * 0.88f, h * 0.16f)
      )

      // 2. Ears (Dark charcoal with inner soft tint)
      val earColor = Color(0xFF1E222A)
      val innerEarColor = Color(0xFF2E3440)
      val earRadius = w * 0.15f

      // Left Ear
      val leftEarCenter = Offset(headCenterX - headRadiusX * 0.72f, headCenterY - headRadiusY * 0.78f + (earTilt * 0.5f))
      drawCircle(
        color = earColor,
        radius = earRadius,
        center = leftEarCenter
      )
      drawCircle(
        color = innerEarColor,
        radius = earRadius * 0.58f,
        center = leftEarCenter
      )

      // Right Ear
      val rightEarCenter = Offset(headCenterX + headRadiusX * 0.72f, headCenterY - headRadiusY * 0.78f - (earTilt * 0.5f))
      drawCircle(
        color = earColor,
        radius = earRadius,
        center = rightEarCenter
      )
      drawCircle(
        color = innerEarColor,
        radius = earRadius * 0.58f,
        center = rightEarCenter
      )

      // 3. Main Face (Fluffy, pearlescent white gradient)
      val faceBrush = Brush.radialGradient(
        colors = listOf(
          Color(0xFFFFFFFF),
          Color(0xFFF4F6FA),
          Color(0xFFE2E8F0)
        ),
        center = Offset(headCenterX, headCenterY - headRadiusY * 0.15f),
        radius = headRadiusX * 1.15f
      )

      drawOval(
        brush = faceBrush,
        topLeft = Offset(headCenterX - headRadiusX, headCenterY - headRadiusY),
        size = Size(headRadiusX * 2f, headRadiusY * 2f)
      )

      // Face outline border for crispness
      drawOval(
        color = if (isDarkTheme) Color.White.copy(alpha = 0.18f) else Color(0xFFCBD5E1),
        topLeft = Offset(headCenterX - headRadiusX, headCenterY - headRadiusY),
        size = Size(headRadiusX * 2f, headRadiusY * 2f),
        style = Stroke(width = 1.2f * density)
      )

      // 4. Black Eye Patches (Characteristic tilted teardrop/oval patches)
      val eyePatchColor = Color(0xFF1E222A)
      val patchRadiusX = headRadiusX * 0.30f
      val patchRadiusY = headRadiusY * 0.38f

      // Left Eye Patch
      val leftPatchCenter = Offset(headCenterX - headRadiusX * 0.42f, headCenterY - headRadiusY * 0.08f)
      drawOval(
        color = eyePatchColor,
        topLeft = Offset(leftPatchCenter.x - patchRadiusX, leftPatchCenter.y - patchRadiusY),
        size = Size(patchRadiusX * 2f, patchRadiusY * 2f)
      )

      // Right Eye Patch
      val rightPatchCenter = Offset(headCenterX + headRadiusX * 0.42f, headCenterY - headRadiusY * 0.08f)
      drawOval(
        color = eyePatchColor,
        topLeft = Offset(rightPatchCenter.x - patchRadiusX, rightPatchCenter.y - patchRadiusY),
        size = Size(patchRadiusX * 2f, patchRadiusY * 2f)
      )

      // 5. Glossy Eyes with Catchlight Sparkles
      val eyeBallColor = Color(0xFF0F141C)
      val eyeRadius = patchRadiusX * 0.58f

      // Left Eyeball
      drawCircle(
        color = eyeBallColor,
        radius = eyeRadius,
        center = Offset(leftPatchCenter.x + patchRadiusX * 0.1f, leftPatchCenter.y + patchRadiusY * 0.05f)
      )
      // Left Eye Catchlight (Major Sparkle)
      drawCircle(
        color = Color.White,
        radius = eyeRadius * 0.38f,
        center = Offset(leftPatchCenter.x + patchRadiusX * 0.02f, leftPatchCenter.y - patchRadiusY * 0.12f)
      )
      // Left Eye Minor Sparkle
      drawCircle(
        color = Color.White.copy(alpha = 0.8f),
        radius = eyeRadius * 0.16f,
        center = Offset(leftPatchCenter.x + patchRadiusX * 0.22f, leftPatchCenter.y + patchRadiusY * 0.15f)
      )

      // Right Eyeball
      drawCircle(
        color = eyeBallColor,
        radius = eyeRadius,
        center = Offset(rightPatchCenter.x - patchRadiusX * 0.1f, rightPatchCenter.y + patchRadiusY * 0.05f)
      )
      // Right Eye Catchlight (Major Sparkle)
      drawCircle(
        color = Color.White,
        radius = eyeRadius * 0.38f,
        center = Offset(rightPatchCenter.x - patchRadiusX * 0.18f, rightPatchCenter.y - patchRadiusY * 0.12f)
      )
      // Right Eye Minor Sparkle
      drawCircle(
        color = Color.White.copy(alpha = 0.8f),
        radius = eyeRadius * 0.16f,
        center = Offset(rightPatchCenter.x + patchRadiusX * 0.02f, rightPatchCenter.y + patchRadiusY * 0.15f)
      )

      // 6. Rosy Cheeks (Soft pastel pink blushing)
      val cheekPink = Color(0xFFFF8FA3).copy(alpha = 0.42f)
      val cheekRadius = headRadiusX * 0.18f
      drawCircle(
        color = cheekPink,
        radius = cheekRadius,
        center = Offset(headCenterX - headRadiusX * 0.72f, headCenterY + headRadiusY * 0.28f)
      )
      drawCircle(
        color = cheekPink,
        radius = cheekRadius,
        center = Offset(headCenterX + headRadiusX * 0.72f, headCenterY + headRadiusY * 0.28f)
      )

      // 7. Cute Button Nose (Rounded triangle/oval)
      val noseCenter = Offset(headCenterX, headCenterY + headRadiusY * 0.16f)
      val noseRadiusX = headRadiusX * 0.15f
      val noseRadiusY = headRadiusY * 0.10f
      drawOval(
        color = Color(0xFF1E222A),
        topLeft = Offset(noseCenter.x - noseRadiusX, noseCenter.y - noseRadiusY),
        size = Size(noseRadiusX * 2f, noseRadiusY * 2f)
      )
      // Nose gloss highlight
      drawOval(
        color = Color.White.copy(alpha = 0.6f),
        topLeft = Offset(noseCenter.x - noseRadiusX * 0.5f, noseCenter.y - noseRadiusY * 0.6f),
        size = Size(noseRadiusX * 0.9f, noseRadiusY * 0.45f)
      )

      // 8. Happy Panda Smile (Path with double curve)
      val mouthPath = Path().apply {
        moveTo(noseCenter.x, noseCenter.y + noseRadiusY)
        lineTo(noseCenter.x, noseCenter.y + noseRadiusY * 1.8f)

        // Left smile curve
        moveTo(noseCenter.x, noseCenter.y + noseRadiusY * 1.8f)
        cubicTo(
          noseCenter.x - headRadiusX * 0.10f, noseCenter.y + headRadiusY * 0.36f,
          noseCenter.x - headRadiusX * 0.22f, noseCenter.y + headRadiusY * 0.32f,
          noseCenter.x - headRadiusX * 0.26f, noseCenter.y + headRadiusY * 0.22f
        )

        // Right smile curve
        moveTo(noseCenter.x, noseCenter.y + noseRadiusY * 1.8f)
        cubicTo(
          noseCenter.x + headRadiusX * 0.10f, noseCenter.y + headRadiusY * 0.36f,
          noseCenter.x + headRadiusX * 0.22f, noseCenter.y + headRadiusY * 0.32f,
          noseCenter.x + headRadiusX * 0.26f, noseCenter.y + headRadiusY * 0.22f
        )
      }

      drawPath(
        path = mouthPath,
        color = Color(0xFF1E222A),
        style = Stroke(width = 1.8f * density, cap = androidx.compose.ui.graphics.StrokeCap.Round)
      )

      // 9. Cyber / Tech Bowtie or Accent Badge (Remmi Signature)
      val badgeCenter = Offset(headCenterX, headCenterY + headRadiusY * 0.88f)
      drawCircle(
        color = accentColor,
        radius = headRadiusX * 0.14f,
        center = badgeCenter
      )
      drawCircle(
        color = Color.White,
        radius = headRadiusX * 0.06f,
        center = badgeCenter
      )
    }
  }
}
