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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun ImagePreviewDialog(
  imageUrl: String,
  title: String,
  onDismiss: () -> Unit,
  onDownload: (String) -> Unit,
  onShare: (String, String) -> Unit,
  onOpenInTab: (String) -> Unit,
) {
  val context = LocalContext.current

  val resolvedUrl = remember(imageUrl) {
    val src = imageUrl.trim()
    when {
      src.startsWith("//") -> "https:$src"
      src.startsWith("http://") || src.startsWith("https://") || src.startsWith("data:") -> src
      else -> "https://$src"
    }
  }

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
            text = title.ifEmpty { resolvedUrl.substringAfterLast('/') },
            fontSize = 13.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          IconButton(
            onClick = { onOpenInTab(resolvedUrl) },
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.15f))
          ) {
            Icon(Icons.Default.OpenInNew, contentDescription = "Open in tab", tint = Color.White, modifier = Modifier.size(18.dp))
          }

          IconButton(
            onClick = { onDownload(resolvedUrl) },
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.15f))
          ) {
            Icon(Icons.Default.Download, contentDescription = "Download image", tint = Color.White, modifier = Modifier.size(18.dp))
          }

          IconButton(
            onClick = { onShare(resolvedUrl, title) },
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
        var isLoading by remember { mutableStateOf(true) }
        var isError by remember { mutableStateOf(false) }

        AsyncImage(
          model = ImageRequest.Builder(context)
            .data(resolvedUrl)
            .crossfade(true)
            .build(),
          contentDescription = title,
          contentScale = ContentScale.Fit,
          onState = { state ->
            when (state) {
              is AsyncImagePainter.State.Loading -> {
                isLoading = true
                isError = false
              }
              is AsyncImagePainter.State.Success -> {
                isLoading = false
                isError = false
              }
              is AsyncImagePainter.State.Error -> {
                isLoading = false
                isError = true
              }
              else -> Unit
            }
          },
          modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
        )

        if (isLoading) {
          CircularProgressIndicator(
            color = ThemeCyber.colors.primary,
            modifier = Modifier.size(40.dp)
          )
        }

        if (isError) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Image,
              contentDescription = "Image load error",
              tint = Color.White.copy(alpha = 0.5f),
              modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = "Unable to preview image directly",
              color = Color.White.copy(alpha = 0.8f),
              fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = resolvedUrl,
              color = Color.White.copy(alpha = 0.4f),
              fontSize = 11.sp,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }
  }
}
