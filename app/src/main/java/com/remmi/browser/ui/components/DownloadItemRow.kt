package com.remmi.browser.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.downloads.DownloadProgressInfo
import com.remmi.browser.storage.DownloadItem
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadItemRow(
  item: DownloadItem,
  isCurrentlyDownloading: Boolean,
  activeProgress: DownloadProgressInfo?,
  accentColor: Color,
  dateFormatter: SimpleDateFormat,
  formatFileSize: (Long) -> String,
  onCancelDownload: () -> Unit,
  onDeleteLog: () -> Unit,
  onDeleteDevice: () -> Unit,
  onOpen: () -> Unit
) {
  var showMenu by remember { mutableStateOf(false) }
  var showInfoDialog by remember { mutableStateOf(false) }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(ThemeCyber.colors.surface)
      .border(0.6.dp, if (isCurrentlyDownloading) accentColor.copy(alpha = 0.5f) else ThemeCyber.colors.surfaceBorder, RoundedCornerShape(6.dp))
      .clickable(enabled = !isCurrentlyDownloading) { onOpen() }
      .padding(12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Default.InsertDriveFile,
        contentDescription = null,
        tint = if (isCurrentlyDownloading) accentColor else ThemeCyber.colors.textSecondary,
        modifier = Modifier.size(20.dp)
      )

      Spacer(modifier = Modifier.width(10.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.fileName,
          color = ThemeCyber.colors.textPrimary,
          fontFamily = CyberMonoFamily,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = if (isCurrentlyDownloading && activeProgress != null && activeProgress.totalBytes > 0) {
              "${activeProgress.progressPercent}% • ${formatFileSize(activeProgress.bytesDownloaded)} / ${formatFileSize(activeProgress.totalBytes)}"
            } else {
              formatFileSize(if (isCurrentlyDownloading && activeProgress != null) activeProgress.bytesDownloaded else item.fileSize)
            },
            color = if (isCurrentlyDownloading) accentColor else ThemeCyber.colors.textSecondary,
            fontFamily = CyberMonoFamily,
            fontSize = 10.sp,
            fontWeight = if (isCurrentlyDownloading) FontWeight.Bold else FontWeight.Normal,
          )

          Spacer(modifier = Modifier.width(8.dp))

          Text(
            text = "• ${dateFormatter.format(Date(item.timestamp))}",
            color = ThemeCyber.colors.textMuted,
            fontFamily = CyberMonoFamily,
            fontSize = 9.sp,
          )
        }
      }

      if (isCurrentlyDownloading) {
        IconButton(
          onClick = onCancelDownload,
          modifier = Modifier.size(28.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Cancel Download",
            tint = ThemeCyber.colors.dangerRed,
            modifier = Modifier.size(14.dp)
          )
        }
      } else {
        Box {
          IconButton(
            onClick = { showMenu = true },
            modifier = Modifier.size(28.dp)
          ) {
            Icon(
              imageVector = Icons.Default.MoreVert,
              contentDescription = "Options",
              tint = ThemeCyber.colors.textMuted,
              modifier = Modifier.size(20.dp)
            )
          }

          DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.background(ThemeCyber.colors.surfaceLight)
          ) {
            DropdownMenuItem(
              text = { Text("Open File / Play Media", color = ThemeCyber.colors.textPrimary, fontFamily = CyberMonoFamily, fontSize = 12.sp) },
              leadingIcon = { Icon(Icons.Default.OpenInBrowser, null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(18.dp)) },
              onClick = { showMenu = false; onOpen() }
            )
            DropdownMenuItem(
              text = { Text("File Info", color = ThemeCyber.colors.textPrimary, fontFamily = CyberMonoFamily, fontSize = 12.sp) },
              leadingIcon = { Icon(Icons.Default.Info, null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(18.dp)) },
              onClick = { showMenu = false; showInfoDialog = true }
            )
            DropdownMenuItem(
              text = { Text("Delete from List", color = ThemeCyber.colors.textPrimary, fontFamily = CyberMonoFamily, fontSize = 12.sp) },
              leadingIcon = { Icon(Icons.Default.Delete, null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(18.dp)) },
              onClick = { showMenu = false; onDeleteLog() }
            )
            DropdownMenuItem(
              text = { Text("Delete from Device", color = ThemeCyber.colors.dangerRed, fontFamily = CyberMonoFamily, fontSize = 12.sp) },
              leadingIcon = { Icon(Icons.Default.DeleteForever, null, tint = ThemeCyber.colors.dangerRed, modifier = Modifier.size(18.dp)) },
              onClick = { showMenu = false; onDeleteDevice() }
            )
          }
        }
      }
    }

    if (isCurrentlyDownloading) {
      Spacer(modifier = Modifier.height(8.dp))
      if (activeProgress != null && activeProgress.totalBytes > 0) {
        LinearProgressIndicator(
          progress = { activeProgress.progressPercent / 100f },
          modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
          color = accentColor,
          trackColor = ThemeCyber.colors.surfaceLight,
          strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
      } else {
        LinearProgressIndicator(
          modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
          color = accentColor,
          trackColor = ThemeCyber.colors.surfaceLight,
          strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
      }
    }
  }

  if (showInfoDialog) {
    val context = LocalContext.current
    var fileSha256 by remember { mutableStateOf<String?>("Computing SHA-256...") }

    LaunchedEffect(item.filePath) {
      withContext(Dispatchers.IO) {
        try {
          val file = if (item.filePath.isNotBlank()) File(item.filePath) else null
          if (file != null && file.exists() && file.canRead()) {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
              val buffer = ByteArray(8192)
              var read: Int
              while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
              }
            }
            fileSha256 = digest.digest().joinToString("") { "%02x".format(it) }
          } else {
            fileSha256 = "File not stored at local path"
          }
        } catch (e: Exception) {
          fileSha256 = "Error computing checksum: ${e.localizedMessage}"
        }
      }
    }

    AlertDialog(
      onDismissRequest = { showInfoDialog = false },
      containerColor = ThemeCyber.colors.surface,
      title = {
        Text("File Information & Integrity", color = ThemeCyber.colors.primary, fontFamily = CyberMonoFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Name: ${item.fileName}", color = ThemeCyber.colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
          Text("Size: ${formatFileSize(item.fileSize)}", color = ThemeCyber.colors.textPrimary, fontSize = 12.sp)
          Text("Type: ${item.mimeType.ifEmpty { "Unknown" }}", color = ThemeCyber.colors.textPrimary, fontSize = 12.sp)
          Text("Date: ${dateFormatter.format(Date(item.timestamp))}", color = ThemeCyber.colors.textPrimary, fontSize = 12.sp)
          Text("Location: ${item.filePath}", color = ThemeCyber.colors.textSecondary, fontSize = 10.sp)
          Text("URL: ${item.url}", color = ThemeCyber.colors.textMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
          
          Spacer(modifier = Modifier.height(4.dp))
          
          Surface(
            color = ThemeCyber.colors.background,
            border = androidx.compose.foundation.BorderStroke(0.8.dp, ThemeCyber.colors.surfaceBorder),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(8.dp)) {
              Text(
                text = "SHA-256 CHECKSUM",
                color = ThemeCyber.colors.primary,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = CyberMonoFamily
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = fileSha256 ?: "Computing...",
                color = if (fileSha256 != null && fileSha256!!.length == 64) ThemeCyber.colors.neonCyan else ThemeCyber.colors.textMuted,
                fontSize = 9.5.sp,
                fontFamily = CyberMonoFamily,
                lineHeight = 13.sp
              )
            }
          }
        }
      },
      confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (fileSha256 != null && fileSha256!!.length == 64) {
            TextButton(
              onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SHA-256", fileSha256))
                Toast.makeText(context, "SHA-256 Checksum copied", Toast.LENGTH_SHORT).show()
              }
            ) {
              Icon(Icons.Default.ContentCopy, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Copy SHA-256", color = ThemeCyber.colors.primary, fontFamily = CyberMonoFamily, fontSize = 11.sp)
            }
          }
          TextButton(onClick = { showInfoDialog = false }) {
            Text("Close", color = ThemeCyber.colors.primary, fontFamily = CyberMonoFamily, fontSize = 11.sp)
          }
        }
      }
    )
  }
}
