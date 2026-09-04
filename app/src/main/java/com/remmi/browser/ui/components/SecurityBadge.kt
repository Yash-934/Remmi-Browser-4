package com.remmi.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

data class SecurityFeature(
  val name: String,
  val status: String,
  val isHardened: Boolean,
  val description: String,
)

@Composable
fun SecurityShieldSheet(
  profile: PrivacyProfile,
  blockedCount: Int,
  blockedLog: List<String>,
  onToggleProfile: () -> Unit,
  onDismiss: () -> Unit,
) {
  val isGhost = profile == PrivacyProfile.GHOST
  val profileColor = if (isGhost) ThemeCyber.colors.torPurple else ThemeCyber.colors.primary

  val features = listOf(
    SecurityFeature(
      name = if (isGhost) "Full Resist Fingerprinting (RFP)" else "Fingerprinting Protection (FPP)",
      status = "ACTIVE // ENFORCED",
      isHardened = true,
      description = if (isGhost) "Spoofs uniform Tor Browser identity, letterboxing, UTC timezone, and canvas randomization." else "Granular isolation, prevents canvas/audio/hardware fingerprint tracking."
    ),
    SecurityFeature(
      name = "WebRTC Leak Neutralizer",
      status = "BLOCKED",
      isHardened = true,
      description = "RTCPeerConnection completely disabled. Zero local or public IP address leaks."
    ),
    SecurityFeature(
      name = "Native Adblock & Tracker Filter",
      status = "$blockedCount NEUTRALIZED",
      isHardened = true,
      description = "EasyList + EasyPrivacy + Anti-Telemetry engine active across all network requests."
    ),
    SecurityFeature(
      name = "Cookie & Storage Isolation (dFPI)",
      status = "ISOLATED",
      isHardened = true,
      description = "Dynamic first-party partition sandbox. Third-party cross-site cookies are sandboxed."
    ),
    SecurityFeature(
      name = "Hardware & Sensor Neutralizer",
      status = "LOCKED",
      isHardened = true,
      description = "Battery status, Gamepad, Vibrator, and DeviceOrientation APIs strictly disabled."
    ),
    SecurityFeature(
      name = "HTTPS-Only Network Guard",
      status = "FORCE TLS",
      isHardened = true,
      description = "Automatic upgrade to encrypted TLS. Cleartext unencrypted traffic is dropped."
    ),
  )

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
      .padding(16.dp)
  ) {
    // Header
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = if (isGhost) Icons.Default.VpnKey else Icons.Default.Shield,
          contentDescription = null,
          tint = profileColor,
          modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        GlitchText(
          text = if (isGhost) "GHOST MODE SHIELD" else "SHIELD MODE DEFENSE",
          fontSize = 17.sp,
          color = profileColor,
        )
      }

      IconButton(
        onClick = onDismiss,
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .background(ThemeCyber.colors.surfaceLight)
          .testTag("dismiss_security_sheet_button")
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "Close",
          tint = ThemeCyber.colors.textPrimary,
        )
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Switch Profile CTA Card
    Card(
      colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
      shape = RoundedCornerShape(8.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, profileColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = if (isGhost) "CURRENT: TOR GHOST ANONYMITY" else "CURRENT: HIGH SPEED SHIELD",
              color = profileColor,
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = if (isGhost) "Switch to Shield for maximum clearnet performance." else "Switch to Ghost for full Tor onion routing & maximum anonymity.",
              color = ThemeCyber.colors.textSecondary,
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 11.sp,
            )
          }

          Spacer(modifier = Modifier.width(8.dp))

          Button(
            onClick = onToggleProfile,
            colors = ButtonDefaults.buttonColors(
              containerColor = profileColor,
              contentColor = if (ThemeCyber.colors.isLight) Color.White else ThemeCyber.colors.backgroundDarker,
            ),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.testTag("toggle_privacy_profile_button")
          ) {
            Text(
              text = if (isGhost) "USE SHIELD" else "USE GHOST",
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = "HARDENED SECURITY MATRIX",
      color = ThemeCyber.colors.textMuted,
      fontFamily = ThemeCyber.fontFamily,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
    )

    Spacer(modifier = Modifier.height(8.dp))

    LazyColumn(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      items(features) { feature ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(ThemeCyber.colors.surface)
            .border(0.6.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(6.dp))
            .padding(12.dp),
          verticalAlignment = Alignment.Top,
        ) {
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = ThemeCyber.colors.successGreen,
            modifier = Modifier
              .size(18.dp)
              .padding(top = 2.dp),
          )

          Spacer(modifier = Modifier.width(10.dp))

          Column(modifier = Modifier.weight(1f)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = feature.name,
                color = ThemeCyber.colors.textPrimary,
                fontFamily = ThemeCyber.fontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = feature.status,
                color = ThemeCyber.colors.successGreen,
                fontFamily = ThemeCyber.fontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
              )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
              text = feature.description,
              color = ThemeCyber.colors.textSecondary,
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 10.sp,
            )
          }
        }
      }

      if (blockedLog.isNotEmpty()) {
        item {
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text = "RECENT INTERCEPTED TRACKERS",
            color = ThemeCyber.colors.secondary,
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
          )
          Spacer(modifier = Modifier.height(6.dp))
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(6.dp))
              .background(ThemeCyber.colors.surface)
              .border(0.6.dp, ThemeCyber.colors.secondary.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
              .padding(10.dp)
          ) {
            blockedLog.take(6).forEach { host ->
              Text(
                text = "⚡ [BLOCKED] $host",
                color = ThemeCyber.colors.secondary,
                fontFamily = ThemeCyber.fontFamily,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 2.dp)
              )
            }
          }
        }
      }
    }
  }
}
