package com.remmi.browser.model

import java.net.URLDecoder

data class WebContextMenuData(
  val linkUri: String? = null,
  val linkText: String? = null,
  val linkTitle: String? = null,
  val srcUri: String? = null,
  val altText: String? = null,
  val title: String? = null,
  val type: Int = 0,
) {
  val isLink: Boolean get() = !linkUri.isNullOrBlank()
  
  val isImage: Boolean get() = type == 1 || (!srcUri.isNullOrBlank() && (
    srcUri.endsWith(".png", true) ||
    srcUri.endsWith(".jpg", true) ||
    srcUri.endsWith(".jpeg", true) ||
    srcUri.endsWith(".webp", true) ||
    srcUri.endsWith(".gif", true) ||
    srcUri.endsWith(".svg", true) ||
    srcUri.startsWith("data:image") ||
    srcUri.contains("/File:", true) ||
    srcUri.contains("upload.wikimedia", true) ||
    srcUri.contains("image", true)
  ))

  val resolvedSrcUri: String? get() {
    val src = srcUri?.trim() ?: return null
    return when {
      src.startsWith("//") -> "https:$src"
      src.startsWith("http://") || src.startsWith("https://") || src.startsWith("data:") -> src
      !linkUri.isNullOrBlank() && src.startsWith("/") -> {
        try {
          val uri = java.net.URI(linkUri)
          "${uri.scheme ?: "https"}://${uri.host ?: ""}$src"
        } catch (e: Exception) {
          "https://$src"
        }
      }
      else -> src
    }
  }

  val resolvedLinkText: String get() {
    if (!linkText.isNullOrBlank()) return linkText.trim()
    if (!linkTitle.isNullOrBlank()) return linkTitle.trim()
    if (!title.isNullOrBlank()) return title.trim()
    if (!altText.isNullOrBlank()) return altText.trim()
    
    // Extract human-readable name from the URL path if available
    if (!linkUri.isNullOrBlank()) {
      try {
        val segment = linkUri.substringBefore('?').substringBefore('#').substringAfterLast('/').trim()
        if (segment.isNotEmpty() && !segment.equals("index.html", true) && !segment.equals("index.php", true)) {
          val decoded = URLDecoder.decode(segment, "UTF-8")
          val clean = decoded.replace('_', ' ').replace('-', ' ').trim()
          if (clean.isNotEmpty()) return clean
        }
      } catch (e: Exception) {
        // Fallback below
      }
    }
    return displayTitle
  }

  val displayTitle: String get() {
    return when {
      !title.isNullOrBlank() -> title
      !linkTitle.isNullOrBlank() -> linkTitle
      !linkText.isNullOrBlank() -> linkText
      !altText.isNullOrBlank() -> altText
      !srcUri.isNullOrBlank() -> {
        val name = srcUri.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val clean = name.replace('_', ' ').replace('-', ' ').trim()
        if (clean.isNotBlank()) clean else "Image"
      }
      !linkUri.isNullOrBlank() -> {
        val clean = linkUri.substringAfter("://").substringBefore('/')
        clean.ifEmpty { "Web Link" }
      }
      else -> "Selection"
    }
  }

  val displayUrlSnippet: String get() {
    val target = linkUri ?: resolvedSrcUri ?: srcUri ?: ""
    return target.removePrefix("https://").removePrefix("http://")
  }

  val initialLetter: String get() {
    val text = displayTitle.trim()
    return if (text.isNotEmpty()) text.first().uppercase() else "W"
  }
}
