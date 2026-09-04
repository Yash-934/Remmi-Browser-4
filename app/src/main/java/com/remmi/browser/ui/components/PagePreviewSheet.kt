package com.remmi.browser.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.ui.theme.ThemeCyber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagePreviewSheet(
  url: String,
  title: String,
  onDismiss: () -> Unit,
  onOpenFullTab: (url: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

  val uri = remember(url) {
    try {
      Uri.parse(url)
    } catch (_: Exception) {
      null
    }
  }

  val scheme = uri?.scheme?.lowercase() ?: "https"
  val host = uri?.host ?: url
  val path = uri?.path?.ifBlank { "/" } ?: "/"
  val isOnion = host.endsWith(".onion")
  val isHttps = scheme == "https"

  val isLightMode = ThemeCyber.colors.isLight
  val backgroundColor = if (isLightMode) Color(0xFFF8F9FA) else Color(0xFF14161C)
  val surfaceColor = if (isLightMode) Color.White else Color(0xFF1E212B)
  val textColor = if (isLightMode) Color(0xFF1F2024) else Color(0xFFF1F1F3)
  val subtleTextColor = if (isLightMode) Color(0xFF6B7280) else Color(0xFF9CA3AF)
  val accentColor = if (isOnion) Color(0xFFBB86FC) else if (isHttps) ThemeCyber.colors.primary else Color(0xFFFF5252)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = backgroundColor,
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 8.dp)
        .verticalScroll(rememberScrollState())
    ) {
      // Header Bar
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(32.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(accentColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = if (isOnion) Icons.Default.Security else if (isHttps) Icons.Default.Lock else Icons.Default.Warning,
              contentDescription = null,
              tint = accentColor,
              modifier = Modifier.size(18.dp)
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text(
              text = if (isOnion) "TOR ONION HIDDEN SERVICE" else if (isHttps) "SECURE HTTPS LINK" else "INSECURE HTTP LINK",
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = accentColor
            )
            Text(
              text = "ZERO-LEAK STATIC INSPECTION",
              fontSize = 9.sp,
              color = subtleTextColor,
              fontFamily = ThemeCyber.fontFamily
            )
          }
        }

        IconButton(
          onClick = onDismiss,
          modifier = Modifier.size(36.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close",
            tint = textColor
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Link Card
      Surface(
        color = surfaceColor,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(
            text = title.ifBlank { host },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            text = url,
            fontSize = 12.sp,
            color = subtleTextColor,
            fontFamily = ThemeCyber.fontFamily,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
          )

          Spacer(modifier = Modifier.height(14.dp))

          // Detail Grid
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            DetailPill(label = "PROTOCOL", value = scheme.uppercase(), color = accentColor)
            DetailPill(label = "DOMAIN", value = host.take(20), color = ThemeCyber.colors.textPrimary)
            DetailPill(label = "TOR PROXY", value = if (isOnion) "MANDATORY" else "SHIELDED", color = if (isOnion) Color(0xFFBB86FC) else ThemeCyber.colors.successGreen)
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Security Sandbox Notice
      Surface(
        color = ThemeCyber.colors.successGreen.copy(alpha = 0.08f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ThemeCyber.colors.successGreen.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            tint = ThemeCyber.colors.successGreen,
            modifier = Modifier.size(20.dp)
          )
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = "Anonymity Preserved: Preview rendered without background network calls or WebView proxy leaks.",
            fontSize = 11.sp,
            color = textColor,
            lineHeight = 15.sp
          )
        }
      }

      Spacer(modifier = Modifier.height(20.dp))

      // Primary Action Buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Button(
          onClick = {
            onDismiss()
            onOpenFullTab(url)
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = ThemeCyber.colors.primary,
            contentColor = Color.Black
          ),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .weight(1f)
            .height(46.dp)
        ) {
          Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("OPEN TAB", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
          onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Link URL", url)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
          },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .weight(1f)
            .height(46.dp)
        ) {
          Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("COPY", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
          onClick = {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
              type = "text/plain"
              putExtra(Intent.EXTRA_TEXT, url)
              putExtra(Intent.EXTRA_TITLE, title)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share URL"))
          },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.size(46.dp),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
          Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

@Composable
private fun DetailPill(
  label: String,
  value: String,
  color: Color
) {
  Column(horizontalAlignment = Alignment.Start) {
    Text(
      text = label,
      fontSize = 9.sp,
      fontWeight = FontWeight.Bold,
      color = Color.Gray,
      fontFamily = ThemeCyber.fontFamily
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = value,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      color = color,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}
