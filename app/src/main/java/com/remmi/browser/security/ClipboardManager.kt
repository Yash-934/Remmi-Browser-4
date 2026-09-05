package com.remmi.browser.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager as AndroidClipboard
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

  suspend fun copyImageDirect(imageUrl: String): Boolean = withContext(Dispatchers.IO) {
    try {
      val cleanUrl = when {
        imageUrl.startsWith("//") -> "https:$imageUrl"
        imageUrl.startsWith("http://") || imageUrl.startsWith("https://") || imageUrl.startsWith("data:") -> imageUrl
        else -> "https://$imageUrl"
      }

      val imageDir = File(context.cacheDir, "images").apply { mkdirs() }
      val isPng = cleanUrl.contains(".png", ignoreCase = true)
      val ext = if (isPng) "png" else "jpg"
      val imageFile = File(imageDir, "clipboard_img_${System.currentTimeMillis()}.$ext")

      if (cleanUrl.startsWith("data:image")) {
        val base64Data = cleanUrl.substringAfter("base64,")
        val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
        FileOutputStream(imageFile).use { it.write(decodedBytes) }
      } else {
        val url = URL(cleanUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
        conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.inputStream.use { input ->
          FileOutputStream(imageFile).use { output ->
            input.copyTo(output)
          }
        }
      }

      val contentUri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        imageFile
      )

      withContext(Dispatchers.Main) {
        val mimeType = if (isPng) "image/png" else "image/jpeg"
        val clip = ClipData.newUri(context.contentResolver, "Image", contentUri).apply {
          description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, false)
          }
        }
        clipboard.setPrimaryClip(clip)
      }
      true
    } catch (e: Exception) {
      Log.e("ClipboardManager", "Failed to copy image to clipboard: ${e.message}", e)
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
