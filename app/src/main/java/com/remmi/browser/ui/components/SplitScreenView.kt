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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.remmi.browser.engine.BrowserTab
import com.remmi.browser.model.WebContextMenuData
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun SplitScreenView(
  primaryTab: BrowserTab,
  secondaryTab: BrowserTab,
  primaryContent: @Composable () -> Unit,
  onSwapTabs: () -> Unit,
  onCloseSplit: () -> Unit,
  onSecondaryUrlChange: (String) -> Unit,
  onSecondaryTitleChange: (String) -> Unit,
  onSecondaryLoadingChange: (Boolean) -> Unit,
  onSecondarySecurityChange: (Boolean) -> Unit,
  onSecondaryNavStateChange: (Boolean, Boolean) -> Unit,
  onSecondaryTrackerBlocked: (String, String) -> Unit,
  onSecondaryContextMenuRequested: (WebContextMenuData) -> Unit,
  onSecondaryBack: () -> Unit,
  onSecondaryForward: () -> Unit,
  onSecondaryReload: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isLight = ThemeCyber.colors.isLight
  val splitBarBg = if (isLight) Color(0xFFE5E7EB) else Color(0xFF14151B)
  val splitBorderColor = if (isLight) Color(0xFFD1D5DB) else Color(0xFF2E303E)
  val textColor = if (isLight) Color(0xFF1F2937) else Color(0xFFE5E7EB)
  val subTextColor = if (isLight) Color(0xFF6B7280) else Color(0xFF9CA3AF)

  Column(modifier = modifier.fillMaxSize()) {
    // 1. Primary Tab (Top Pane)
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
    ) {
      primaryContent()
    }

    // 2. Middle Split Control & Drag Bar
    Surface(
      color = splitBarBg,
      modifier = Modifier
        .fillMaxWidth()
        .height(38.dp)
        .border(0.8.dp, splitBorderColor)
    ) {
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        // Left Badge
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(4.dp))
              .background(ThemeCyber.colors.primary.copy(alpha = 0.2f))
              .padding(horizontal = 6.dp, vertical = 2.dp)
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Default.VerticalSplit,
                contentDescription = null,
                tint = ThemeCyber.colors.primary,
                modifier = Modifier.size(12.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "SPLIT VIEW",
                fontFamily = CyberMonoFamily,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = ThemeCyber.colors.primary
              )
            }
          }

          Spacer(modifier = Modifier.width(8.dp))

          Text(
            text = secondaryTab.title.ifBlank { secondaryTab.url.ifBlank { "Secondary Window" } },
            fontSize = 11.5.sp,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
          )
        }

        // Action Buttons: Swap & Close
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = onSwapTabs,
            modifier = Modifier
              .size(28.dp)
              .testTag("btn_swap_split_tabs")
          ) {
            Icon(
              imageVector = Icons.Default.SwapVert,
              contentDescription = "Swap Top/Bottom Tabs",
              tint = textColor,
              modifier = Modifier.size(16.dp)
            )
          }

          Spacer(modifier = Modifier.width(4.dp))

          IconButton(
            onClick = onCloseSplit,
            modifier = Modifier
              .size(28.dp)
              .testTag("btn_close_split_view")
          ) {
            Icon(
              imageVector = Icons.Default.FullscreenExit,
              contentDescription = "Exit Split View",
              tint = textColor,
              modifier = Modifier.size(16.dp)
            )
          }
        }
      }
    }

    // 3. Secondary Tab Mini Control Bar & Browser View (Bottom Pane)
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        // Mini Navigation Bar for Secondary Pane
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(if (isLight) Color(0xFFF3F4F6) else Color(0xFF1E2028))
            .padding(horizontal = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(
            onClick = onSecondaryBack,
            enabled = secondaryTab.canGoBack,
            modifier = Modifier.size(26.dp)
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
              tint = if (secondaryTab.canGoBack) textColor else subTextColor.copy(alpha = 0.4f),
              modifier = Modifier.size(14.dp)
            )
          }

          IconButton(
            onClick = onSecondaryForward,
            enabled = secondaryTab.canGoForward,
            modifier = Modifier.size(26.dp)
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowForward,
              contentDescription = "Forward",
              tint = if (secondaryTab.canGoForward) textColor else subTextColor.copy(alpha = 0.4f),
              modifier = Modifier.size(14.dp)
            )
          }

          IconButton(
            onClick = onSecondaryReload,
            modifier = Modifier.size(26.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = "Reload",
              tint = textColor,
              modifier = Modifier.size(14.dp)
            )
          }

          Spacer(modifier = Modifier.width(6.dp))

          // URL preview box
          Box(
            modifier = Modifier
              .weight(1f)
              .height(24.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(if (isLight) Color.White else Color(0xFF121318))
              .border(0.6.dp, splitBorderColor, RoundedCornerShape(6.dp))
              .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
          ) {
            Text(
              text = if (secondaryTab.url == "about:blank") "New Tab" else secondaryTab.url,
              fontSize = 11.sp,
              color = subTextColor,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        if (secondaryTab.isLoading) {
          LinearProgressIndicator(
            modifier = Modifier
              .fillMaxWidth()
              .height(2.dp),
            color = ThemeCyber.colors.primary,
            trackColor = Color.Transparent
          )
        }

        // Secondary Browser Content
        androidx.compose.runtime.key(secondaryTab.id) {
          BrowserView(
            tab = secondaryTab,
            onUrlChange = onSecondaryUrlChange,
            onTitleChange = onSecondaryTitleChange,
            onProgressChange = { },
            onLoadingChange = onSecondaryLoadingChange,
            onSecurityChange = onSecondarySecurityChange,
            onNavStateChange = onSecondaryNavStateChange,
            onTrackerBlocked = onSecondaryTrackerBlocked,
            onReaderArticleExtracted = { },
            onContextMenuRequested = onSecondaryContextMenuRequested,
            modifier = Modifier.fillMaxSize()
          )
        }
      }
    }
  }
}
