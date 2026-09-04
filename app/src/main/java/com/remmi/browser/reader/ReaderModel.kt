package com.remmi.browser.reader

import android.content.Context
import android.util.Log
import com.remmi.browser.security.CurrentTorRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Scanner
import java.util.concurrent.TimeUnit

data class ReaderParagraph(
  val index: Int,
  val text: String,
  val isHeading: Boolean = false,
  val headingLevel: Int = 0,
)

data class ReaderHighlight(
  val paragraphIndex: Int,
  val colorHex: String = "#FFEB3B", // Default yellow
  val isUnderline: Boolean = false,
  val note: String = "",
)

data class ReaderArticle(
  val title: String,
  val byline: String = "",
  val siteName: String = "",
  val excerpt: String = "",
  val paragraphs: List<ReaderParagraph> = emptyList(),
  val rawTextList: List<String> = emptyList(),
  val readingTimeMinutes: Int = 1,
  val sourceUrl: String = "",
  val leadImageUrl: String? = null,
  val translatedTitle: String? = null,
  val translatedParagraphs: List<ReaderParagraph>? = null,
  val targetLanguage: String? = null,
) {
  val fullPlainText: String
    get() = paragraphs.joinToString("\n\n") { it.text }

  val activeParagraphs: List<ReaderParagraph>
    get() = translatedParagraphs ?: paragraphs

  val activeTitle: String
    get() = translatedTitle ?: title

  val wordCount: Int
    get() = paragraphs.sumOf { p ->
      if (p.text.isBlank()) 0
      else p.text.trim().split("\\s+".toRegex()).size
    }

  fun toJson(): String {
    val root = JSONObject()
    root.put("title", title)
    root.put("byline", byline)
    root.put("siteName", siteName)
    root.put("excerpt", excerpt)
    root.put("readingTimeMinutes", readingTimeMinutes)
    root.put("sourceUrl", sourceUrl)
    if (leadImageUrl != null) root.put("leadImageUrl", leadImageUrl)
    val parArray = org.json.JSONArray()
    paragraphs.forEach { p ->
      val pObj = JSONObject()
      pObj.put("index", p.index)
      pObj.put("text", p.text)
      pObj.put("isHeading", p.isHeading)
      pObj.put("headingLevel", p.headingLevel)
      parArray.put(pObj)
    }
    root.put("paragraphs", parArray)
    return root.toString()
  }

  companion object {
    fun fromJson(jsonStr: String): ReaderArticle? {
      if (jsonStr.isBlank()) return null
      return try {
        val root = JSONObject(jsonStr)
        val pars = mutableListOf<ReaderParagraph>()
        val parArray = root.optJSONArray("paragraphs")
        if (parArray != null) {
          for (i in 0 until parArray.length()) {
            val pObj = parArray.getJSONObject(i)
            pars.add(
              ReaderParagraph(
                index = pObj.optInt("index", i),
                text = pObj.optString("text", ""),
                isHeading = pObj.optBoolean("isHeading", false),
                headingLevel = pObj.optInt("headingLevel", 0)
              )
            )
          }
        }
        ReaderArticle(
          title = root.optString("title", ""),
          byline = root.optString("byline", ""),
          siteName = root.optString("siteName", ""),
          excerpt = root.optString("excerpt", ""),
          paragraphs = pars,
          readingTimeMinutes = root.optInt("readingTimeMinutes", 1),
          sourceUrl = root.optString("sourceUrl", ""),
          leadImageUrl = if (root.has("leadImageUrl") && !root.isNull("leadImageUrl")) root.optString("leadImageUrl") else null
        )
      } catch (e: Exception) {
        null
      }
    }
  }
}

object ReaderExtractor {
  private const val TAG = "ReaderExtractor"

  private const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L // 2 MB cap
  private const val MAX_ARTICLE_PARAGRAPHS = 500

  /**
   * Fetches the web page asynchronously and extracts full clean article content
   */
  suspend fun extractFromUrl(context: Context, url: String, currentTitle: String = "", isGhost: Boolean = false): ReaderArticle? = withContext(Dispatchers.IO) {
    val domain = try {
      URI(url).host ?: url.substringAfter("://").substringBefore('/')
    } catch (e: Exception) {
      url.substringAfter("://").substringBefore('/')
    }

    // SSRF and Scheme Gate
    if (!com.remmi.browser.security.RedirectInspector.isSchemeSafeForNavigation(url)) {
      Log.w(TAG, "Reader extraction blocked: Unsupported or unsafe URL scheme ($url)")
      return@withContext null
    }

    if (com.remmi.browser.security.NavigationSecurityAuthority.isPrivateOrLocalHost(domain, isGhost || com.remmi.browser.security.NetworkRouteAuthority.isOnionDestination(url))) {
      Log.w(TAG, "Reader extraction blocked: Local/Private address targets prohibited ($domain)")
      return@withContext null
    }

    val isOnion = com.remmi.browser.security.NetworkRouteAuthority.isOnionDestination(url)
    if ((isGhost || isOnion) && !com.remmi.browser.security.CurrentTorRoute.isReady) {
      Log.w(TAG, "Reader extraction blocked: Tor route is not verified")
      return@withContext null
    }

    val runtime = com.remmi.browser.engine.GeckoEngineManager.getInstance(context).runtime
    if (runtime == null) {
      Log.w(TAG, "Reader extraction failed: Gecko runtime not available")
      return@withContext null
    }

    try {
      kotlinx.coroutines.withTimeout(20_000L) {
        val executor = GeckoWebExecutor(runtime)
        val request = WebRequest.Builder(url)
          .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0")
          .build()

        val response = executor.fetch(request).poll(15000)
        if (response == null) {
          Log.w(TAG, "Reader request timed out for $url")
          return@withTimeout null
        }
        if (response.statusCode != 200) {
          Log.w(TAG, "Reader fetch returned HTTP ${response.statusCode} for $url")
          return@withTimeout null
        }
        
        val bodyStream = response.body
        if (bodyStream == null) {
          Log.w(TAG, "Reader fetch returned empty stream for $url")
          return@withTimeout null
        }
        val html = Scanner(bodyStream, "UTF-8").useDelimiter("\\A").next()
        bodyStream.close()
        if (html.isBlank()) {
          Log.w(TAG, "Reader fetch body content is blank for $url")
          return@withTimeout null
        }
        parseHtmlDocument(html, url, currentTitle, domain)
      }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
      Log.e(TAG, "Reader extraction timed out (20s limit) for $url", e)
      null
    } catch (e: Exception) {
      Log.e(TAG, "Failed to extract article from $url", e)
      null
    }
  }

  fun parseHtmlDocument(
    html: String,
    url: String,
    fallbackTitle: String,
    domain: String,
  ): ReaderArticle? {
    val boundedHtml = if (html.length > 2_000_000) html.take(2_000_000) else html
    val doc: Document = Jsoup.parse(boundedHtml, url)

    // Extract Title
    val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
    val twitterTitle = doc.selectFirst("meta[name=twitter:title]")?.attr("content")?.trim()
    val h1Title = doc.selectFirst("h1")?.text()?.trim()
    val docTitle = doc.title().trim()

    val title = listOfNotNull(ogTitle, twitterTitle, h1Title, docTitle, fallbackTitle)
      .firstOrNull { it.isNotBlank() } ?: "Reader Mode Article"

    // Extract Author / Byline
    val author = doc.selectFirst("meta[name=author]")?.attr("content")?.trim()
      ?: doc.selectFirst("meta[property=article:author]")?.attr("content")?.trim()
      ?: doc.selectFirst("[rel=author], .byline, .author, .by-line, .entry-author")?.text()?.trim()
      ?: ""

    // Extract Site Name
    val siteName = doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.trim()
      ?: domain.removePrefix("www.")

    // Lead Image
    val leadImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
      ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")

    // Find main article container
    val contentContainer: Element = doc.selectFirst(".mw-parser-output") // Wikipedia
      ?: doc.selectFirst("article")
      ?: doc.selectFirst("[role=main]")
      ?: doc.selectFirst(".post-content, .entry-content, .article-content, .story-body, .article-body, #article-body, #content")
      ?: doc.body()

    // Clone to manipulate without altering original doc
    val cleanContainer = contentContainer.clone()

    // Remove noise elements
    val noiseSelectors = listOf(
      "script", "style", "noscript", "nav", "header", "footer", "iframe", "aside",
      ".ad", ".ads", ".advertisement", ".social-share", ".share-box", ".comments",
      ".sidebar", ".menu", ".nav", ".cookie-banner", ".banner", ".mw-editsection",
      ".reflist", ".reference", ".infobox", ".navbox", ".hatnote", ".noprint",
      ".metadata", ".thumbcaption", "form", "button", "input"
    )
    for (sel in noiseSelectors) {
      cleanContainer.select(sel).remove()
    }

    // Extract paragraphs and headings using aggressive newline-preserving extraction
    // This forces Read Mode to work on ALL websites even if they lack standard <p> tags.
    cleanContainer.select("br").append(" _NEWLINE_ ")
    cleanContainer.select("p, h1, h2, h3, h4, h5, h6, blockquote, li").prepend(" _NEWLINE_ _NEWLINE_ ")
    cleanContainer.select("div, section, article").prepend(" _NEWLINE_ ")

    var bodyText = cleanContainer.text()
    // Replace the newline markers with actual newlines, accounting for possible spaces Jsoup adds
    bodyText = bodyText.replace(Regex("\\s*_NEWLINE_\\s*"), "\n").trim()
    // Collapse 3+ newlines into 2
    bodyText = bodyText.replace(Regex("\n{3,}"), "\n\n")

    val chunks = bodyText.split(Regex("\n\\s*\n"))
      .filter { it.trim().length > 15 }
      .take(MAX_ARTICLE_PARAGRAPHS)

    val paragraphs = mutableListOf<ReaderParagraph>()
    val rawList = mutableListOf<String>()

    chunks.forEachIndexed { idx, chunk ->
      val text = chunk.trim().take(10_000)
      // Basic heading detection: short, no terminal punctuation
      val isHeading = text.length in 5..80 && !text.matches(Regex(".*[.?!]$"))
      val headingLevel = if (isHeading) 2 else 0

      paragraphs.add(
        ReaderParagraph(
          index = idx,
          text = text,
          isHeading = isHeading,
          headingLevel = headingLevel,
        )
      )
      rawList.add(text)
    }

    if (paragraphs.isEmpty()) {
      val elements = doc.body().select("p, h1, h2, h3, h4, h5, h6, li, blockquote, div")
      var idx = 0
      for (el in elements) {
        val txt = el.ownText().trim()
        if (txt.length > 20 && !rawList.contains(txt)) {
          val isHeading = el.tagName().startsWith("h", ignoreCase = true)
          val headingLevel = if (isHeading) el.tagName().substring(1).toIntOrNull() ?: 2 else 0
          paragraphs.add(
            ReaderParagraph(
              index = idx++,
              text = txt,
              isHeading = isHeading,
              headingLevel = headingLevel
            )
          )
          rawList.add(txt)
        }
      }
      if (paragraphs.isEmpty()) {
        val backup = doc.body().text().trim()
        if (backup.isNotBlank()) {
          val fallbackChunks = backup.split(Regex("\n+")).filter { it.trim().length > 15 }
          if (fallbackChunks.isNotEmpty()) {
            fallbackChunks.forEachIndexed { fIdx, fChunk ->
              paragraphs.add(ReaderParagraph(fIdx, fChunk.trim()))
              rawList.add(fChunk.trim())
            }
          } else {
            paragraphs.add(ReaderParagraph(0, backup))
            rawList.add(backup)
          }
        }
      }
    }

    if (paragraphs.isEmpty()) {
      Log.w(TAG, "No readable paragraphs could be extracted from $url")
      return null
    }

    val totalWords = rawList.sumOf { it.split("\\s+".toRegex()).size }
    val readingTime = Math.max(1, Math.ceil(totalWords / 200.0).toInt())

    return ReaderArticle(
      title = title,
      byline = author,
      siteName = siteName,
      paragraphs = paragraphs,
      rawTextList = rawList,
      readingTimeMinutes = readingTime,
      sourceUrl = url,
      leadImageUrl = leadImage,
    )
  }

  fun parseArticle(json: JSONObject, fallbackUrl: String = ""): ReaderArticle? {
    val title = json.optString("title").ifBlank { "Untitled Article" }
    val byline = json.optString("byline")
    val siteName = json.optString("siteName")
    val readTime = json.optInt("readingTimeMinutes", 1)
    val sourceUrl = json.optString("sourceUrl", fallbackUrl)
    val jsonArray = json.optJSONArray("paragraphs")
    val paragraphs = mutableListOf<ReaderParagraph>()
    val rawList = mutableListOf<String>()

    if (jsonArray != null) {
      for (i in 0 until jsonArray.length()) {
        val p = jsonArray.optString(i)
        if (p.isNotBlank()) {
          paragraphs.add(ReaderParagraph(index = i, text = p))
          rawList.add(p)
        }
      }
    }
    if (paragraphs.isEmpty()) return null
    return ReaderArticle(
      title = title,
      byline = byline,
      siteName = siteName,
      paragraphs = paragraphs,
      rawTextList = rawList,
      readingTimeMinutes = readTime,
      sourceUrl = sourceUrl,
    )
  }
}

