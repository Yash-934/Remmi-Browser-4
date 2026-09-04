package com.remmi.browser.model

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
  val isImage: Boolean get() = type == 1 || (!srcUri.isNullOrBlank() && (srcUri.endsWith(".png", true) || srcUri.endsWith(".jpg", true) || srcUri.endsWith(".jpeg", true) || srcUri.endsWith(".webp", true) || srcUri.endsWith(".gif", true) || srcUri.endsWith(".svg", true) || srcUri.startsWith("data:image")))

  val displayTitle: String get() {
    return when {
      !title.isNullOrBlank() -> title
      !linkTitle.isNullOrBlank() -> linkTitle
      !linkText.isNullOrBlank() -> linkText
      !altText.isNullOrBlank() -> altText
      !linkUri.isNullOrBlank() -> {
        val clean = linkUri.substringAfter("://").substringBefore('/')
        clean.ifEmpty { "Web Link" }
      }
      !srcUri.isNullOrBlank() -> {
        val name = srcUri.substringAfterLast('/').substringBefore('?').substringBefore('#')
        if (name.isNotBlank()) name else "Image"
      }
      else -> "Selection"
    }
  }

  val displayUrlSnippet: String get() {
    val target = linkUri ?: srcUri ?: ""
    return target.removePrefix("https://").removePrefix("http://")
  }

  val initialLetter: String get() {
    val text = displayTitle.trim()
    return if (text.isNotEmpty()) text.first().uppercase() else "W"
  }
}
