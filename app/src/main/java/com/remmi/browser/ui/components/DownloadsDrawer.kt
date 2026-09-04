package com.remmi.browser.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.downloads.DownloadHandler
import com.remmi.browser.storage.DownloadItem
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadsDrawer(
  downloadsList: List<DownloadItem>,
  onDeleteDownload: (DownloadItem) -> Unit,
  onClearAll: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val downloadHandler = remember { DownloadHandler.getInstance(context) }
  val activeDownloads by downloadHandler.activeDownloads.collectAsState()
  val scope = rememberCoroutineScope()
  val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
  val accentColor = ThemeCyber.colors.primary

  val totalActiveCount = activeDownloads.values.count { it.status == "DOWNLOADING" }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
      .padding(16.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Download,
          contentDescription = null,
          tint = accentColor,
          modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = if (totalActiveCount > 0) "DOWNLOADS (${downloadsList.size} • $totalActiveCount active)" else "DOWNLOADS (${downloadsList.size})",
          color = accentColor,
          fontFamily = CyberMonoFamily,
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold,
        )
      }

      Row {
        if (downloadsList.isNotEmpty()) {
          IconButton(
            onClick = onClearAll,
            modifier = Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(ThemeCyber.colors.surfaceLight)
              .testTag("clear_downloads_button")
          ) {
            Icon(
              imageVector = Icons.Default.Delete,
              contentDescription = "Clear All",
              tint = ThemeCyber.colors.dangerRed,
              modifier = Modifier.size(18.dp)
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
        }

        IconButton(
          onClick = onDismiss,
          modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ThemeCyber.colors.surfaceLight)
            .testTag("close_downloads_button")
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close",
            tint = ThemeCyber.colors.textPrimary,
            modifier = Modifier.size(18.dp)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    if (downloadsList.isEmpty() && activeDownloads.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            imageVector = Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = ThemeCyber.colors.textMuted,
            modifier = Modifier.size(40.dp)
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "NO DOWNLOADS IN LOG // AIR-GAPPED STORAGE",
            color = ThemeCyber.colors.textMuted,
            fontFamily = CyberMonoFamily,
            fontSize = 12.sp,
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(downloadsList) { item ->
          val activeProgress = activeDownloads[item.downloadId]
          val isCurrentlyDownloading = item.status == "DOWNLOADING" || (activeProgress != null && activeProgress.status == "DOWNLOADING")

          DownloadItemRow(
            item = item,
            isCurrentlyDownloading = isCurrentlyDownloading,
            activeProgress = activeProgress,
            accentColor = accentColor,
            dateFormatter = dateFormatter,
            formatFileSize = { formatFileSize(it) },
            onCancelDownload = { 
              scope.launch { downloadHandler.cancelDownload(item.downloadId) } 
            },
            onDeleteLog = { onDeleteDownload(item) },
            onDeleteDevice = {
              try {
                val uri = android.net.Uri.parse(item.filePath)
                context.contentResolver.delete(uri, null, null)
                onDeleteDownload(item)
                Toast.makeText(context, "Deleted from device", Toast.LENGTH_SHORT).show()
              } catch (e: Exception) {
                try {
                  val file = java.io.File(item.filePath)
                  if (file.exists() && file.delete()) {
                    onDeleteDownload(item)
                    Toast.makeText(context, "Deleted from device", Toast.LENGTH_SHORT).show()
                  } else {
                    Toast.makeText(context, "Failed to delete from device", Toast.LENGTH_SHORT).show()
                  }
                } catch (e2: Exception) {
                  Toast.makeText(context, "Failed to delete: ${e2.message}", Toast.LENGTH_SHORT).show()
                }
              }
            },
            onOpen = { openDownloadedFile(context, item) }
          )
        }
      }
    }
  }
}

private fun formatFileSize(bytes: Long): String {
  if (bytes <= 0) return "Unknown size"
  val kb = bytes / 1024.0
  val mb = kb / 1024.0
  val gb = mb / 1024.0
  return when {
    gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
    mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
    else -> String.format(Locale.US, "%.1f KB", kb)
  }
}

private fun openDownloadedFile(context: android.content.Context, item: DownloadItem) {
  try {
    val uri = if (item.filePath.startsWith("content://") || item.filePath.startsWith("file://")) {
      android.net.Uri.parse(item.filePath)
    } else {
      val file = File(item.filePath)
      if (file.exists()) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
           androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
           android.net.Uri.fromFile(file)
        }
      } else {
        android.widget.Toast.makeText(context, "File payload located at: ${item.filePath}", android.widget.Toast.LENGTH_SHORT).show()
        return
      }
    }

    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
      setDataAndType(uri, item.mimeType.ifEmpty { "*/*" })
      flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Open Payload"))
  } catch (e: Exception) {
    android.widget.Toast.makeText(context, "Saved to Downloads: ${item.fileName}", android.widget.Toast.LENGTH_SHORT).show()
  }
}
