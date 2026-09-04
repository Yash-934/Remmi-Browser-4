package com.remmi.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.GhostRoutePhase
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.TorManager
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun HudOverlay(
  trackerCount: Int,
  torState: TorManager.TorState,
  profile: PrivacyProfile,
  securityLevel: com.remmi.browser.security.SecurityLevel = com.remmi.browser.security.SecurityLevel.STANDARD,
  onTorClick: () -> Unit,
  onShieldClick: () -> Unit,
  onCircuitClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(ThemeCyber.colors.surface.copy(alpha = 0.95f))
      .border(0.5.dp, ThemeCyber.colors.surfaceBorder)
      .padding(horizontal = 12.dp, vertical = 6.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 1. Blocked tracker metric
      HudMetric(
        label = "NEUTRALIZED",
        value = "$trackerCount THREATS",
        color = if (trackerCount > 0) ThemeCyber.colors.secondary else ThemeCyber.colors.primary,
        onClick = onShieldClick,
      )

      // 2. Truthful Phase-Aware Tor Circuit State metric
      val routePhase = CurrentTorRoute.currentPhase
      val isLocked = (torState as? TorManager.TorState.FAILED)?.category == com.remmi.browser.security.TorErrorCategory.TOR_SERVICE_START_FAILED &&
          (torState as? TorManager.TorState.FAILED)?.message?.contains("Reset required") == true

      val (torLabel, torColor) = when {
        isLocked -> "TOR LOCKED (RETRY)" to ThemeCyber.colors.dangerRed
        routePhase == GhostRoutePhase.ROTATING -> "ROTATING CIRCUIT" to ThemeCyber.colors.torPurple
        routePhase == GhostRoutePhase.STARTING_TOR -> "STARTING TOR" to ThemeCyber.colors.warningYellow
        routePhase == GhostRoutePhase.VERIFYING_TOR -> "VERIFYING TOR" to ThemeCyber.colors.warningYellow
        routePhase == GhostRoutePhase.APPLYING_GECKO -> "APPLYING PROXY" to ThemeCyber.colors.torPurple
        routePhase == GhostRoutePhase.VERIFYING_GECKO -> "VERIFYING ROUTE" to ThemeCyber.colors.torPurple
        routePhase == GhostRoutePhase.READY && CurrentTorRoute.isReady -> "GHOST READY" to ThemeCyber.colors.successGreen
        torState is TorManager.TorState.OFF -> "CLEAN DIRECT" to ThemeCyber.colors.textSecondary
        torState is TorManager.TorState.FAILED -> "TOR BLOCKED" to ThemeCyber.colors.dangerRed
        torState is TorManager.TorState.STOPPING -> "STOPPING" to ThemeCyber.colors.textMuted
        torState is TorManager.TorState.READY && !CurrentTorRoute.isReady -> "VERIFYING ROUTE" to ThemeCyber.colors.torPurple
        else -> "CONNECTING..." to ThemeCyber.colors.warningYellow
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .clip(RoundedCornerShape(4.dp))
          .background(ThemeCyber.colors.surfaceLight)
          .border(0.8.dp, torColor.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
          .clickable { onCircuitClick() }
          .padding(horizontal = 8.dp, vertical = 4.dp),
      ) {
        Box(
          modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(torColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.Start) {
          Text(
            text = "NET MESH",
            fontSize = 6.sp,
            fontFamily = CyberMonoFamily,
            color = ThemeCyber.colors.textMuted,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = torLabel,
            fontSize = 9.sp,
            fontFamily = CyberMonoFamily,
            color = torColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      // 3. Security Profile Tag
      val profileColor = if (profile == PrivacyProfile.GHOST) ThemeCyber.colors.torPurple else ThemeCyber.colors.primary
      val secLevelTag = if (securityLevel != com.remmi.browser.security.SecurityLevel.STANDARD) " [${securityLevel.displayName}]" else ""
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .clip(RoundedCornerShape(4.dp))
          .background(profileColor.copy(alpha = 0.12f))
          .border(0.8.dp, profileColor, RoundedCornerShape(4.dp))
          .clickable { onShieldClick() }
          .padding(horizontal = 8.dp, vertical = 4.dp),
      ) {
        Text(
          text = if (profile == PrivacyProfile.GHOST) "GHOST // RFP$secLevelTag" else "SHIELD // FPP$secLevelTag",
          fontSize = 8.sp,
          fontFamily = CyberMonoFamily,
          color = profileColor,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

@Composable
private fun HudMetric(
  label: String,
  value: String,
  color: Color,
  onClick: () -> Unit,
) {
  Column(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .clickable { onClick() }
      .padding(horizontal = 4.dp, vertical = 2.dp),
    horizontalAlignment = Alignment.Start,
  ) {
    Text(
      text = label,
      fontSize = 6.sp,
      fontFamily = CyberMonoFamily,
      color = ThemeCyber.colors.textMuted,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = value,
      fontSize = 9.sp,
      fontFamily = CyberMonoFamily,
      color = color,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}
