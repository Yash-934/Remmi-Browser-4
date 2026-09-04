package com.remmi.browser.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReaderExporter {

  /**
   * Generates formatted Markdown string
   */
  fun generateMarkdown(article: ReaderArticle): String {
    val sb = StringBuilder()
    sb.append("# ${article.activeTitle}\n\n")
    if (article.byline.isNotBlank()) {
      sb.append("**Author:** ${article.byline}  \n")
    }
    if (article.siteName.isNotBlank()) {
      sb.append("**Source:** [${article.siteName}](${article.sourceUrl})  \n")
    }
    sb.append("**Reading Time:** ~${article.readingTimeMinutes} min  \n")
    sb.append("**Extracted on:** ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}\n\n")
    sb.append("---\n\n")

    for (p in article.activeParagraphs) {
      if (p.isHeading) {
        val prefix = "#".repeat(p.headingLevel.coerceIn(1, 4))
        sb.append("$prefix ${p.text}\n\n")
      } else {
        sb.append("${p.text}\n\n")
      }
    }

    return sb.toString()
  }

  /**
   * Generates formatted HTML document string for DOC export
   */
  fun generateHtmlDoc(article: ReaderArticle): String {
    val sb = StringBuilder()
    sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
    sb.append("<title>${escapeHtml(article.activeTitle)}</title>")
    sb.append("<style>")
    sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; line-height: 1.6; max-width: 800px; margin: 40px auto; padding: 0 20px; color: #222; }")
    sb.append("h1 { font-size: 28px; line-height: 1.3; color: #111; margin-bottom: 8px; }")
    sb.append(".meta { color: #666; font-size: 14px; margin-bottom: 24px; border-bottom: 1px solid #eee; padding-bottom: 16px; }")
    sb.append("p { font-size: 16px; margin-bottom: 16px; text-align: justify; }")
    sb.append("h2, h3, h4 { color: #222; margin-top: 24px; }")
    sb.append("a { color: #0066cc; text-decoration: none; }")
    sb.append("</style></head><body>")
    sb.append("<h1>${escapeHtml(article.activeTitle)}</h1>")
    sb.append("<div class=\"meta\">")
    if (article.byline.isNotBlank()) sb.append("<span>By ${escapeHtml(article.byline)}</span> &bull; ")
    if (article.siteName.isNotBlank()) sb.append("<span>${escapeHtml(article.siteName)}</span> &bull; ")
    sb.append("<span>~${article.readingTimeMinutes} min read</span>")
    if (article.sourceUrl.isNotBlank()) {
      sb.append("<br><a href=\"${article.sourceUrl}\">${article.sourceUrl}</a>")
    }
    sb.append("</div>")

    for (p in article.activeParagraphs) {
      if (p.isHeading) {
        val tag = "h${p.headingLevel.coerceIn(2, 4)}"
        sb.append("<$tag>${escapeHtml(p.text)}</$tag>")
      } else {
        sb.append("<p>${escapeHtml(p.text)}</p>")
      }
    }
    sb.append("</body></html>")
    return sb.toString()
  }

  /**
   * Export as Markdown file to Downloads and share
   */
  suspend fun exportMarkdownFile(context: Context, article: ReaderArticle): Uri? = withContext(Dispatchers.IO) {
    withContext(Dispatchers.Main) {
      Toast.makeText(context, "Exporting Markdown document...", Toast.LENGTH_SHORT).show()
    }
    try {
      val filename = sanitizeFilename("${article.activeTitle.take(30)}_reader.md")
      val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      if (!downloadsDir.exists()) downloadsDir.mkdirs()

      val file = File(downloadsDir, filename)
      FileOutputStream(file).use { out ->
        out.write(generateMarkdown(article).toByteArray())
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      withContext(Dispatchers.Main) {
        Toast.makeText(context, "Saved to Downloads/$filename", Toast.LENGTH_LONG).show()
        shareFile(context, uri, "text/markdown", article.activeTitle)
      }
      uri
    } catch (e: Exception) {
      withContext(Dispatchers.Main) {
        Toast.makeText(context, "Export error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
      }
      null
    }
  }

  /**
   * Export as DOC / HTML file to Downloads and share
   */
  suspend fun exportDocFile(context: Context, article: ReaderArticle): Uri? = withContext(Dispatchers.IO) {
    withContext(Dispatchers.Main) {
      Toast.makeText(context, "Exporting Word Document (.doc)...", Toast.LENGTH_SHORT).show()
    }
    try {
      val filename = sanitizeFilename("${article.activeTitle.take(30)}_reader.doc")
      val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      if (!downloadsDir.exists()) downloadsDir.mkdirs()

      val file = File(downloadsDir, filename)
      FileOutputStream(file).use { out ->
        out.write(generateHtmlDoc(article).toByteArray())
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      withContext(Dispatchers.Main) {
        Toast.makeText(context, "Saved to Downloads/$filename", Toast.LENGTH_LONG).show()
        shareFile(context, uri, "application/msword", article.activeTitle)
      }
      uri
    } catch (e: Exception) {
      withContext(Dispatchers.Main) {
        Toast.makeText(context, "Export error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
      }
      null
    }
  }

  /**
   * Export / Print as PDF via native Android PdfDocument (Zero-network, Zero-leak)
   */
  fun printOrExportPdf(context: Context, article: ReaderArticle) {
    Toast.makeText(context, "Generating paginated PDF document...", Toast.LENGTH_SHORT).show()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val filename = sanitizeFilename("${article.activeTitle.take(30)}_reader.pdf")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()

        val pdfFile = File(downloadsDir, filename)
        val pdfDocument = PdfDocument()

        val pageWidth = 595 // Standard A4 width in points (72 dpi)
        val pageHeight = 842 // Standard A4 height in points (72 dpi)
        val margin = 40f
        val contentWidth = (pageWidth - 2 * margin).toInt()

        // Text Paints
        val titlePaint = TextPaint().apply {
          color = Color.BLACK
          textSize = 18f
          isFakeBoldText = true
          isAntiAlias = true
        }

        val metaPaint = TextPaint().apply {
          color = Color.DKGRAY
          textSize = 10f
          isAntiAlias = true
        }

        val headingPaint = TextPaint().apply {
          color = Color.BLACK
          textSize = 14f
          isFakeBoldText = true
          isAntiAlias = true
        }

        val bodyPaint = TextPaint().apply {
          color = Color.rgb(30, 30, 30)
          textSize = 11f
          isAntiAlias = true
        }

        val dividerPaint = Paint().apply {
          color = Color.LTGRAY
          strokeWidth = 1f
        }

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas
        var currentY = margin

        fun createLayout(text: CharSequence, paint: TextPaint): StaticLayout {
          return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
              .setAlignment(Layout.Alignment.ALIGN_NORMAL)
              .setLineSpacing(2f, 1.15f)
              .setIncludePad(false)
              .build()
          } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, contentWidth, Layout.Alignment.ALIGN_NORMAL, 1.15f, 2f, false)
          }
        }

        fun ensureSpace(requiredHeight: Float) {
          if (currentY + requiredHeight > pageHeight - margin) {
            pdfDocument.finishPage(page)
            pageNumber++
            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            page = pdfDocument.startPage(pageInfo)
            canvas = page.canvas
            currentY = margin
          }
        }

        // Draw Title
        val titleLayout = createLayout(article.activeTitle, titlePaint)
        ensureSpace(titleLayout.height.toFloat())
        canvas.save()
        canvas.translate(margin, currentY)
        titleLayout.draw(canvas)
        canvas.restore()
        currentY += titleLayout.height + 8f

        // Draw Metadata
        val metaText = buildString {
          if (article.byline.isNotBlank()) append("Author: ${article.byline}  •  ")
          if (article.siteName.isNotBlank()) append("Source: ${article.siteName}  •  ")
          append("Reading time: ~${article.readingTimeMinutes} min")
        }
        val metaLayout = createLayout(metaText, metaPaint)
        ensureSpace(metaLayout.height.toFloat() + 16f)
        canvas.save()
        canvas.translate(margin, currentY)
        metaLayout.draw(canvas)
        canvas.restore()
        currentY += metaLayout.height + 10f

        // Draw divider
        canvas.drawLine(margin, currentY, margin + contentWidth, currentY, dividerPaint)
        currentY += 14f

        // Draw Paragraphs
        for (p in article.activeParagraphs) {
          if (p.text.isBlank()) continue
          val paint = if (p.isHeading) headingPaint else bodyPaint
          val layout = createLayout(p.text, paint)
          val extraSpacing = if (p.isHeading) 14f else 10f

          ensureSpace(layout.height.toFloat() + extraSpacing)
          canvas.save()
          canvas.translate(margin, currentY)
          layout.draw(canvas)
          canvas.restore()
          currentY += layout.height + extraSpacing
        }

        pdfDocument.finishPage(page)

        FileOutputStream(pdfFile).use { out ->
          pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
        withContext(Dispatchers.Main) {
          Toast.makeText(context, "Saved PDF to Downloads/$filename", Toast.LENGTH_LONG).show()
          shareFile(context, uri, "application/pdf", article.activeTitle)
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          Toast.makeText(context, "PDF export error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  /**
   * Copy article content to clipboard
   */
  fun copyToClipboard(context: Context, article: ReaderArticle, asMarkdown: Boolean = true) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val text = if (asMarkdown) generateMarkdown(article) else article.fullPlainText
    val clip = ClipData.newPlainText("Article - ${article.activeTitle}", text)
    clipboard?.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
  }

  /**
   * Share text or URL via system share sheet
   */
  fun shareArticle(context: Context, article: ReaderArticle) {
    val sendIntent = Intent().apply {
      action = Intent.ACTION_SEND
      putExtra(Intent.EXTRA_TITLE, article.activeTitle)
      putExtra(Intent.EXTRA_TEXT, "${article.activeTitle}\n\n${article.sourceUrl}\n\n${article.paragraphs.firstOrNull()?.text?.take(200) ?: ""}")
      type = "text/plain"
    }
    val shareIntent = Intent.createChooser(sendIntent, "Share Article")
    context.startActivity(shareIntent)
  }

  private fun shareFile(context: Context, uri: Uri, mimeType: String, title: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = mimeType
      putExtra(Intent.EXTRA_STREAM, uri)
      putExtra(Intent.EXTRA_TITLE, title)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share Exported Article"))
  }

  private fun sanitizeFilename(name: String): String {
    return name.replace("[^a-zA-Z0-9._-]".toRegex(), "_").take(50).ifBlank { "reader_export" }
  }

  private fun escapeHtml(text: String): String {
    return text.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")
  }
}
