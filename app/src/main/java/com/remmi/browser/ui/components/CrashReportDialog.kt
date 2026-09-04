package com.remmi.browser.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.util.CrashExportResult

@Composable
fun CrashReportDialog(
  crashResult: CrashExportResult,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  var copied by remember { mutableStateOf(false) }
  val isAbnormal = crashResult.reportType == com.remmi.browser.util.ReportType.ABNORMAL_TERMINATION
  val titleText = if (isAbnormal) "Abnormal Termination Detected" else "Crash Report Detected"
  val descriptionText = if (isAbnormal) {
    "A previous session was terminated abnormally (e.g. process kill, out-of-memory, or native crash). A diagnostic report has been automatically generated."
  } else {
    "An unexpected exception was caught. A complete crash report has been saved."
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    shape = RoundedCornerShape(16.dp),
    containerColor = MaterialTheme.colorScheme.surface,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Icon(
          imageVector = Icons.Default.Info,
          contentDescription = titleText,
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(24.dp)
        )
        Text(
          text = titleText,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
      }
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        // Status banner
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = "Saved",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp)
            )
            Text(
              text = "Saved to:\n${crashResult.savedPath}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              fontWeight = FontWeight.Medium
            )
          }
        }

        Text(
          text = descriptionText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Scrollable Log Preview
        val verticalScrollState = rememberScrollState()
        val horizontalScrollState = rememberScrollState()
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F141C))
            .border(1.dp, Color(0xFF263242), RoundedCornerShape(8.dp))
            .padding(10.dp)
        ) {
          Text(
            text = crashResult.fullReport,
            fontFamily = FontFamily.SansSerif,
            fontSize = 11.sp,
            color = Color(0xFF00FFCC),
            lineHeight = 15.sp,
            modifier = Modifier
              .verticalScroll(verticalScrollState)
              .horizontalScroll(horizontalScrollState)
          )
        }
      }
    },
    confirmButton = {
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        OutlinedButton(
          onClick = {
            try {
              val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, crashResult.fullReport)
                putExtra(Intent.EXTRA_SUBJECT, if (isAbnormal) "Remmi Browser Diagnostic Report" else "Remmi Browser Crash Report")
                type = "text/plain"
              }
              val shareIntent = Intent.createChooser(sendIntent, "Share Diagnostic Report")
              context.startActivity(shareIntent)
            } catch (e: Exception) {
              Toast.makeText(context, "Could not open share sheet", Toast.LENGTH_SHORT).show()
            }
          }
        ) {
          Icon(
            imageVector = Icons.Default.Share,
            contentDescription = "Share",
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("Share")
        }

        Button(
          onClick = {
            try {
              val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
              val clip = ClipData.newPlainText("Remmi Diagnostic Report", crashResult.fullReport)
              clipboard?.setPrimaryClip(clip)
              copied = true
              Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
            onDismiss()
          }
        ) {
          Text("Copy & Close")
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Dismiss")
      }
    }
  )
}
