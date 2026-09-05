package com.remmi.browser.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.security.ContainerType
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.SecurityLevel
import com.remmi.browser.security.SiteSecurityPolicyManager
import com.remmi.browser.security.SiteSecuritySettings
import com.remmi.browser.security.StorageIsolationManager
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.launch
import java.net.URI

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
  onStartElementBlocker: () -> Unit,
  onOpenGlobalSettings: () -> Unit,
  onReloadTab: () -> Unit,
  onInspectRedirects: ((String) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val host = remember(url) {
    try {
      URI(if (url.contains("://")) url else "https://$url").host?.lowercase() ?: url
    } catch (_: Exception) {
      url
    }
  }

  val sitePolicyManager = remember { SiteSecurityPolicyManager.getInstance(context) }
  val currentPolicy by remember(host) {
    derivedStateOf { sitePolicyManager.getPolicyForHost(host) }
  }

  var isAdvancedExpanded by remember { mutableStateOf(true) }

  // Dialog States
  var showShredConfirmDialog by remember { mutableStateOf(false) }
  var showTrackersAdsDialog by remember { mutableStateOf(false) }
  var showCookiePolicyDialog by remember { mutableStateOf(false) }
  var showHttpsUpgradeDialog by remember { mutableStateOf(false) }

  val shieldsUp = !currentPolicy.shieldsDown
  val isScriptsBlocked = currentPolicy.javascriptEnabled == false

  val siteInitial = remember(host) {
    val clean = host.removePrefix("www.").trim()
    if (clean.isNotEmpty()) clean.first().uppercaseChar().toString() else "W"
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = ThemeCyber.colors.surface,
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    dragHandle = {
      Box(
        modifier = Modifier
          .padding(top = 10.dp, bottom = 6.dp)
          .size(width = 38.dp, height = 4.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(ThemeCyber.colors.surfaceBorder)
      )
    },
    modifier = modifier.testTag("brave_shields_security_sheet"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .padding(bottom = 36.dp)
    ) {
      // --- HEADER ROW (Favicon/Initial + Domain + Shields Up/Down Subtitle + Master Switch) ---
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 12.dp)
      ) {
        // Site Initial Circle Avatar
        Box(
          modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (shieldsUp) ThemeCyber.colors.primary.copy(alpha = 0.18f) else ThemeCyber.colors.surfaceLight),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = siteInitial,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (shieldsUp) ThemeCyber.colors.primary else ThemeCyber.colors.textMuted
          )
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Domain & Shields Status Text
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = host.ifBlank { "Current Site" },
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = ThemeCyber.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = if (shieldsUp) "Shields up for this site" else "Shields down for this site",
            fontSize = 13.sp,
            color = if (shieldsUp) ThemeCyber.colors.primary else ThemeCyber.colors.textSecondary
          )
        }

        // Master Shields Switch
        Switch(
          checked = shieldsUp,
          onCheckedChange = { isChecked ->
            val updated = currentPolicy.copy(shieldsDown = !isChecked)
            sitePolicyManager.setPolicyForHost(updated)
            GeckoEngineManager.getInstance(context).applySiteSecurityPolicyToMatchingTabs(host)
            onReloadTab()
            val statusMsg = if (isChecked) "Shields enabled for $host" else "Shields disabled for $host"
            Toast.makeText(context, statusMsg, Toast.LENGTH_SHORT).show()
          },
          colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = ThemeCyber.colors.primary,
            uncheckedThumbColor = ThemeCyber.colors.textMuted,
            uncheckedTrackColor = ThemeCyber.colors.surfaceLight
          ),
          modifier = Modifier.testTag("shields_master_switch")
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      // --- SUMMARY BANNER (Blocked Count Card) ---
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = ThemeCyber.colors.background,
        border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "$trackersBlocked",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (trackersBlocked > 0) ThemeCyber.colors.primary else ThemeCyber.colors.textPrimary
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Trackers, ads, and more blocked.",
            fontSize = 14.sp,
            color = ThemeCyber.colors.textPrimary
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // --- ADVANCED CONTROLS ACCORDION HEADER ---
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable { isAdvancedExpanded = !isAdvancedExpanded }
          .padding(vertical = 8.dp, horizontal = 4.dp)
      ) {
        Text(
          text = "Advanced controls",
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          color = ThemeCyber.colors.primary
        )
        Icon(
          imageVector = if (isAdvancedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
          contentDescription = if (isAdvancedExpanded) "Collapse" else "Expand",
          tint = ThemeCyber.colors.primary
        )
      }

      // --- ADVANCED CONTROLS CONTENT ---
      AnimatedVisibility(
        visible = isAdvancedExpanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
      ) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = ThemeCyber.colors.background,
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
        ) {
          Column(modifier = Modifier.fillMaxWidth()) {

            // 1. Upgrade connections to HTTPS
            ShieldsOptionRow(
              title = "Upgrade connections to HTTPS",
              subtitle = if (isSecure) "HTTPS (Strict)" else "Standard",
              trailingContent = {
                Icon(
                  Icons.AutoMirrored.Filled.KeyboardArrowRight,
                  contentDescription = null,
                  tint = ThemeCyber.colors.textMuted,
                  modifier = Modifier.size(20.dp)
                )
              },
              onClick = { showHttpsUpgradeDialog = true }
            )

            HorizontalDivider(color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f), thickness = 0.8.dp)

            // 2. Block scripts
            ShieldsOptionRow(
              title = "Block scripts",
              subtitle = if (isScriptsBlocked) "JavaScript blocked" else "JavaScript allowed",
              trailingContent = {
                Switch(
                  checked = isScriptsBlocked,
                  onCheckedChange = { blockJs ->
                    val updated = currentPolicy.copy(javascriptEnabled = !blockJs)
                    sitePolicyManager.setPolicyForHost(updated)
                    GeckoEngineManager.getInstance(context).applySiteSecurityPolicyToMatchingTabs(host)
                    onReloadTab()
                    val msg = if (blockJs) "JavaScript blocked for $host" else "JavaScript allowed for $host"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                  },
                  colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = ThemeCyber.colors.primary,
                    uncheckedThumbColor = ThemeCyber.colors.textMuted,
                    uncheckedTrackColor = ThemeCyber.colors.surfaceLight
                  ),
                  modifier = Modifier.scale(0.85f)
                )
              },
              onClick = {
                val blockJs = !isScriptsBlocked
                val updated = currentPolicy.copy(javascriptEnabled = !blockJs)
                sitePolicyManager.setPolicyForHost(updated)
                GeckoEngineManager.getInstance(context).applySiteSecurityPolicyToMatchingTabs(host)
                onReloadTab()
              }
            )

            HorizontalDivider(color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f), thickness = 0.8.dp)

            // 3. Shred site's data
            ShieldsOptionRow(
              title = "Shred site's data",
              subtitle = "Clear cookies, cache & storage for this site",
              trailingContent = {
                Icon(
                  Icons.AutoMirrored.Filled.KeyboardArrowRight,
                  contentDescription = null,
                  tint = ThemeCyber.colors.textMuted,
                  modifier = Modifier.size(20.dp)
                )
              },
              onClick = { showShredConfirmDialog = true }
            )

            HorizontalDivider(color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f), thickness = 0.8.dp)

            // 4. Block trackers & ads
            val adBlockMode = when (currentPolicy.cosmeticPolicy) {
              "ENABLED" -> "Aggressive"
              "DISABLED" -> "Allow"
              else -> "Aggressive"
            }
            ShieldsOptionRow(
              title = "Block trackers & ads",
              subtitle = adBlockMode,
              trailingContent = {
                Icon(
                  Icons.AutoMirrored.Filled.KeyboardArrowRight,
                  contentDescription = null,
                  tint = ThemeCyber.colors.textMuted,
                  modifier = Modifier.size(20.dp)
                )
              },
              onClick = { showTrackersAdsDialog = true }
            )

            HorizontalDivider(color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f), thickness = 0.8.dp)

            // 5. Block cookies
            val cookieDisplay = when (currentPolicy.cookiePolicy) {
              "BLOCK" -> "Block all"
              "ALLOW" -> "Allow all"
              else -> "Block 3rd-party"
            }
            ShieldsOptionRow(
              title = "Block cookies",
              subtitle = cookieDisplay,
              trailingContent = {
                Icon(
                  Icons.AutoMirrored.Filled.KeyboardArrowRight,
                  contentDescription = null,
                  tint = ThemeCyber.colors.textMuted,
                  modifier = Modifier.size(20.dp)
                )
              },
              onClick = { showCookiePolicyDialog = true }
            )

            HorizontalDivider(color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f), thickness = 0.8.dp)

            // 6. Block fingerprinting
            val isFingerprintBlocked = currentPolicy.customSecurityLevel != SecurityLevel.STANDARD || shieldsUp
            ShieldsOptionRow(
              title = "Block fingerprinting",
              subtitle = if (isFingerprintBlocked) "Canvas & telemetry defense active" else "Standard protection",
              trailingContent = {
                Switch(
                  checked = isFingerprintBlocked,
                  onCheckedChange = { blockFp ->
                    val newSecLevel = if (blockFp) SecurityLevel.SAFER else SecurityLevel.STANDARD
                    val updated = currentPolicy.copy(customSecurityLevel = newSecLevel)
                    sitePolicyManager.setPolicyForHost(updated)
                    GeckoEngineManager.getInstance(context).applySiteSecurityPolicyToMatchingTabs(host)
                    onReloadTab()
                  },
                  colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = ThemeCyber.colors.primary,
                    uncheckedThumbColor = ThemeCyber.colors.textMuted,
                    uncheckedTrackColor = ThemeCyber.colors.surfaceLight
                  ),
                  modifier = Modifier.scale(0.85f)
                )
              },
              onClick = {
                val blockFp = !isFingerprintBlocked
                val newSecLevel = if (blockFp) SecurityLevel.SAFER else SecurityLevel.STANDARD
                val updated = currentPolicy.copy(customSecurityLevel = newSecLevel)
                sitePolicyManager.setPolicyForHost(updated)
                GeckoEngineManager.getInstance(context).applySiteSecurityPolicyToMatchingTabs(host)
                onReloadTab()
              }
            )

            HorizontalDivider(color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f), thickness = 0.8.dp)

            // 7. Block element (Interactive Element Picker)
            ShieldsOptionRow(
              title = "Block element",
              subtitle = "Select and zap unwanted ads or elements",
              trailingContent = {
                Icon(
                  Icons.Default.OpenInNew,
                  contentDescription = "Launch Element Blocker",
                  tint = ThemeCyber.colors.primary,
                  modifier = Modifier.size(19.dp)
                )
              },
              onClick = {
                onDismiss()
                onStartElementBlocker()
              }
            )

            HorizontalDivider(color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f), thickness = 0.8.dp)

            // 8. Shields global settings
            ShieldsOptionRow(
              title = "Shields global settings",
              titleColor = ThemeCyber.colors.primary,
              subtitle = "Configure global privacy defaults",
              trailingContent = {
                Icon(
                  Icons.Default.OpenInNew,
                  contentDescription = "Open Settings",
                  tint = ThemeCyber.colors.primary,
                  modifier = Modifier.size(19.dp)
                )
              },
              onClick = {
                onDismiss()
                onOpenGlobalSettings()
              }
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(20.dp))

      // --- FOOTER DISCLAIMER ---
      Text(
        text = "If this site appears broken, try Shields down.\nNote: this may reduce Remmi's privacy protections.",
        fontSize = 12.sp,
        lineHeight = 16.sp,
        textAlign = TextAlign.Center,
        color = ThemeCyber.colors.textMuted,
        modifier = Modifier.fillMaxWidth()
      )
    }
  }

  // --- CONFIRMATION DIALOG: Shred Site Data ---
  if (showShredConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showShredConfirmDialog = false },
      title = {
        Text("Shred site's data?", fontWeight = FontWeight.Bold, color = ThemeCyber.colors.textPrimary)
      },
      text = {
        Text(
          "This will delete all cookies, cache, local storage, and session data for $host and reload the page.",
          color = ThemeCyber.colors.textSecondary,
          fontSize = 13.5.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showShredConfirmDialog = false
            scope.launch {
              val runtime = GeckoEngineManager.getInstance(context).runtime
              if (runtime != null) {
                StorageIsolationManager.getInstance(context).clearSiteDataForHost(runtime, host)
              }
              Toast.makeText(context, "Site data shredded for $host", Toast.LENGTH_SHORT).show()
              onReloadTab()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.dangerRed)
        ) {
          Text("Shred Data", color = Color.White)
        }
      },
      dismissButton = {
        TextButton(onClick = { showShredConfirmDialog = false }) {
          Text("Cancel", color = ThemeCyber.colors.textMuted)
        }
      },
      containerColor = ThemeCyber.colors.surface,
      shape = RoundedCornerShape(16.dp)
    )
  }

  // --- DIALOG: Block Trackers & Ads ---
  if (showTrackersAdsDialog) {
    AlertDialog(
      onDismissRequest = { showTrackersAdsDialog = false },
      title = {
        Text("Block trackers & ads", fontWeight = FontWeight.Bold, color = ThemeCyber.colors.textPrimary)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          val modes = listOf(
            "Aggressive" to "Aggressive (Block ads & cosmetic elements)",
            "DEFAULT" to "Standard (Block recognized trackers)",
            "DISABLED" to "Allow ads & trackers on this site"
          )
          modes.forEach { (modeKey, label) ->
            val isSelected = when (modeKey) {
              "Aggressive" -> currentPolicy.cosmeticPolicy == "ENABLED"
              "DISABLED" -> currentPolicy.cosmeticPolicy == "DISABLED"
              else -> currentPolicy.cosmeticPolicy == "DEFAULT"
            }
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = if (isSelected) ThemeCyber.colors.primary.copy(alpha = 0.15f) else ThemeCyber.colors.surfaceLight,
              border = BorderStroke(1.dp, if (isSelected) ThemeCyber.colors.primary else ThemeCyber.colors.surfaceBorder),
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  val updated = currentPolicy.copy(
                    cosmeticPolicy = when (modeKey) {
                      "Aggressive" -> "ENABLED"
                      "DISABLED" -> "DISABLED"
                      else -> "DEFAULT"
                    }
                  )
                  sitePolicyManager.setPolicyForHost(updated)
                  GeckoEngineManager.getInstance(context).applySiteSecurityPolicyToMatchingTabs(host)
                  showTrackersAdsDialog = false
                  onReloadTab()
                }
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                RadioButton(
                  selected = isSelected,
                  onClick = null,
                  colors = RadioButtonDefaults.colors(selectedColor = ThemeCyber.colors.primary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, fontSize = 13.sp, color = ThemeCyber.colors.textPrimary)
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showTrackersAdsDialog = false }) {
          Text("Done", color = ThemeCyber.colors.primary)
        }
      },
      containerColor = ThemeCyber.colors.surface,
      shape = RoundedCornerShape(16.dp)
    )
  }

  // --- DIALOG: Block Cookies ---
  if (showCookiePolicyDialog) {
    AlertDialog(
      onDismissRequest = { showCookiePolicyDialog = false },
      title = {
        Text("Block cookies", fontWeight = FontWeight.Bold, color = ThemeCyber.colors.textPrimary)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          val cookieOptions = listOf(
            "ISOLATE" to "Block 3rd-party cookies (Recommended)",
            "BLOCK" to "Block all cookies (May break logins)",
            "ALLOW" to "Allow all cookies"
          )
          cookieOptions.forEach { (optKey, label) ->
            val isSelected = currentPolicy.cookiePolicy == optKey
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = if (isSelected) ThemeCyber.colors.primary.copy(alpha = 0.15f) else ThemeCyber.colors.surfaceLight,
              border = BorderStroke(1.dp, if (isSelected) ThemeCyber.colors.primary else ThemeCyber.colors.surfaceBorder),
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  val updated = currentPolicy.copy(cookiePolicy = optKey)
                  sitePolicyManager.setPolicyForHost(updated)
                  GeckoEngineManager.getInstance(context).applySiteSecurityPolicyToMatchingTabs(host)
                  showCookiePolicyDialog = false
                  onReloadTab()
                }
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                RadioButton(
                  selected = isSelected,
                  onClick = null,
                  colors = RadioButtonDefaults.colors(selectedColor = ThemeCyber.colors.primary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, fontSize = 13.sp, color = ThemeCyber.colors.textPrimary)
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showCookiePolicyDialog = false }) {
          Text("Done", color = ThemeCyber.colors.primary)
        }
      },
      containerColor = ThemeCyber.colors.surface,
      shape = RoundedCornerShape(16.dp)
    )
  }

  // --- DIALOG: Upgrade Connections to HTTPS ---
  if (showHttpsUpgradeDialog) {
    AlertDialog(
      onDismissRequest = { showHttpsUpgradeDialog = false },
      title = {
        Text("Upgrade connections to HTTPS", fontWeight = FontWeight.Bold, color = ThemeCyber.colors.textPrimary)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = if (isSecure) "This connection is already securely encrypted with TLS 1.3 / HTTPS." else "This site is using unencrypted HTTP. Remmi automatically upgrades insecure requests to HTTPS whenever supported.",
            fontSize = 13.sp,
            color = ThemeCyber.colors.textSecondary
          )
        }
      },
      confirmButton = {
        TextButton(onClick = { showHttpsUpgradeDialog = false }) {
          Text("OK", color = ThemeCyber.colors.primary)
        }
      },
      containerColor = ThemeCyber.colors.surface,
      shape = RoundedCornerShape(16.dp)
    )
  }
}

@Composable
private fun ShieldsOptionRow(
  title: String,
  titleColor: Color = ThemeCyber.colors.textPrimary,
  subtitle: String? = null,
  trailingContent: @Composable () -> Unit,
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        fontSize = 14.5.sp,
        fontWeight = FontWeight.Medium,
        color = titleColor
      )
      if (!subtitle.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = subtitle,
          fontSize = 12.sp,
          color = ThemeCyber.colors.textSecondary
        )
      }
    }
    Spacer(modifier = Modifier.width(8.dp))
    trailingContent()
  }
}
