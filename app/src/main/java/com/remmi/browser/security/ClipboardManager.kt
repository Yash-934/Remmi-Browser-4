package com.remmi.browser.security

import android.content.ClipData
import android.content.ClipboardManager as AndroidClipboard
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ClipboardManager(private val context: Context) {
  private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as AndroidClipboard

  fun clear() {
    try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        clipboard.clearPrimaryClip()
      } else {
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
      }
    } catch (e: Exception) {
      // Ignored
    }
  }

  fun copy(text: String, label: String = "Remmi") {
    copyWithAutoClear(text, label)
  }

  fun copyWithAutoClear(text: String, label: String = "Remmi", clearAfterMs: Long = 30000) {
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))

    Handler(Looper.getMainLooper()).postDelayed({
      try {
        if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text) {
          clipboard.clearPrimaryClip()
        }
      } catch (e: Exception) {
        // Ignored
      }
    }, clearAfterMs)
  }

  suspend fun copyImage(imageUrl: String): Boolean = withContext(Dispatchers.IO) {
    try {
      val imagesDir = File(context.cacheDir, "images").apply { mkdirs() }
      val imageFile = File(imagesDir, "clipboard_image.png")

      if (imageUrl.startsWith("data:image/", ignoreCase = true)) {
        val base64Data = imageUrl.substringAfter("base64,")
        val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
        imageFile.writeBytes(bytes)
      } else {
        val isGhost = CurrentTorRoute.isGhostActive || NetworkRouteAuthority.isOnionDestination(imageUrl)
        val client = NetworkRouteAuthority.createHttpClient(
          isGhost = isGhost,
          targetUrl = imageUrl,
          connectTimeoutSeconds = 10L,
          readTimeoutSeconds = 15L
        )
        val request = okhttp3.Request.Builder()
          .url(imageUrl)
          .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0")
          .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
          .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return@withContext false
        val bytes = response.body?.bytes() ?: return@withContext false
        imageFile.writeBytes(bytes)
      }

      val contentUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        imageFile
      )

      withContext(Dispatchers.Main) {
        val clip = ClipData.newUri(context.contentResolver, "Image", contentUri)
        clipboard.setPrimaryClip(clip)
      }
      true
    } catch (e: Exception) {
      Log.e("ClipboardManager", "Failed to copy image to clipboard", e)
      false
    }
  }

  fun getCopiedUrl(): String? {
    return try {
      if (clipboard.hasPrimaryClip()) {
        val item = clipboard.primaryClip?.getItemAt(0)
        val text = item?.text?.toString()?.trim()
        if (!text.isNullOrBlank() && isUrl(text)) {
          text
        } else {
          null
        }
      } else {
        null
      }
    } catch (e: Exception) {
      null
    }
  }

  companion object {
    fun isUrl(text: String): Boolean {
      val trimmed = text.trim()
      if (trimmed.isEmpty() || trimmed.contains(" ") || trimmed.contains("\n")) return false
      if (trimmed.startsWith("http://", ignoreCase = true) || 
          trimmed.startsWith("https://", ignoreCase = true) ||
          trimmed.startsWith("ftp://", ignoreCase = true) ||
          trimmed.startsWith("about:", ignoreCase = true) ||
          trimmed.startsWith("remmi://", ignoreCase = true)) {
        return true
      }
      // Check for domain pattern like domain.tld or sub.domain.tld
      val domainPattern = Regex("^[a-zA-Z0-9][-a-zA-Z0-9]*(\\.[a-zA-Z0-9][-a-zA-Z0-9]*)+(/.*)?$")
      return domainPattern.matches(trimmed)
    }
  }
}
