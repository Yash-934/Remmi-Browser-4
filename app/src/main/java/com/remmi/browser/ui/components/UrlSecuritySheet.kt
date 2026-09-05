package com.remmi.browser.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.security.ClipboardManager
import com.remmi.browser.security.ContainerType
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.SecurityLevel
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UrlSecuritySheet(
  url: String,
  isSecure: Boolean,
  profile: PrivacyProfile,
  trackersBlocked: Int,
  securityLevel: SecurityLevel = SecurityLevel.STANDARD,
  containerType: ContainerType = ContainerType.fromProfile(profile),
  onSecurityLevelChange: ((SecurityLevel) -> Unit)? = null,
  onDismiss: () -> Unit,
  onInspectRedirects: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val clipboard = remember { ClipboardManager(context) }

  val isOnion = url.contains(".onion", ignoreCase = true)
  val isGhost = profile == PrivacyProfile.GHOST
  val accentColor = if (isGhost) ThemeCyber.colors.torPurple else ThemeCyber.colors.primary

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = ThemeCyber.colors.surface,
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    dragHandle = {
      Box(
        modifier = Modifier
          .padding(top = 12.dp, bottom = 8.dp)
          .size(width = 40.dp, height = 4.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(ThemeCyber.colors.surfaceBorder)
      )
    },
    modifier = modifier.testTag("url_security_sheet"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(bottom = 32.dp)
    ) {
      // Header
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
      ) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accentColor.copy(alpha = 0.15f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = if (isSecure) Icons.Default.Lock else Icons.Default.Warning,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(20.dp)
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "SECURITY & PRIVACY TELEMETRY",
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            color = ThemeCyber.colors.textPrimary
          )
          Text(
            text = url.substringBefore('?'),
            fontSize = 11.sp,
            fontFamily = CyberMonoFamily,
            color = ThemeCyber.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.Close, contentDescription = "Close", tint = ThemeCyber.colors.textMuted)
        }
      }

      // Security Level Selector
      if (onSecurityLevelChange != null) {
        Text(
          text = "SECURITY LEVEL",
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          color = ThemeCyber.colors.textSecondary,
          modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          SecurityLevel.values().forEach { level ->
            val isSelected = level == securityLevel
            val levelColor = when (level) {
              SecurityLevel.STANDARD -> ThemeCyber.colors.primary
              SecurityLevel.SAFER -> ThemeCyber.colors.warningYellow
              SecurityLevel.SAFEST -> ThemeCyber.colors.dangerRed
            }
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = if (isSelected) levelColor.copy(alpha = 0.2f) else ThemeCyber.colors.background,
              border = BorderStroke(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) levelColor else ThemeCyber.colors.surfaceBorder
              ),
              modifier = Modifier
                .weight(1f)
                .clickable { onSecurityLevelChange(level) }
            ) {
              Column(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Text(
                  text = level.displayName,
                  fontSize = 12.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                  color = if (isSelected) levelColor else ThemeCyber.colors.textPrimary,
                  fontFamily = ThemeCyber.fontFamily
                )
                Text(
                  text = when (level) {
                    SecurityLevel.STANDARD -> "All features"
                    SecurityLevel.SAFER -> "No media/SVG"
                    SecurityLevel.SAFEST -> "No JS/WebGL"
                  },
                  fontSize = 9.5.sp,
                  color = ThemeCyber.colors.textMuted,
                  fontFamily = CyberMonoFamily
                )
              }
            }
          }
        }
      }

      // Security Metric Cards
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        // 1. Connection Encryption
        SecurityStatusItem(
          icon = if (isSecure) Icons.Default.Shield else Icons.Default.Warning,
          iconTint = if (isSecure) ThemeCyber.colors.successGreen else ThemeCyber.colors.dangerRed,
          title = if (isSecure) "Encrypted Connection (TLS 1.3 / HTTPS)" else "Unencrypted Connection (Clearnet HTTP)",
          subtitle = if (isSecure) "Traffic encrypted against ISP interception" else "Data transmitted in plaintext. Exercise caution."
        )

        // 2. Tor Network Status & Container Isolation
        SecurityStatusItem(
          icon = Icons.Default.VpnLock,
          iconTint = if (isGhost) ThemeCyber.colors.torPurple else ThemeCyber.colors.textMuted,
          title = if (isGhost) "Tor Onion Routing Active" else "Clearnet Shield Active",
          subtitle = if (isGhost) {
            "SOCKS5 Port ${CurrentTorRoute.currentSocksPort ?: 9050} • Isolated ${containerType.displayName} Container"
          } else {
            "Direct connection • Isolated ${containerType.displayName} Container"
          }
        )

        // 3. Trackers & Defense
        SecurityStatusItem(
          icon = Icons.Default.Security,
          iconTint = ThemeCyber.colors.primary,
          title = "$trackersBlocked Trackers & Ads Neutralized",
          subtitle = "Third-party scripts, canvas fingerprinters & telemetry blocked"
        )

        // 4. Fingerprint Resistance
        SecurityStatusItem(
          icon = Icons.Default.Fingerprint,
          iconTint = if (isGhost) ThemeCyber.colors.torPurple else ThemeCyber.colors.primary,
          title = if (isGhost) "RFP Defense: Full (${securityLevel.displayName})" else "FPP Defense: Active (${securityLevel.displayName})",
          subtitle = if (isGhost) "Desktop UA, UTC spoofing, uniform canvas noise" else "Mobile UA, cookie partitioning & privacy buffers"
        )
      }

      Spacer(modifier = Modifier.height(20.dp))

      // Bottom Action Buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Button(
          onClick = {
            onDismiss()
            onInspectRedirects(url)
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = ThemeCyber.colors.surfaceLight,
            contentColor = ThemeCyber.colors.textPrimary
          ),
          border = BorderStroke(1.dp, ThemeCyber.colors.primary),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.weight(1f).height(44.dp)
        ) {
          Icon(Icons.Default.Visibility, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Inspect Link", fontSize = 12.sp, fontFamily = ThemeCyber.fontFamily, color = ThemeCyber.colors.primary)
        }

        Button(
          onClick = {
            clipboard.copy(url, "Current Page URL")
            Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = ThemeCyber.colors.surfaceLight,
            contentColor = ThemeCyber.colors.textPrimary
          ),
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.weight(1f).height(44.dp)
        ) {
          Icon(Icons.Default.ContentCopy, contentDescription = null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Copy URL", fontSize = 12.sp, color = ThemeCyber.colors.textPrimary)
        }
      }
    }
  }
}

@Composable
private fun SecurityStatusItem(
  icon: ImageVector,
  iconTint: Color,
  title: String,
  subtitle: String,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = ThemeCyber.colors.background,
    border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(32.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(iconTint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
      ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          fontSize = 12.5.sp,
          fontWeight = FontWeight.SemiBold,
          color = ThemeCyber.colors.textPrimary
        )
        Text(
          text = subtitle,
          fontSize = 11.sp,
          color = ThemeCyber.colors.textMuted,
          lineHeight = 14.sp
        )
      }
    }
  }
}
