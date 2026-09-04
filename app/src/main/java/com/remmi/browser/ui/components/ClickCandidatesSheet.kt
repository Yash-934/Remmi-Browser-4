package com.remmi.browser.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.security.ClickTargetCandidate
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClickCandidatesSheet(
  candidates: List<ClickTargetCandidate>,
  onSelectCandidate: (ClickTargetCandidate) -> Unit,
  onInspectCandidate: (ClickTargetCandidate) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
    modifier = modifier.testTag("click_candidates_sheet")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(bottom = 32.dp)
    ) {
      val hasOverlay = candidates.any { it.isOverlay }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
      ) {
        Box(
          modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
              if (hasOverlay) ThemeCyber.colors.dangerRed.copy(alpha = 0.15f)
              else ThemeCyber.colors.warningYellow.copy(alpha = 0.15f)
            ),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = if (hasOverlay) Icons.Default.Warning else Icons.Default.TouchApp,
            contentDescription = null,
            tint = if (hasOverlay) ThemeCyber.colors.dangerRed else ThemeCyber.colors.warningYellow,
            modifier = Modifier.size(20.dp)
          )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = if (hasOverlay) "SUSPICIOUS OVERLAY DETECTED" else "MULTIPLE CLICK TARGETS DETECTED",
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (hasOverlay) ThemeCyber.colors.dangerRed else ThemeCyber.colors.textPrimary
          )
          Text(
            text = if (hasOverlay) "Suspicious overlay detected above visible content" else "Overlapping elements discovered under touch point",
            fontSize = 10.5.sp,
            fontFamily = CyberMonoFamily,
            color = ThemeCyber.colors.textSecondary
          )
        }
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.Close, contentDescription = "Close", tint = ThemeCyber.colors.textMuted)
        }
      }

      LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        items(candidates) { candidate ->
          Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.background),
            border = BorderStroke(
              1.dp,
              if (candidate.isOverlay) ThemeCyber.colors.dangerRed else ThemeCyber.colors.surfaceBorder
            ),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (candidate.isOverlay) ThemeCyber.colors.dangerRed.copy(alpha = 0.15f) else ThemeCyber.colors.primary.copy(alpha = 0.15f)
                  ) {
                    Text(
                      text = if (candidate.isOverlay) "SUSPICIOUS OVERLAY TARGET" else "VISIBLE / INTENDED TARGET",
                      fontSize = 9.5.sp,
                      fontFamily = CyberMonoFamily,
                      fontWeight = FontWeight.Bold,
                      color = if (candidate.isOverlay) ThemeCyber.colors.dangerRed else ThemeCyber.colors.primary,
                      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                  }
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = candidate.label,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (candidate.isOverlay) ThemeCyber.colors.dangerRed else ThemeCyber.colors.textPrimary
                  )
                }

                if (candidate.isTransparent) {
                  Text(
                    text = "TRANSPARENT",
                    fontSize = 9.sp,
                    fontFamily = CyberMonoFamily,
                    color = ThemeCyber.colors.dangerRed,
                    fontWeight = FontWeight.Bold
                  )
                }
              }

              Spacer(modifier = Modifier.height(6.dp))

              Text(
                text = candidate.url,
                fontSize = 11.5.sp,
                fontFamily = CyberMonoFamily,
                color = ThemeCyber.colors.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
              )

              if (candidate.details.isNotBlank()) {
                Text(
                  text = candidate.details,
                  fontSize = 10.sp,
                  fontFamily = CyberMonoFamily,
                  color = ThemeCyber.colors.textMuted,
                  modifier = Modifier.padding(top = 2.dp)
                )
              }

              Spacer(modifier = Modifier.height(10.dp))

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Button(
                  onClick = {
                    onDismiss()
                    onSelectCandidate(candidate)
                  },
                  colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier.weight(1f).height(36.dp)
                ) {
                  Text("Open Link", color = Color.Black, fontFamily = ThemeCyber.fontFamily, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                  onClick = {
                    onDismiss()
                    onInspectCandidate(candidate)
                  },
                  border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier.weight(1f).height(36.dp)
                ) {
                  Icon(Icons.Default.Visibility, contentDescription = null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(14.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Inspect", color = ThemeCyber.colors.textPrimary, fontFamily = ThemeCyber.fontFamily, fontSize = 11.5.sp)
                }
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      OutlinedButton(
        onClick = onDismiss,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
        modifier = Modifier.fillMaxWidth().height(38.dp)
      ) {
        Text("Cancel", color = ThemeCyber.colors.textSecondary, fontFamily = ThemeCyber.fontFamily, fontSize = 12.sp)
      }
    }
  }
}
