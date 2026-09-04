package com.remmi.browser.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.security.ClickTargetCandidate
import com.remmi.browser.security.ClipboardManager
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.RedirectInspectionResult
import com.remmi.browser.security.RedirectInspector
import com.remmi.browser.security.RedirectResolutionStatus
import com.remmi.browser.security.SecurityRiskLevel
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

private val DOWNLOAD_EXTENSIONS = setOf(
  "apk", "exe", "zip", "pdf", "dmg", "bin", "msi", "iso", "tar", "gz", "rar", "7z", "deb", "rpm", "bat", "sh"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedirectInspectorSheet(
  initialUrl: String,
  isGhost: Boolean,
  onDismiss: () -> Unit,
  onOpenUrl: (String) -> Unit,
  modifier: Modifier = Modifier,
  candidates: List<ClickTargetCandidate> = emptyList(),
  actualBrowserLandedUrl: String? = null,
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val clipboard = remember { ClipboardManager(context) }

  var activeUrl by remember { mutableStateOf(initialUrl) }
  var isLoading by remember { mutableStateOf(true) }
  var result by remember { mutableStateOf<RedirectInspectionResult?>(null) }
  var reloadTrigger by remember { mutableIntStateOf(0) }

  LaunchedEffect(activeUrl, reloadTrigger) {
    isLoading = true
    val inspectResult = RedirectInspector.inspectUrl(
      url = activeUrl,
      isGhost = isGhost,
      socksPort = CurrentTorRoute.currentSocksPort,
      expectedGeneration = CurrentTorRoute.currentGeneration,
      actualBrowserLandedUrl = actualBrowserLandedUrl
    )
    result = inspectResult
    isLoading = false
  }

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
    modifier = modifier.testTag("redirect_inspector_sheet"),
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
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
      ) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ThemeCyber.colors.primary.copy(alpha = 0.15f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Visibility,
            contentDescription = null,
            tint = ThemeCyber.colors.primary,
            modifier = Modifier.size(20.dp)
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "CLICK TRANSPARENCY & REDIRECT INSPECTOR",
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            color = ThemeCyber.colors.textPrimary
          )
          Text(
            text = if (isGhost) "Tor SOCKS5 Isolated • Zero-Clearnet-Leak Gate" else "Isolated Clearnet Analysis • SSRF Rebinding Shield",
            fontSize = 10.5.sp,
            fontFamily = CyberMonoFamily,
            color = if (isGhost) ThemeCyber.colors.torPurple else ThemeCyber.colors.primary
          )
        }
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.Close, contentDescription = "Close", tint = ThemeCyber.colors.textMuted)
        }
      }

      // Candidate Targets (if multiple targets discovered under click)
      if (candidates.isNotEmpty()) {
        Text(
          text = "DISCOVERED CLICK TARGETS (${candidates.size}):",
          fontFamily = CyberMonoFamily,
          fontSize = 10.5.sp,
          fontWeight = FontWeight.Bold,
          color = ThemeCyber.colors.textSecondary,
          modifier = Modifier.padding(bottom = 6.dp)
        )
        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
          items(candidates) { candidate ->
            val isSelected = candidate.url == activeUrl
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = if (isSelected) ThemeCyber.colors.primary.copy(alpha = 0.2f) else ThemeCyber.colors.background,
              border = BorderStroke(
                1.dp,
                if (candidate.isOverlay) ThemeCyber.colors.dangerRed
                else if (isSelected) ThemeCyber.colors.primary
                else ThemeCyber.colors.surfaceBorder
              ),
              modifier = Modifier.clickable { activeUrl = candidate.url }
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
              ) {
                if (candidate.isOverlay) {
                  Icon(Icons.Default.Warning, contentDescription = null, tint = ThemeCyber.colors.dangerRed, modifier = Modifier.size(14.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                  text = candidate.label,
                  fontFamily = CyberMonoFamily,
                  fontSize = 11.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                  color = if (candidate.isOverlay) ThemeCyber.colors.dangerRed else if (isSelected) ThemeCyber.colors.primary else ThemeCyber.colors.textSecondary
                )
              }
            }
          }
        }
      }

      if (isLoading) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          CircularProgressIndicator(
            color = if (isGhost) ThemeCyber.colors.torPurple else ThemeCyber.colors.primary,
            modifier = Modifier.size(36.dp),
            strokeWidth = 3.dp
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = if (isGhost) "Routing hops over Tor circuit without cookies..." else "Tracing multi-hop redirects and verifying SSL/DNS...",
            fontFamily = CyberMonoFamily,
            fontSize = 11.5.sp,
            color = ThemeCyber.colors.textSecondary
          )
        }
      } else {
        val inspection = result
        if (inspection != null) {
          val hops = inspection.hops
          val hasRedirects = hops.size > 1

          // Status & Risk Banner Card
          Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.background),
            border = BorderStroke(
              1.dp,
              when (inspection.riskLevel) {
                SecurityRiskLevel.BLOCKED -> ThemeCyber.colors.dangerRed
                SecurityRiskLevel.HIGH -> ThemeCyber.colors.dangerRed
                SecurityRiskLevel.MEDIUM -> ThemeCyber.colors.warningYellow
                SecurityRiskLevel.LOW -> ThemeCyber.colors.surfaceBorder
              }
            ),
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (inspection.status) {
                      RedirectResolutionStatus.RESOLVED -> ThemeCyber.colors.successGreen.copy(alpha = 0.15f)
                      RedirectResolutionStatus.SSRF_BLOCKED, RedirectResolutionStatus.TOR_ROUTE_LOST -> ThemeCyber.colors.dangerRed.copy(alpha = 0.2f)
                      RedirectResolutionStatus.LOOP_DETECTED, RedirectResolutionStatus.TIMEOUT -> ThemeCyber.colors.warningYellow.copy(alpha = 0.2f)
                      else -> ThemeCyber.colors.surfaceLight
                    },
                    border = BorderStroke(
                      0.8.dp,
                      when (inspection.status) {
                        RedirectResolutionStatus.RESOLVED -> ThemeCyber.colors.successGreen
                        RedirectResolutionStatus.SSRF_BLOCKED, RedirectResolutionStatus.TOR_ROUTE_LOST -> ThemeCyber.colors.dangerRed
                        RedirectResolutionStatus.LOOP_DETECTED, RedirectResolutionStatus.TIMEOUT -> ThemeCyber.colors.warningYellow
                        else -> ThemeCyber.colors.surfaceBorder
                      }
                    )
                  ) {
                    Text(
                      text = inspection.status.name,
                      color = when (inspection.status) {
                        RedirectResolutionStatus.RESOLVED -> ThemeCyber.colors.successGreen
                        RedirectResolutionStatus.SSRF_BLOCKED, RedirectResolutionStatus.TOR_ROUTE_LOST -> ThemeCyber.colors.dangerRed
                        RedirectResolutionStatus.LOOP_DETECTED, RedirectResolutionStatus.TIMEOUT -> ThemeCyber.colors.warningYellow
                        else -> ThemeCyber.colors.textSecondary
                      },
                      fontSize = 10.5.sp,
                      fontFamily = CyberMonoFamily,
                      fontWeight = FontWeight.Bold,
                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                  }

                  Spacer(modifier = Modifier.width(8.dp))

                  Text(
                    text = if (hasRedirects) "${hops.size - 1} HOPS" else "DIRECT",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp,
                    fontFamily = ThemeCyber.fontFamily,
                    color = if (hasRedirects) ThemeCyber.colors.warningYellow else ThemeCyber.colors.textPrimary
                  )
                }

                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = when (inspection.riskLevel) {
                    SecurityRiskLevel.BLOCKED, SecurityRiskLevel.HIGH -> ThemeCyber.colors.dangerRed.copy(alpha = 0.15f)
                    SecurityRiskLevel.MEDIUM -> ThemeCyber.colors.warningYellow.copy(alpha = 0.15f)
                    SecurityRiskLevel.LOW -> ThemeCyber.colors.successGreen.copy(alpha = 0.15f)
                  }
                ) {
                  Text(
                    text = "RISK: ${inspection.riskLevel.name} (${inspection.safetyScore}/100)",
                    color = when (inspection.riskLevel) {
                      SecurityRiskLevel.BLOCKED, SecurityRiskLevel.HIGH -> ThemeCyber.colors.dangerRed
                      SecurityRiskLevel.MEDIUM -> ThemeCyber.colors.warningYellow
                      SecurityRiskLevel.LOW -> ThemeCyber.colors.successGreen
                    },
                    fontSize = 10.sp,
                    fontFamily = CyberMonoFamily,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                  )
                }
              }

              Spacer(modifier = Modifier.height(10.dp))

              // Final Destination / Status Details
              if (inspection.finalUrl != null && inspection.status == RedirectResolutionStatus.RESOLVED) {
                Text(
                  text = "VERIFIED FINAL DESTINATION:",
                  fontSize = 10.sp,
                  fontFamily = CyberMonoFamily,
                  fontWeight = FontWeight.Bold,
                  color = ThemeCyber.colors.textMuted
                )
                Text(
                  text = inspection.finalUrl,
                  fontSize = 12.5.sp,
                  fontFamily = CyberMonoFamily,
                  fontWeight = FontWeight.SemiBold,
                  color = ThemeCyber.colors.primary,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis
                )
              } else {
                Text(
                  text = "RESOLUTION OUTCOME:",
                  fontSize = 10.sp,
                  fontFamily = CyberMonoFamily,
                  fontWeight = FontWeight.Bold,
                  color = ThemeCyber.colors.dangerRed
                )
                Text(
                  text = inspection.error ?: "Unable to inspect this link safely (${inspection.status.name})",
                  fontSize = 12.sp,
                  fontFamily = CyberMonoFamily,
                  color = ThemeCyber.colors.textSecondary
                )
              }

              // Download Link Safety Detection
              val destForDownloadCheck = inspection.finalUrl ?: inspection.strippedUrl
              val pathExt = destForDownloadCheck.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
              if (DOWNLOAD_EXTENSIONS.contains(pathExt)) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = ThemeCyber.colors.warningYellow.copy(alpha = 0.15f),
                  border = BorderStroke(0.8.dp, ThemeCyber.colors.warningYellow),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(8.dp)
                  ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = ThemeCyber.colors.warningYellow, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = "DOWNLOAD TARGET: .$pathExt file • Domain: ${RedirectInspector.extractDomain(destForDownloadCheck)} • Auto-download stopped",
                      fontSize = 10.5.sp,
                      fontFamily = CyberMonoFamily,
                      fontWeight = FontWeight.SemiBold,
                      color = ThemeCyber.colors.warningYellow
                    )
                  }
                }
              }

              // Actual Browser Landing Discrepancy (if different)
              if (actualBrowserLandedUrl != null && actualBrowserLandedUrl != inspection.originalUrl && actualBrowserLandedUrl != inspection.finalUrl) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                  text = "ACTUAL BROWSER DESTINATION:",
                  fontSize = 10.sp,
                  fontFamily = CyberMonoFamily,
                  fontWeight = FontWeight.Bold,
                  color = ThemeCyber.colors.warningYellow
                )
                Text(
                  text = "Clicked: ${inspection.originalUrl}",
                  fontSize = 11.sp,
                  fontFamily = CyberMonoFamily,
                  color = ThemeCyber.colors.textMuted,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = "Landed: $actualBrowserLandedUrl",
                  fontSize = 11.sp,
                  fontFamily = CyberMonoFamily,
                  color = ThemeCyber.colors.warningYellow,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }

          // Security Insights List (if any)
          if (inspection.securityInsights.isNotEmpty()) {
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
              inspection.securityInsights.forEach { insight ->
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.padding(vertical = 2.dp)
                ) {
                  Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = ThemeCyber.colors.primary,
                    modifier = Modifier.size(13.dp)
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = insight,
                    fontFamily = CyberMonoFamily,
                    fontSize = 10.5.sp,
                    color = ThemeCyber.colors.textSecondary
                  )
                }
              }
            }
          }

          // Hop-by-Hop Chain
          if (hops.isNotEmpty()) {
            Text(
              text = "REDIRECT CHAIN (${hops.size} STEPS):",
              fontFamily = CyberMonoFamily,
              fontSize = 10.5.sp,
              fontWeight = FontWeight.Bold,
              color = ThemeCyber.colors.textMuted,
              modifier = Modifier.padding(bottom = 6.dp)
            )

            LazyColumn(
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
            ) {
              itemsIndexed(hops) { index, hop ->
                val isLast = index == hops.size - 1
                val isFirst = index == 0

                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                  verticalAlignment = Alignment.Top
                ) {
                  // Step Badge
                  Box(
                    modifier = Modifier
                      .size(22.dp)
                      .clip(CircleShape)
                      .background(
                        if (isLast && inspection.status == RedirectResolutionStatus.RESOLVED) ThemeCyber.colors.primary
                        else if (isFirst) ThemeCyber.colors.surfaceLight
                        else if (hop.statusCode >= 400 || hop.redirectType == "SSRF_BLOCKED" || hop.redirectType == "ERROR") ThemeCyber.colors.dangerRed.copy(alpha = 0.4f)
                        else ThemeCyber.colors.warningYellow.copy(alpha = 0.3f)
                      ),
                    contentAlignment = Alignment.Center
                  ) {
                    Text(
                      text = "${hop.step}",
                      fontSize = 10.sp,
                      fontWeight = FontWeight.Bold,
                      color = if (isLast && inspection.status == RedirectResolutionStatus.RESOLVED) Color.Black else ThemeCyber.colors.textPrimary
                    )
                  }

                  Spacer(modifier = Modifier.width(8.dp))

                  Column(modifier = Modifier.weight(1f)) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                      Text(
                        text = "${hop.domain} • ${hop.redirectType}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ThemeCyber.colors.textPrimary
                      )
                      Text(
                        text = if (hop.statusCode > 0) "${hop.statusCode}" else hop.redirectType,
                        fontSize = 10.5.sp,
                        fontFamily = CyberMonoFamily,
                        fontWeight = FontWeight.Bold,
                        color = if (hop.statusCode in 200..299) ThemeCyber.colors.successGreen
                        else if (hop.statusCode in 300..399) ThemeCyber.colors.warningYellow
                        else ThemeCyber.colors.dangerRed
                      )
                    }
                    Text(
                      text = hop.url,
                      fontSize = 10.sp,
                      fontFamily = CyberMonoFamily,
                      color = ThemeCyber.colors.textMuted,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                  }
                }

                if (!isLast) {
                  Box(
                    modifier = Modifier
                      .padding(start = 10.dp)
                      .width(2.dp)
                      .height(8.dp)
                      .background(ThemeCyber.colors.surfaceBorder)
                  )
                }
              }
            }
          }

          // Dynamic Script Limitation Notice
          Text(
            text = "NOTICE: Dynamic client-side SPA redirects execute in-browser and may navigate beyond static discovery.",
            fontSize = 9.5.sp,
            fontFamily = CyberMonoFamily,
            color = ThemeCyber.colors.textMuted,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
          )

          // Action Buttons
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            // Open Clean Link
            val destinationToOpen = inspection.finalUrl ?: inspection.strippedUrl
            Button(
              onClick = {
                onDismiss()
                onOpenUrl(destinationToOpen)
              },
              colors = ButtonDefaults.buttonColors(
                containerColor = ThemeCyber.colors.primary,
                contentColor = Color.Black
              ),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.fillMaxWidth().height(42.dp).testTag("open_revealed_button")
            ) {
              Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("OPEN CLEAN DESTINATION", fontFamily = ThemeCyber.fontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            // Open Nested Target if extracted
            if (inspection.extractedNestedUrl != null && inspection.extractedNestedUrl != inspection.finalUrl) {
              Button(
                onClick = {
                  onDismiss()
                  onOpenUrl(inspection.extractedNestedUrl)
                },
                colors = ButtonDefaults.buttonColors(
                  containerColor = ThemeCyber.colors.surfaceLight,
                  contentColor = ThemeCyber.colors.primary
                ),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ThemeCyber.colors.primary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().height(38.dp).testTag("open_nested_target_button")
              ) {
                Icon(Icons.Default.Launch, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("OPEN EXTRACTED ORIGINAL LINK", fontFamily = ThemeCyber.fontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp)
              }
            }

            // Open Original URL (useful when inspection fails or user requests original)
            if (inspection.status != RedirectResolutionStatus.RESOLVED) {
              OutlinedButton(
                onClick = {
                  onDismiss()
                  onOpenUrl(inspection.originalUrl)
                },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ThemeCyber.colors.primary.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth().height(38.dp).testTag("open_original_button")
              ) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("OPEN ORIGINAL LINK", color = ThemeCyber.colors.primary, fontFamily = CyberMonoFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold)
              }
            }

            // Retry Button if error or timeout
            if (inspection.status != RedirectResolutionStatus.RESOLVED) {
              OutlinedButton(
                onClick = { reloadTrigger++ },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ThemeCyber.colors.warningYellow),
                modifier = Modifier.fillMaxWidth().height(38.dp)
              ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = ThemeCyber.colors.warningYellow, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("RETRY ANALYSIS", color = ThemeCyber.colors.warningYellow, fontFamily = CyberMonoFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold)
              }
            }

            // Quick Copy Buttons Row
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedButton(
                onClick = {
                  clipboard.copy(inspection.originalUrl, "Original Click URL")
                  Toast.makeText(context, "Original URL copied", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
                modifier = Modifier.weight(1f).height(38.dp).testTag("copy_original_button")
              ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Original", fontSize = 11.sp, color = ThemeCyber.colors.textPrimary, maxLines = 1)
              }

              OutlinedButton(
                onClick = {
                  val target = inspection.finalUrl ?: inspection.strippedUrl
                  clipboard.copy(target, "Final URL")
                  Toast.makeText(context, "Final URL copied", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
                modifier = Modifier.weight(1f).height(38.dp).testTag("copy_final_button")
              ) {
                Icon(Icons.Default.Done, contentDescription = null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Final", fontSize = 11.sp, color = ThemeCyber.colors.textPrimary, maxLines = 1)
              }

              OutlinedButton(
                onClick = {
                  val chainText = hops.joinToString("\n ➔ ") { "[${it.statusCode}] ${it.url}" }
                  clipboard.copy(chainText, "Redirect Chain")
                  Toast.makeText(context, "Redirect chain copied", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
                modifier = Modifier.weight(1f).height(38.dp).testTag("copy_chain_button")
              ) {
                Icon(Icons.Default.Link, contentDescription = null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Chain", fontSize = 11.sp, color = ThemeCyber.colors.textPrimary, maxLines = 1)
              }
            }
          }
        }
      }
    }
  }
}
