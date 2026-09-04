package com.remmi.browser.security

import android.content.ClipData
import android.content.ClipboardManager as AndroidClipboard
import android.content.Context
import android.os.Handler
import android.os.Looper

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
