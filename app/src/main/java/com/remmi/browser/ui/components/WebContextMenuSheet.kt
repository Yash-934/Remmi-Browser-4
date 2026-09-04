package com.remmi.browser.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.remmi.browser.model.WebContextMenuData
import com.remmi.browser.ui.theme.ThemeCyber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebContextMenuSheet(
  data: WebContextMenuData,
  onDismiss: () -> Unit,
  onOpenInNewTab: (url: String) -> Unit,
  onOpenInNewTabInBackground: (url: String) -> Unit,
  onOpenInInPrivateTab: (url: String) -> Unit,
  onOpenInNewWindow: (url: String) -> Unit,
  onPreviewPage: (url: String, title: String) -> Unit,
  onPreviewImage: (imageUrl: String, title: String) -> Unit,
  onAskAiAboutImage: (imageUrl: String, title: String) -> Unit,
  onCopyLinkAddress: (url: String) -> Unit,
  onCopyLinkText: (text: String) -> Unit,
  onCopyImage: (imageUrl: String) -> Unit,
  onDownloadLink: (url: String) -> Unit,
  onDownloadImage: (imageUrl: String) -> Unit,
  onSearchWebForImage: (imageUrl: String) -> Unit,
  onShareLink: (url: String, title: String) -> Unit,
  onShareImage: (imageUrl: String, title: String) -> Unit,
  onInspectRedirects: ((url: String) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val isLightMode = ThemeCyber.colors.isLight
  val backgroundColor = if (isLightMode) Color.White else Color(0xFF1E1F24)
  val headerBgColor = if (isLightMode) Color(0xFFF3F4F6) else Color(0xFF2A2B32)
  val textColor = if (isLightMode) Color(0xFF1F2024) else Color(0xFFF1F1F3)
  val textSubColor = if (isLightMode) Color(0xFF6B7280) else Color(0xFF9CA3AF)
  val dividerColor = if (isLightMode) Color(0xFFE5E7EB) else Color(0xFF374151)
  val rippleColor = if (isLightMode) Color(0xFFE5E7EB) else Color(0xFF33353D)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = backgroundColor,
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    dragHandle = null,
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(bottom = 24.dp)
    ) {
      // 1. Header Section (Top Header with Icon/Thumbnail + Title + Subtitle)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        if (data.isImage && !data.srcUri.isNullOrBlank()) {
          // Image Thumbnail Box with checkered transparency background
          Box(
            modifier = Modifier
              .size(54.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(headerBgColor)
              .border(0.8.dp, dividerColor, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
          ) {
            AsyncImage(
              model = ImageRequest.Builder(context)
                .data(data.srcUri)
                .crossfade(true)
                .build(),
              contentDescription = data.displayTitle,
              contentScale = ContentScale.Fit,
              modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(6.dp))
            )
          }

          Spacer(modifier = Modifier.width(16.dp))

          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = data.displayTitle,
              fontSize = 16.sp,
              fontWeight = FontWeight.SemiBold,
              color = textColor,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            if (!data.linkUri.isNullOrBlank()) {
              Text(
                text = data.displayUrlSnippet,
                fontSize = 13.sp,
                color = textSubColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }
        } else {
          // Link Avatar Circle (Matching Screenshot 1 with circular letter badge)
          Box(
            modifier = Modifier
              .size(44.dp)
              .clip(CircleShape)
              .background(if (isLightMode) Color(0xFFE5E9F0) else Color(0xFF374151)),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = data.initialLetter,
              fontSize = 18.sp,
              fontWeight = FontWeight.Bold,
              color = if (isLightMode) Color(0xFF4B5563) else Color(0xFFD1D5DB)
            )
          }

          Spacer(modifier = Modifier.width(14.dp))

          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = data.displayTitle,
              fontSize = 16.sp,
              fontWeight = FontWeight.SemiBold,
              color = textColor,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = data.displayUrlSnippet,
              fontSize = 13.sp,
              color = textSubColor,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }

      HorizontalDivider(color = dividerColor, thickness = 0.8.dp)

      Spacer(modifier = Modifier.height(4.dp))

      // 2. Action Menu Items
      if (data.isImage && !data.srcUri.isNullOrBlank()) {
        // --- IMAGE CONTEXT MENU (Matches Screenshot 2 Exactly) ---
        val imageUrl = data.srcUri!!

        // 2. Open image in new tab
        ContextMenuItem(
          title = "Open image in new tab",
          textColor = textColor,
          onClick = {
            onDismiss()
            onOpenInNewTab(imageUrl)
          },
          testTag = "menu_open_image_new_tab"
        )

        // 3. Preview image
        ContextMenuItem(
          title = "Preview image",
          textColor = textColor,
          onClick = {
            onDismiss()
            onPreviewImage(imageUrl, data.displayTitle)
          },
          testTag = "menu_preview_image"
        )

        // 4. Copy image
        ContextMenuItem(
          title = "Copy image",
          textColor = textColor,
          onClick = {
            onDismiss()
            onCopyImage(imageUrl)
          },
          testTag = "menu_copy_image"
        )

        // 5. Download image
        ContextMenuItem(
          title = "Download image",
          textColor = textColor,
          onClick = {
            onDismiss()
            onDownloadImage(imageUrl)
          },
          testTag = "menu_download_image"
        )

        // 7. Share image (with colorful share badge)
        ContextMenuItem(
          title = "Share image",
          textColor = textColor,
          trailingContent = { ShareBadgeIcon() },
          onClick = {
            onDismiss()
            onShareImage(imageUrl, data.displayTitle)
          },
          testTag = "menu_share_image"
        )

        // If this image is ALSO a link, show link section options below
        if (data.isLink && !data.linkUri.isNullOrBlank()) {
          val linkUrl = data.linkUri!!
          Spacer(modifier = Modifier.height(8.dp))
          HorizontalDivider(color = dividerColor, thickness = 0.8.dp)
          Spacer(modifier = Modifier.height(4.dp))

          Text(
            text = "LINK OPTIONS",
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textSubColor,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
          )

          ContextMenuItem(
            title = "Open link in new tab",
            textColor = textColor,
            onClick = {
              onDismiss()
              onOpenInNewTab(linkUrl)
            }
          )

          ContextMenuItem(
            title = "Open in InPrivate tab",
            textColor = textColor,
            onClick = {
              onDismiss()
              onOpenInInPrivateTab(linkUrl)
            }
          )

          ContextMenuItem(
            title = "Copy link address",
            textColor = textColor,
            onClick = {
              onDismiss()
              onCopyLinkAddress(linkUrl)
            }
          )

          if (onInspectRedirects != null) {
            ContextMenuItem(
              title = "Reveal original link (Inspect redirects)",
              textColor = textColor,
              onClick = {
                onDismiss()
                onInspectRedirects(linkUrl)
              },
              testTag = "menu_inspect_redirects_image_link"
            )
          }
        }
      } else {
        // --- LINK CONTEXT MENU (Matches Screenshot 1 Exactly) ---
        val linkUrl = data.linkUri ?: ""

        // 1. Open in new tab
        ContextMenuItem(
          title = "Open in new tab",
          textColor = textColor,
          onClick = {
            onDismiss()
            onOpenInNewTab(linkUrl)
          },
          testTag = "menu_open_new_tab"
        )

        // 2. Open in new tab in group
        ContextMenuItem(
          title = "Open in new tab in group",
          textColor = textColor,
          onClick = {
            onDismiss()
            onOpenInNewTabInBackground(linkUrl)
          },
          testTag = "menu_open_new_tab_group"
        )

        // 3. Open in InPrivate tab
        ContextMenuItem(
          title = "Open in InPrivate tab",
          textColor = textColor,
          onClick = {
            onDismiss()
            onOpenInInPrivateTab(linkUrl)
          },
          testTag = "menu_open_inprivate"
        )

        // 4. Reveal original link / Inspect redirects
        if (onInspectRedirects != null) {
          ContextMenuItem(
            title = "Reveal original link (Inspect redirects)",
            textColor = textColor,
            onClick = {
              onDismiss()
              onInspectRedirects(linkUrl)
            },
            testTag = "menu_inspect_redirects"
          )
        }

        // 4. Open in new window
        ContextMenuItem(
          title = "Open in new window",
          textColor = textColor,
          onClick = {
            onDismiss()
            onOpenInNewWindow(linkUrl)
          },
          testTag = "menu_open_new_window"
        )

        // 5. Preview page
        ContextMenuItem(
          title = "Preview page",
          textColor = textColor,
          onClick = {
            onDismiss()
            onPreviewPage(linkUrl, data.displayTitle)
          },
          testTag = "menu_preview_page"
        )

        // 6. Copy link address
        ContextMenuItem(
          title = "Copy link address",
          textColor = textColor,
          onClick = {
            onDismiss()
            onCopyLinkAddress(linkUrl)
          },
          testTag = "menu_copy_link_address"
        )

        // 7. Copy link text
        ContextMenuItem(
          title = "Copy link text",
          textColor = textColor,
          onClick = {
            onDismiss()
            onCopyLinkText(data.linkText ?: data.displayTitle)
          },
          testTag = "menu_copy_link_text"
        )

        // 8. Download link
        ContextMenuItem(
          title = "Download link",
          textColor = textColor,
          onClick = {
            onDismiss()
            onDownloadLink(linkUrl)
          },
          testTag = "menu_download_link"
        )

        // 9. Share link (with colorful share badge)
        ContextMenuItem(
          title = "Share link",
          textColor = textColor,
          trailingContent = { ShareBadgeIcon() },
          onClick = {
            onDismiss()
            onShareLink(linkUrl, data.displayTitle)
          },
          testTag = "menu_share_link"
        )
      }
    }
  }
}

@Composable
fun ContextMenuItem(
  title: String,
  textColor: Color,
  modifier: Modifier = Modifier,
  trailingContent: (@Composable () -> Unit)? = null,
  testTag: String = "",
  onClick: () -> Unit,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 20.dp, vertical = 14.dp)
      .then(if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = title,
      fontSize = 15.5.sp,
      fontWeight = FontWeight.Normal,
      color = textColor,
      modifier = Modifier.weight(1f)
    )

    if (trailingContent != null) {
      Spacer(modifier = Modifier.width(8.dp))
      trailingContent()
    }
  }
}

/**
 * Colorful Microsoft Copilot / AI Spark ribbon icon
 */
@Composable
fun CopilotBadgeIcon(modifier: Modifier = Modifier) {
  Canvas(modifier = modifier.size(18.dp)) {
    val w = size.width
    val h = size.height
    // Ribbon loop drawing with colorful gradient
    drawRoundRect(
      brush = Brush.linearGradient(
        colors = listOf(
          Color(0xFFFFB900), // Amber
          Color(0xFFF25022), // Coral
          Color(0xFF7FBA00), // Green
          Color(0xFF00A4EF)  // Blue
        )
      ),
      topLeft = Offset(w * 0.1f, h * 0.1f),
      size = Size(w * 0.8f, h * 0.8f),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
    )
  }
}

/**
 * Colorful Microsoft 365 / Share badge icon
 */
@Composable
fun ShareBadgeIcon(modifier: Modifier = Modifier) {
  Canvas(modifier = modifier.size(17.dp)) {
    val w = size.width
    val h = size.height
    // Four colored quadrants
    drawRect(color = Color(0xFF0078D4), topLeft = Offset(0f, 0f), size = Size(w * 0.65f, h * 0.65f))
    drawRect(color = Color(0xFFFFB900), topLeft = Offset(w * 0.45f, 0f), size = Size(w * 0.55f, h * 0.45f))
    drawRect(color = Color(0xFF107C41), topLeft = Offset(0f, h * 0.45f), size = Size(w * 0.45f, h * 0.55f))
    drawRect(color = Color(0xFFD83B01), topLeft = Offset(w * 0.45f, h * 0.45f), size = Size(w * 0.55f, h * 0.55f))
  }
}
