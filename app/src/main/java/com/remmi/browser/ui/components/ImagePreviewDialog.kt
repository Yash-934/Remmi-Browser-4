package com.remmi.browser.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun ImagePreviewDialog(
  imageUrl: String,
  title: String,
  refererUrl: String? = null,
  onDismiss: () -> Unit,
  onDownload: (String) -> Unit,
  onShare: (String, String) -> Unit,
  onOpenInTab: (String) -> Unit,
) {
  val context = LocalContext.current
  var retryKey by remember { mutableIntStateOf(0) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.94f))
    ) {
      // Top Action Bar
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 20.dp)
          .align(Alignment.TopCenter),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "IMAGE PREVIEW",
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = ThemeCyber.colors.primary
          )
          Text(
            text = title.ifEmpty { imageUrl.substringAfterLast('/') },
            fontSize = 13.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          IconButton(
            onClick = { onOpenInTab(imageUrl) },
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.15f))
          ) {
            Icon(Icons.Default.OpenInNew, contentDescription = "Open in tab", tint = Color.White, modifier = Modifier.size(18.dp))
          }

          IconButton(
            onClick = { onDownload(imageUrl) },
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.15f))
          ) {
            Icon(Icons.Default.Download, contentDescription = "Download image", tint = Color.White, modifier = Modifier.size(18.dp))
          }

          IconButton(
            onClick = { onShare(imageUrl, title) },
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.15f))
          ) {
            Icon(Icons.Default.Share, contentDescription = "Share image", tint = Color.White, modifier = Modifier.size(18.dp))
          }

          IconButton(
            onClick = onDismiss,
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.2f))
          ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
          }
        }
      }

      // Full Image Center
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(top = 70.dp, bottom = 40.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.Center
      ) {
        val imageRequest = remember(imageUrl, retryKey) {
          ImageRequest.Builder(context)
            .data(imageUrl)
            .crossfade(true)
            .build()
        }

        SubcomposeAsyncImage(
          model = imageRequest,
          contentDescription = title,
          contentScale = ContentScale.Fit,
          modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp)),
          loading = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                  color = ThemeCyber.colors.primary,
                  modifier = Modifier.size(36.dp),
                  strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                  text = "Loading image...",
                  color = Color.White.copy(alpha = 0.7f),
                  fontSize = 12.sp,
                  fontFamily = ThemeCyber.fontFamily
                )
              }
            }
          },
          error = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
              ) {
                Icon(
                  Icons.Default.BrokenImage,
                  contentDescription = null,
                  tint = Color(0xFFFF5252),
                  modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                  text = "Could not load image preview",
                  color = Color.White,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "The host may restrict hotlinking or require page credentials.",
                  color = Color.White.copy(alpha = 0.6f),
                  fontSize = 12.sp,
                  textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  Button(
                    onClick = { retryKey++ },
                    colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary, contentColor = Color.Black)
                  ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Retry")
                  }

                  Button(
                    onClick = { onOpenInTab(imageUrl) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White)
                  ) {
                    Text("Open Direct")
                  }
                }
              }
            }
          },
          success = {
            SubcomposeAsyncImageContent()
          }
        )
      }
    }
  }
}
