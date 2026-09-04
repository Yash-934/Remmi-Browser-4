package com.remmi.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.GhostRoutePhase
import com.remmi.browser.security.TorCircuit
import com.remmi.browser.security.TorManager
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun CircuitVisualizerSheet(
  torState: TorManager.TorState,
  circuit: TorCircuit?,
  onRotateCircuit: () -> Unit,
  onStartTor: (() -> Unit)? = null,
  onLaunchOrbot: (() -> Unit)? = null,
  isOrbotInstalled: Boolean = false,
  onCheckTorProject: (() -> Unit)? = null,
  onDismiss: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
      .padding(16.dp)
      .verticalScroll(rememberScrollState())
  ) {
    // Header
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.VpnKey,
          contentDescription = null,
          tint = ThemeCyber.colors.torPurple,
          modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        GlitchText(
          text = "TOR ONION ROUTING",
          fontSize = 18.sp,
          color = ThemeCyber.colors.torPurple,
        )
      }

      IconButton(
        onClick = onDismiss,
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .background(ThemeCyber.colors.surfaceLight)
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "Close",
          tint = ThemeCyber.colors.textPrimary,
        )
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    val currentPort = circuit?.socksPort ?: (torState as? TorManager.TorState.READY)?.port ?: CurrentTorRoute.currentSocksPort ?: 0
    val currentPhase = CurrentTorRoute.currentPhase

    // SOCKS5 Status Card
    Card(
      colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
      shape = RoundedCornerShape(8.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, ThemeCyber.colors.torPurple.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "CIRCUIT ID: ${circuit?.circuitId ?: if (CurrentTorRoute.isReady) "ACTIVE" else "INACTIVE"}",
            color = ThemeCyber.colors.torPurple,
            fontFamily = CyberMonoFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
          )

          val (badgeText, badgeColor) = when {
            CurrentTorRoute.isReady -> Pair("GHOST READY ($currentPort)", ThemeCyber.colors.successGreen)
            currentPhase == GhostRoutePhase.ROTATING -> Pair("ROTATING CIRCUIT", ThemeCyber.colors.torPurple)
            currentPhase == GhostRoutePhase.STARTING_TOR -> Pair("STARTING TOR", ThemeCyber.colors.warningYellow)
            currentPhase == GhostRoutePhase.VERIFYING_TOR -> Pair("VERIFYING TOR", ThemeCyber.colors.warningYellow)
            currentPhase == GhostRoutePhase.APPLYING_GECKO -> Pair("APPLYING PROXY", ThemeCyber.colors.torPurple)
            currentPhase == GhostRoutePhase.VERIFYING_GECKO -> Pair("VERIFYING ROUTE", ThemeCyber.colors.torPurple)
            torState is TorManager.TorState.FAILED -> Pair("BLOCKED", ThemeCyber.colors.dangerRed)
            torState is TorManager.TorState.STOPPING -> Pair("STOPPING", ThemeCyber.colors.textMuted)
            torState is TorManager.TorState.OFF -> Pair("OFFLINE", ThemeCyber.colors.textMuted)
            else -> Pair("INITIALIZING", ThemeCyber.colors.warningYellow)
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .clip(RoundedCornerShape(4.dp))
              .background(badgeColor.copy(alpha = 0.15f))
              .padding(horizontal = 6.dp, vertical = 2.dp)
          ) {
            Box(
              modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(badgeColor)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = badgeText,
              color = badgeColor,
              fontFamily = CyberMonoFamily,
              fontSize = 9.sp,
              fontWeight = FontWeight.Bold,
            )
          }
        }

        Spacer(modifier = Modifier.height(6.dp))

        when {
          CurrentTorRoute.isReady -> {
            Text(
              text = "SOCKS5 127.0.0.1:$currentPort // FAILOVER_DIRECT=FALSE // REMOTE DNS",
              color = ThemeCyber.colors.successGreen,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
            if (circuit?.isVerifiedTor == true || CurrentTorRoute.isVerified) {
              Text(
                text = "✓ Verified with check.torproject.org (Exit IP: ${circuit?.verifiedExitIp ?: CurrentTorRoute.exitIp ?: "Protected"})",
                color = ThemeCyber.colors.primary,
                fontFamily = CyberMonoFamily,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp)
              )
            }
          }
          currentPhase == GhostRoutePhase.ROTATING -> {
            Text(
              text = "Rotating Tor onion circuit via SIGNAL NEWNYM...",
              color = ThemeCyber.colors.torPurple,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          currentPhase == GhostRoutePhase.STARTING_TOR -> {
            Text(
              text = "Launching native Tor daemon & confirming foreground service...",
              color = ThemeCyber.colors.warningYellow,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          currentPhase == GhostRoutePhase.VERIFYING_TOR -> {
            Text(
              text = "Verifying Tor daemon SOCKS5 protocol and remote exit routing...",
              color = ThemeCyber.colors.warningYellow,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          currentPhase == GhostRoutePhase.APPLYING_GECKO -> {
            Text(
              text = "Applying hardened proxy preferences to GeckoView engine...",
              color = ThemeCyber.colors.torPurple,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          currentPhase == GhostRoutePhase.VERIFYING_GECKO -> {
            Text(
              text = "Verifying end-to-end browser route through Tor...",
              color = ThemeCyber.colors.torPurple,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          torState is TorManager.TorState.FAILED -> {
            Text(
              text = "[${torState.category}] ${torState.message}",
              color = ThemeCyber.colors.dangerRed,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          torState is TorManager.TorState.STOPPING -> {
            Text(
              text = "Stopping Tor onion service...",
              color = ThemeCyber.colors.textMuted,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          torState is TorManager.TorState.OFF -> {
            Text(
              text = "Direct Clearnet Active (Shield Mode). Tor is offline.",
              color = ThemeCyber.colors.textMuted,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // 3-Hop Circuit Visualizer Nodes
    Text(
      text = "CIRCUIT HOPS",
      color = ThemeCyber.colors.textSecondary,
      fontFamily = CyberMonoFamily,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Card(
      colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
      shape = RoundedCornerShape(8.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        // Node 1: Entry Guard
        CircuitNodeRow(
          stepNumber = "01",
          nodeType = "ENTRY GUARD",
          nodeDesc = circuit?.guardNodeSummary ?: if (CurrentTorRoute.isReady) "Encrypted Entry Relay" else "Offline",
          icon = Icons.Default.Shield,
          isActive = CurrentTorRoute.isReady,
          tint = ThemeCyber.colors.primary,
        )

        Spacer(modifier = Modifier.height(10.dp))
        Box(
          modifier = Modifier
            .padding(start = 15.dp)
            .width(2.dp)
            .height(16.dp)
            .background(if (CurrentTorRoute.isReady) ThemeCyber.colors.torPurple else ThemeCyber.colors.surfaceBorder)
        )
        Spacer(modifier = Modifier.height(10.dp))

        // Node 2: Middle Relay
        CircuitNodeRow(
          stepNumber = "02",
          nodeType = "MIDDLE RELAY",
          nodeDesc = circuit?.middleNodeSummary ?: if (CurrentTorRoute.isReady) "Zero-Knowledge Relay" else "Offline",
          icon = Icons.Default.Lock,
          isActive = CurrentTorRoute.isReady,
          tint = ThemeCyber.colors.torPurple,
        )

        Spacer(modifier = Modifier.height(10.dp))
        Box(
          modifier = Modifier
            .padding(start = 15.dp)
            .width(2.dp)
            .height(16.dp)
            .background(if (CurrentTorRoute.isReady) ThemeCyber.colors.torPurple else ThemeCyber.colors.surfaceBorder)
        )
        Spacer(modifier = Modifier.height(10.dp))

        // Node 3: Exit Relay
        CircuitNodeRow(
          stepNumber = "03",
          nodeType = "EXIT RELAY",
          nodeDesc = circuit?.exitNodeSummary ?: if (CurrentTorRoute.isReady) "Verified Tor Exit (${circuit?.verifiedExitIp ?: "Protected"})" else "Offline",
          icon = Icons.Default.Language,
          isActive = CurrentTorRoute.isReady,
          tint = ThemeCyber.colors.successGreen,
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Action Buttons
    if (CurrentTorRoute.isReady) {
      Button(
        onClick = onRotateCircuit,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("rotate_circuit_button"),
        colors = ButtonDefaults.buttonColors(
          containerColor = ThemeCyber.colors.torPurple,
          contentColor = Color.White,
        ),
        shape = RoundedCornerShape(8.dp),
      ) {
        Icon(
          imageVector = Icons.Default.Autorenew,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "NEW IDENTITY (ROTATE CIRCUIT)",
          fontFamily = CyberMonoFamily,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      if (onCheckTorProject != null) {
        OutlinedButton(
          onClick = onCheckTorProject,
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.outlinedButtonColors(
            contentColor = ThemeCyber.colors.primary,
          ),
          shape = RoundedCornerShape(8.dp),
        ) {
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "VERIFY ON CHECK.TORPROJECT.ORG",
            fontFamily = CyberMonoFamily,
            fontSize = 12.sp,
          )
        }
      }
    } else if (onStartTor != null && torState !is TorManager.TorState.STOPPING && !torState.isConnecting) {
      Button(
        onClick = onStartTor,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
          containerColor = ThemeCyber.colors.primary,
          contentColor = Color.Black,
        ),
        shape = RoundedCornerShape(8.dp),
      ) {
        Icon(
          imageVector = Icons.Default.VpnKey,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = if (torState is TorManager.TorState.FAILED) "RETRY GHOST MODE" else "ACTIVATE GHOST MODE",
          fontFamily = CyberMonoFamily,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }

    if (!isOrbotInstalled && onLaunchOrbot != null) {
      Spacer(modifier = Modifier.height(8.dp))
      OutlinedButton(
        onClick = onLaunchOrbot,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(
          contentColor = ThemeCyber.colors.textSecondary,
        ),
        shape = RoundedCornerShape(8.dp),
      ) {
        Text(
          text = "GET ORBOT (STANDALONE TOR)",
          fontFamily = CyberMonoFamily,
          fontSize = 11.sp,
        )
      }
    }
  }
}

@Composable
private fun CircuitNodeRow(
  stepNumber: String,
  nodeType: String,
  nodeDesc: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  isActive: Boolean,
  tint: Color,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(32.dp)
        .clip(CircleShape)
        .background(if (isActive) tint.copy(alpha = 0.2f) else ThemeCyber.colors.surfaceLight)
        .border(1.dp, if (isActive) tint else ThemeCyber.colors.surfaceBorder, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (isActive) tint else ThemeCyber.colors.textMuted,
        modifier = Modifier.size(16.dp),
      )
    }

    Spacer(modifier = Modifier.width(12.dp))

    Column {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "$stepNumber // $nodeType",
          color = if (isActive) tint else ThemeCyber.colors.textMuted,
          fontFamily = CyberMonoFamily,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
        )
      }
      Text(
        text = nodeDesc,
        color = if (isActive) ThemeCyber.colors.textPrimary else ThemeCyber.colors.textMuted,
        fontFamily = CyberMonoFamily,
        fontSize = 11.sp,
      )
    }
  }
}
