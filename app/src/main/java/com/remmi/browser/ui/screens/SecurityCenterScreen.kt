package com.remmi.browser.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.engine.BrowserTab
import com.remmi.browser.security.ContainerType
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.DnsProvider
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.SecurityLevel
import com.remmi.browser.security.SiteSecurityPolicyManager
import com.remmi.browser.security.SiteSecuritySettings
import com.remmi.browser.security.TorCircuit
import com.remmi.browser.security.TorManager
import com.remmi.browser.storage.SettingsRepository
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityCenterScreen(
  activeTab: BrowserTab,
  sitePolicyManager: SiteSecurityPolicyManager,
  settingsRepo: SettingsRepository,
  torManager: TorManager? = null,
  onSecurityLevelChange: (SecurityLevel) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier
) {
  val scope = rememberCoroutineScope()
  val context = androidx.compose.ui.platform.LocalContext.current
  val privacyController = androidx.compose.runtime.remember { com.remmi.browser.security.PrivacyNetworkController.getInstance(context) }
  val settings by settingsRepo.settings.collectAsState()
  val torStateFlow = remember(torManager) { torManager?.bootstrapState ?: MutableStateFlow<TorManager.TorState>(TorManager.TorState.OFF) }
  val torState by torStateFlow.collectAsState()
  val torCircuitFlow = remember(torManager) { torManager?.currentCircuit ?: MutableStateFlow<TorCircuit?>(null) }
  val torCircuit by torCircuitFlow.collectAsState()

  var showDnsMenu by remember { mutableStateOf(false) }
  var newnymStatusMessage by remember { mutableStateOf<String?>(null) }
  var isRotatingCircuit by remember { mutableStateOf(false) }

  val host = remember(activeTab.url) {
    try {
      java.net.URI(activeTab.url).host ?: "active-domain"
    } catch (_: Exception) {
      "active-domain"
    }
  }

  val policies by sitePolicyManager.policies.collectAsState()
  val currentSitePolicy = remember(policies, host) {
    sitePolicyManager.getPolicyForHost(host)
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Shield,
              contentDescription = null,
              tint = ThemeCyber.colors.primary,
              modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "SECURITY CENTER",
              fontFamily = ThemeCyber.fontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 16.sp,
              color = ThemeCyber.colors.textPrimary
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
              tint = ThemeCyber.colors.textPrimary
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = ThemeCyber.colors.surface
        )
      )
    },
    containerColor = ThemeCyber.colors.background
  ) { padding ->
    LazyColumn(
      modifier = modifier
        .fillMaxSize()
        .padding(padding)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // 1. Overall Security Posture Card
      item {
        val score = when {
          activeTab.securityLevel == SecurityLevel.SAFEST && activeTab.isSecure -> 98
          activeTab.securityLevel == SecurityLevel.SAFER && activeTab.isSecure -> 88
          activeTab.isSecure -> 76
          else -> 42
        }
        val scoreColor = when {
          score >= 85 -> ThemeCyber.colors.primary
          score >= 60 -> ThemeCyber.colors.warningYellow
          else -> ThemeCyber.colors.dangerRed
        }

        Card(
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "SECURITY POSTURE",
                fontSize = 12.sp,
                fontFamily = ThemeCyber.fontFamily,
                color = ThemeCyber.colors.textSecondary
              )
              Text(
                text = if (score >= 80) "EXCELLENT PROTECTION" else if (score >= 60) "MODERATE PROTECTION" else "WEAK DEFENSE",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = scoreColor,
                fontFamily = ThemeCyber.fontFamily
              )
              Text(
                text = "Isolated ${activeTab.containerType.displayName} • ${if (activeTab.isSecure) "HTTPS Encrypted" else "Plain HTTP"}",
                fontSize = 11.sp,
                color = ThemeCyber.colors.textMuted,
                fontFamily = CyberMonoFamily
              )
            }
            Box(
              modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(scoreColor.copy(alpha = 0.15f))
                .padding(4.dp),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = "$score",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = scoreColor,
                fontFamily = CyberMonoFamily
              )
            }
          }
        }
      }

      // 2. Tor-Style Security Level Selector
      item {
        Text(
          text = "GLOBAL SECURITY LEVEL",
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          color = ThemeCyber.colors.textSecondary,
          fontFamily = ThemeCyber.fontFamily
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          SecurityLevel.values().forEach { level ->
            val isSelected = level == activeTab.securityLevel
            val levelColor = when (level) {
              SecurityLevel.STANDARD -> ThemeCyber.colors.primary
              SecurityLevel.SAFER -> ThemeCyber.colors.warningYellow
              SecurityLevel.SAFEST -> ThemeCyber.colors.dangerRed
            }
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = if (isSelected) levelColor.copy(alpha = 0.2f) else ThemeCyber.colors.surface,
              border = BorderStroke(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) levelColor else ThemeCyber.colors.surfaceBorder
              ),
              modifier = Modifier
                .weight(1f)
                .clickable { onSecurityLevelChange(level) }
            ) {
              Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Text(
                  text = level.displayName,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (isSelected) levelColor else ThemeCyber.colors.textPrimary,
                  fontFamily = ThemeCyber.fontFamily
                )
                Text(
                  text = level.tag,
                  fontSize = 9.sp,
                  color = ThemeCyber.colors.textMuted,
                  fontFamily = CyberMonoFamily
                )
              }
            }
          }
        }
      }

      // 3. Network & DNS Hardening (Milestone 3)
      item {
        Text(
          text = "ENCRYPTED DNS & NETWORK HARDENING",
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          color = ThemeCyber.colors.textSecondary,
          fontFamily = ThemeCyber.fontFamily
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // DNS Provider Selector
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { showDnsMenu = true },
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Encrypted DNS (DoH) Resolver",
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Medium,
                  color = ThemeCyber.colors.textPrimary
                )
                Text(
                  text = settings.dnsProvider.displayName,
                  fontSize = 11.sp,
                  color = ThemeCyber.colors.neonCyan,
                  fontFamily = CyberMonoFamily
                )
              }
              Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = ThemeCyber.colors.textSecondary
              )

              DropdownMenu(
                expanded = showDnsMenu,
                onDismissRequest = { showDnsMenu = false }
              ) {
                DnsProvider.values().forEach { provider ->
                  DropdownMenuItem(
                    text = {
                      Column {
                        Text(
                          text = provider.displayName,
                          fontWeight = if (provider == settings.dnsProvider) FontWeight.Bold else FontWeight.Normal,
                          color = if (provider == settings.dnsProvider) ThemeCyber.colors.primary else ThemeCyber.colors.textPrimary
                        )
                        Text(
                          text = provider.description,
                          fontSize = 10.sp,
                          color = ThemeCyber.colors.textMuted
                        )
                      }
                    },
                    onClick = {
                      settingsRepo.updateDnsProvider(provider)
                      showDnsMenu = false
                    }
                  )
                }
              }
            }

            HorizontalDivider(color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f))

            // ECH (Encrypted Client Hello)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Encrypted Client Hello (ECH)",
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Medium,
                  color = ThemeCyber.colors.textPrimary
                )
                Text(
                  text = "Hides Server Name Indication (SNI) from network snoopers",
                  fontSize = 11.sp,
                  color = ThemeCyber.colors.textMuted,
                  fontFamily = CyberMonoFamily
                )
              }
              Switch(
                checked = settings.encryptedClientHelloEnabled,
                onCheckedChange = { settingsRepo.updateEchEnabled(it) },
                colors = SwitchDefaults.colors(
                  checkedThumbColor = ThemeCyber.colors.primary,
                  checkedTrackColor = ThemeCyber.colors.primary.copy(alpha = 0.4f)
                )
              )
            }

            HorizontalDivider(color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f))

            // Global Privacy Control (GPC)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Global Privacy Control (GPC)",
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Medium,
                  color = ThemeCyber.colors.textPrimary
                )
                Text(
                  text = "Broadcasts Sec-GPC=1 to legally opt-out of data sales",
                  fontSize = 11.sp,
                  color = ThemeCyber.colors.textMuted,
                  fontFamily = CyberMonoFamily
                )
              }
              Switch(
                checked = settings.globalPrivacyControlEnabled,
                onCheckedChange = { settingsRepo.updateGpcEnabled(it) },
                colors = SwitchDefaults.colors(
                  checkedThumbColor = ThemeCyber.colors.primary,
                  checkedTrackColor = ThemeCyber.colors.primary.copy(alpha = 0.4f)
                )
              )
            }

            HorizontalDivider(color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f))

            // Strict Referrer Trimming
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Strict Referrer Trimming",
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Medium,
                  color = ThemeCyber.colors.textPrimary
                )
                Text(
                  text = "Trims cross-origin referrer URLs to origin root only",
                  fontSize = 11.sp,
                  color = ThemeCyber.colors.textMuted,
                  fontFamily = CyberMonoFamily
                )
              }
              Switch(
                checked = settings.strictReferrerPolicy,
                onCheckedChange = { settingsRepo.updateStrictReferrerPolicy(it) },
                colors = SwitchDefaults.colors(
                  checkedThumbColor = ThemeCyber.colors.primary,
                  checkedTrackColor = ThemeCyber.colors.primary.copy(alpha = 0.4f)
                )
              )
            }
          }
        }
      }

      // 4. Tor Onion & Circuit Identity Manager
      if (torManager != null) {
        item {
          Text(
            text = "TOR ONION & CIRCUIT HUB",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = ThemeCyber.colors.textSecondary,
            fontFamily = ThemeCyber.fontFamily
          )
          Spacer(modifier = Modifier.height(8.dp))
          Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
            border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = ThemeCyber.colors.torPurple,
                    modifier = Modifier.size(18.dp)
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = "Tor Daemon Status",
                    fontSize = 13.sp,
                    color = ThemeCyber.colors.textPrimary
                  )
                }
                Text(
                  text = when (torState) {
                    is TorManager.TorState.READY -> "READY (PORT ${(torState as TorManager.TorState.READY).port})"
                    is TorManager.TorState.TOR_BOOTSTRAPPING -> "BOOTSTRAPPING (${(torState as TorManager.TorState.TOR_BOOTSTRAPPING).bootstrapProgress}%)"
                    is TorManager.TorState.STARTING_SERVICE -> "STARTING..."
                    is TorManager.TorState.FAILED -> "FAILED"
                    else -> "STANDBY"
                  },
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (torState is TorManager.TorState.READY) ThemeCyber.colors.primary else ThemeCyber.colors.warningYellow,
                  fontFamily = CyberMonoFamily
                )
              }

              if (torCircuit?.verifiedExitIp != null) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Text(
                    text = "Verified Exit Node IP",
                    fontSize = 12.sp,
                    color = ThemeCyber.colors.textSecondary
                  )
                  Text(
                    text = torCircuit?.verifiedExitIp ?: "Hidden",
                    fontSize = 12.sp,
                    fontFamily = CyberMonoFamily,
                    color = ThemeCyber.colors.neonCyan
                  )
                }
              }

              Button(
                onClick = {
                  if (!isRotatingCircuit) {
                    isRotatingCircuit = true
                    newnymStatusMessage = "Requesting new circuit identity..."
                    scope.launch {
                      val res = privacyController.rotateTorCircuit()
                      isRotatingCircuit = false
                      newnymStatusMessage = if (res.isSuccess) {
                        "New Tor Identity established successfully!"
                      } else {
                        res.exceptionOrNull()?.message ?: "Rotation pending"
                      }
                    }
                  }
                },
                enabled = !isRotatingCircuit && torState is TorManager.TorState.READY,
                colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.torPurple),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
              ) {
                Icon(
                  imageVector = Icons.Default.Refresh,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                  tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = if (isRotatingCircuit) "ROTATING CIRCUIT..." else "REQUEST NEW TOR IDENTITY",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  fontFamily = CyberMonoFamily,
                  color = Color.White
                )
              }

              if (newnymStatusMessage != null) {
                Text(
                  text = newnymStatusMessage ?: "",
                  fontSize = 11.sp,
                  color = ThemeCyber.colors.neonCyan,
                  fontFamily = CyberMonoFamily
                )
              }
            }
          }
        }
      }

      // 5. Granular Tracker & Ad Classification Breakdown
      item {
        Text(
          text = "TRACKER DEFENSE CLASSIFICATION",
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          color = ThemeCyber.colors.textSecondary,
          fontFamily = ThemeCyber.fontFamily
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TrackerStatRow(
              icon = Icons.Default.Block,
              title = "Ads & Promotional Trackers",
              count = activeTab.adsBlockedCount,
              tint = ThemeCyber.colors.primary
            )
            TrackerStatRow(
              icon = Icons.Default.Analytics,
              title = "Analytics & Telemetry Beacons",
              count = activeTab.analyticsBlockedCount,
              tint = ThemeCyber.colors.neonCyan
            )
            TrackerStatRow(
              icon = Icons.Default.Share,
              title = "Social Tracking & Login Pixels",
              count = activeTab.socialBlockedCount,
              tint = ThemeCyber.colors.torPurple
            )
            TrackerStatRow(
              icon = Icons.Default.Memory,
              title = "Cryptomining Scripts",
              count = activeTab.cryptomineBlockedCount,
              tint = ThemeCyber.colors.dangerRed
            )
            TrackerStatRow(
              icon = Icons.Default.Fingerprint,
              title = "Fingerprint Probing Attempts",
              count = activeTab.fingerprintBlockedCount,
              tint = ThemeCyber.colors.warningYellow
            )
          }
        }
      }

      // 6. Per-Site Permissions / Overrides for current domain
      item {
        Text(
          text = "PER-SITE CONTROLS ($host)",
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          color = ThemeCyber.colors.textSecondary,
          fontFamily = ThemeCyber.fontFamily
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column {
                Text(
                  text = "JavaScript Execution",
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Medium,
                  color = ThemeCyber.colors.textPrimary
                )
                Text(
                  text = if (currentSitePolicy.javascriptEnabled == null) "Default (${activeTab.securityLevel.displayName})" else if (currentSitePolicy.javascriptEnabled == true) "Allowed" else "Blocked",
                  fontSize = 11.sp,
                  color = ThemeCyber.colors.textMuted,
                  fontFamily = CyberMonoFamily
                )
              }
              Switch(
                checked = currentSitePolicy.javascriptEnabled ?: activeTab.securityLevel.javascriptEnabled,
                onCheckedChange = { isChecked ->
                  sitePolicyManager.setPolicyForHost(
                    currentSitePolicy.copy(javascriptEnabled = isChecked)
                  )
                },
                colors = SwitchDefaults.colors(
                  checkedThumbColor = ThemeCyber.colors.primary,
                  checkedTrackColor = ThemeCyber.colors.primary.copy(alpha = 0.4f)
                )
              )
            }

            HorizontalDivider(color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column {
                Text(
                  text = "Block Unsolicited Popups",
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Medium,
                  color = ThemeCyber.colors.textPrimary
                )
                Text(
                  text = "Prevent redirects and new window spawns",
                  fontSize = 11.sp,
                  color = ThemeCyber.colors.textMuted,
                  fontFamily = CyberMonoFamily
                )
              }
              Switch(
                checked = currentSitePolicy.blockPopups,
                onCheckedChange = { isChecked ->
                  sitePolicyManager.setPolicyForHost(
                    currentSitePolicy.copy(blockPopups = isChecked)
                  )
                },
                colors = SwitchDefaults.colors(
                  checkedThumbColor = ThemeCyber.colors.primary,
                  checkedTrackColor = ThemeCyber.colors.primary.copy(alpha = 0.4f)
                )
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TrackerStatRow(
  icon: ImageVector,
  title: String,
  count: Int,
  tint: Color
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(18.dp)
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = title,
        fontSize = 13.sp,
        color = ThemeCyber.colors.textPrimary
      )
    }
    Text(
      text = "$count",
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold,
      color = if (count > 0) tint else ThemeCyber.colors.textMuted,
      fontFamily = CyberMonoFamily
    )
  }
}
