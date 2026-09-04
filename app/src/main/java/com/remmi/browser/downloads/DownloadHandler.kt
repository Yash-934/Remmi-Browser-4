package com.remmi.browser.downloads
import kotlinx.coroutines.cancelAndJoin

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.remmi.browser.R
import com.remmi.browser.storage.DownloadItem
import com.remmi.browser.storage.RemmiDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

data class DownloadProgressInfo(
  val downloadId: Long,
  val fileName: String,
  val url: String,
  val bytesDownloaded: Long,
  val totalBytes: Long,
  val isGhost: Boolean,
  val status: String, // "DOWNLOADING", "COMPLETED", "FAILED", "CANCELLED"
  val filePath: String? = null
) {
  val progressPercent: Int
    get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

  val isIndeterminate: Boolean
    get() = totalBytes <= 0
}

class DownloadHandler(private val context: Context) {

  private val downloadManager =
    context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
  private val scope = CoroutineScope(Dispatchers.IO)

  private val _activeDownloads = MutableStateFlow<Map<Long, DownloadProgressInfo>>(emptyMap())
  val activeDownloads = _activeDownloads.asStateFlow()

  private val activeJobs = ConcurrentHashMap<Long, Job>()

  init {
    createNotificationChannel()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Downloads",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Live progress and status of file downloads"
        setShowBadge(true)
      }
      notificationManager?.createNotificationChannel(channel)
    }
  }

  fun enqueueDownload(
    url: String,
    suggestedFilename: String? = null,
    mimeType: String? = null,
    contentLength: Long = 0L,
    isGhost: Boolean = false,
    webResponse: WebResponse? = null
  ) {
    val downloadId = (url.hashCode().toLong() and 0xFFFFFFF) + System.currentTimeMillis() % 100000
    val job = scope.launch {
      performManagedDownload(downloadId, url, suggestedFilename, mimeType, contentLength, isGhost, webResponse)
    }
    activeJobs[downloadId] = job
  }

  suspend fun cancelDownload(downloadId: Long) {
    activeJobs[downloadId]?.cancelAndJoin()
    activeJobs.remove(downloadId)
    _activeDownloads.value = _activeDownloads.value - downloadId

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    notificationManager?.cancel(downloadId.toInt())

    val db = RemmiDatabase.getDatabaseAsync(context)
    db.downloadDao().updateStatus(downloadId, "CANCELLED")
    
    withContext(Dispatchers.Main) {
      Toast.makeText(context, "Download cancelled", Toast.LENGTH_SHORT).show()
    }
  }

  private suspend fun performManagedDownload(
    downloadId: Long,
    url: String,
    suggestedFilename: String?,
    mimeType: String?,
    contentLength: Long,
    isGhost: Boolean,
    webResponse: WebResponse?
  ) {
    if (isGhost) {
      if (!com.remmi.browser.security.CurrentTorRoute.isReady || com.remmi.browser.security.CurrentTorRoute.currentGeneration <= 0L) {
        val errorNotif = NotificationCompat.Builder(context, CHANNEL_ID)
          .setContentTitle("Download failed: Security Alert")
          .setContentText("Ghost route unavailable or unverified. Download aborted to prevent leaks.")
          .setSmallIcon(android.R.drawable.stat_sys_warning)
          .setAutoCancel(true)
          .build()
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify((downloadId % Int.MAX_VALUE).toInt(), errorNotif)
        return
      }
    }

    val uriStr = Uri.parse(url)
    val fileName = sanitizeFileName(suggestedFilename ?: uriStr.lastPathSegment ?: "remmi_download")
    val mime = mimeType ?: guessMimeType(fileName)
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notifId = (downloadId % Int.MAX_VALUE).toInt()

    val MAX_DOWNLOAD_SIZE_BYTES = 5L * 1024L * 1024L * 1024L // 5GB Hard cap
    if (contentLength > MAX_DOWNLOAD_SIZE_BYTES) {
      val notif = NotificationCompat.Builder(context, CHANNEL_ID)
          .setContentTitle("Download rejected: $fileName")
          .setContentText("File exceeds 5GB limit.")
          .setSmallIcon(android.R.drawable.stat_sys_warning)
          .setAutoCancel(true).build()
      notificationManager.notify(notifId, notif)
      return
    }

    // Register active download info
    val initialInfo = DownloadProgressInfo(
      downloadId = downloadId,
      fileName = fileName,
      url = url,
      bytesDownloaded = 0L,
      totalBytes = contentLength,
      isGhost = isGhost,
      status = "DOWNLOADING"
    )
    _activeDownloads.value = _activeDownloads.value + (downloadId to initialInfo)

    val notifBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
      .setContentTitle(fileName)
      .setContentText(if (isGhost) "Downloading securely via Tor..." else "Starting download...")
      .setSmallIcon(R.drawable.ic_download)
      .setProgress(100, 0, contentLength <= 0)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
    notificationManager.notify(notifId, notifBuilder.build())

    val db = RemmiDatabase.getDatabaseAsync(context)
    var allocatedUri: android.net.Uri? = null
    try {
      db.downloadDao().insert(
        DownloadItem(
          downloadId = downloadId,
          fileName = fileName,
          url = url,
          mimeType = mime,
          fileSize = contentLength,
          status = "DOWNLOADING",
          filePath = ""
        )
      )

      val inputStream: InputStream = withContext(Dispatchers.IO) {
        if (webResponse != null && webResponse.body != null) {
          webResponse.body!!
        } else {
          val runtime = com.remmi.browser.engine.GeckoEngineManager.getInstance(context).runtime
            ?: throw Exception("Gecko runtime unavailable")
          val executor = GeckoWebExecutor(runtime)
          val request = WebRequest.Builder(url).build()
          val result = executor.fetch(request).poll(45000) ?: throw Exception("Download connection timed out")
          result.body ?: throw Exception("Response stream is empty")
        }
      }

      val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
          put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
      }

      val resolver = context.contentResolver
      val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Downloads.EXTERNAL_CONTENT_URI
      } else {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        Uri.fromFile(File(dir, fileName))
      }

      val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        resolver.insert(collection, contentValues) ?: throw Exception("Failed to allocate MediaStore entry")
      } else {
        collection
      }
      allocatedUri = targetUri

      var downloadedBytes = 0L
      val outStream: OutputStream = resolver.openOutputStream(targetUri)
        ?: throw Exception("Failed to open output write stream")

      withContext(Dispatchers.IO) {
        inputStream.use { input ->
          outStream.use { output ->
            val buffer = ByteArray(32 * 1024)
            var bytesRead = input.read(buffer)
            var lastUpdateMs = System.currentTimeMillis()

            val MAX_DOWNLOAD_SIZE_BYTES = 5L * 1024L * 1024L * 1024L // 5GB Hard cap
            while (bytesRead >= 0) {
              if (downloadedBytes + bytesRead > MAX_DOWNLOAD_SIZE_BYTES) {
                  throw java.io.IOException("Download exceeded hard cap of 5GB. Terminated to prevent storage exhaustion.")
              }
              output.write(buffer, 0, bytesRead)
              downloadedBytes += bytesRead

              val now = System.currentTimeMillis()
              if (now - lastUpdateMs > 350) {
                lastUpdateMs = now
                val currentProgress = if (contentLength > 0) {
                  ((downloadedBytes * 100) / contentLength).toInt().coerceIn(0, 100)
                } else 0

                val progressInfo = DownloadProgressInfo(
                  downloadId = downloadId,
                  fileName = fileName,
                  url = url,
                  bytesDownloaded = downloadedBytes,
                  totalBytes = contentLength,
                  isGhost = isGhost,
                  status = "DOWNLOADING"
                )
                _activeDownloads.value = _activeDownloads.value + (downloadId to progressInfo)

                val progressText = if (contentLength > 0) {
                  "$currentProgress% • ${formatBytes(downloadedBytes)} / ${formatBytes(contentLength)}"
                } else {
                  "${formatBytes(downloadedBytes)} downloaded"
                }

                notifBuilder
                  .setContentText(progressText)
                  .setProgress(100, currentProgress, contentLength <= 0)

                notificationManager.notify(notifId, notifBuilder.build())
              }

              bytesRead = input.read(buffer)
            }
          }
        }
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        contentValues.clear()
        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(targetUri, contentValues, null, null)
      }

      // Build completion Open Intent
      val viewUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        targetUri
      } else {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        FileProvider.getUriForFile(
          context,
          "${context.packageName}.fileprovider",
          File(dir, fileName)
        )
      }

      val openIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(viewUri, mime)
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
      }
      val pendingIntent = PendingIntent.getActivity(
        context,
        notifId,
        openIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )

      val completionNotif = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle(fileName)
        .setContentText("Download complete (${formatBytes(downloadedBytes)})")
        .setSmallIcon(R.drawable.ic_check)
        .setProgress(0, 0, false)
        .setOngoing(false)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()

      notificationManager.notify(notifId, completionNotif)

      val finalInfo = DownloadProgressInfo(
        downloadId = downloadId,
        fileName = fileName,
        url = url,
        bytesDownloaded = downloadedBytes,
        totalBytes = downloadedBytes,
        isGhost = isGhost,
        status = "COMPLETED",
        filePath = targetUri.toString()
      )
      _activeDownloads.value = _activeDownloads.value + (downloadId to finalInfo)
      activeJobs.remove(downloadId)

      db.downloadDao().insert(
        DownloadItem(
          downloadId = downloadId,
          fileName = fileName,
          url = url,
          mimeType = mime,
          fileSize = downloadedBytes,
          status = "COMPLETED",
          filePath = targetUri.toString()
        )
      )

      withContext(Dispatchers.Main) {
        Toast.makeText(context, "Download complete: $fileName", Toast.LENGTH_SHORT).show()
      }

    } catch (e: Exception) {
      Log.e(TAG, "Download failed for $fileName", e)
      
      // Cleanup partial/failed download from MediaStore
      try {
        allocatedUri?.let { uri ->
          context.contentResolver.delete(uri, null, null)
        }
      } catch (cleanupEx: Exception) {
        Log.e(TAG, "Failed to clean up partial download", cleanupEx)
      }
      
      activeJobs.remove(downloadId)

      val errorNotif = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle("Download failed: $fileName")
        .setContentText(e.message ?: "Transfer error occurred")
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setProgress(0, 0, false)
        .setOngoing(false)
        .setAutoCancel(true)
        .build()

      notificationManager.notify(notifId, errorNotif)

      val failedInfo = DownloadProgressInfo(
        downloadId = downloadId,
        fileName = fileName,
        url = url,
        bytesDownloaded = 0L,
        totalBytes = contentLength,
        isGhost = isGhost,
        status = "FAILED",
        filePath = ""
      )
      _activeDownloads.value = _activeDownloads.value + (downloadId to failedInfo)

      db.downloadDao().insert(
        DownloadItem(
          downloadId = downloadId,
          fileName = fileName,
          url = url,
          mimeType = mime,
          fileSize = 0L,
          status = "FAILED",
          filePath = ""
        )
      )

      withContext(Dispatchers.Main) {
        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun sanitizeFileName(name: String): String {
    val clean = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return if (clean.contains(".")) clean else "$clean.bin"
  }

  private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
  }

  private fun guessMimeType(fileName: String): String {
    val clean = fileName.substringBefore('?').substringBefore('#')
    val ext = clean.substringAfterLast('.', "").lowercase()
    return when (ext) {
      "html", "htm" -> "text/html"
      "css" -> "text/css"
      "js" -> "application/javascript"
      "json" -> "application/json"
      "pdf" -> "application/pdf"
      "zip" -> "application/zip"
      "tar", "gz", "tgz" -> "application/gzip"
      "apk" -> "application/vnd.android.package-archive"
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "webp" -> "image/webp"
      "gif" -> "image/gif"
      "svg" -> "image/svg+xml"
      "mp4" -> "video/mp4"
      "webm" -> "video/webm"
      "mp3" -> "audio/mpeg"
      "ogg" -> "audio/ogg"
      "wav" -> "audio/wav"
      "txt" -> "text/plain"
      "md" -> "text/markdown"
      "doc", "docx" -> "application/msword"
      else -> "application/octet-stream"
    }
  }

  suspend fun cancelAllDownloads() {
    val jobs = activeJobs.values.toList()
    jobs.forEach { it.cancelAndJoin() }
    activeJobs.clear()
    _activeDownloads.value = emptyMap()
  }

  companion object {
    private const val TAG = "DownloadHandler"
    private const val CHANNEL_ID = "remmi_downloads_channel"

    @Volatile
    private var INSTANCE: DownloadHandler? = null

    fun getInstance(context: Context): DownloadHandler {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: DownloadHandler(context.applicationContext).also { INSTANCE = it }
      }
    }
  }
}
