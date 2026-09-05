package com.remmi.browser.security

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

enum class ConfidenceLevel {
  HIGH,
  MEDIUM,
  LOW
}

enum class ClickTargetType {
  VISIBLE_ELEMENT,
  OVERLAY,
  SUSPICIOUS_OVERLAY,
  FULL_SCREEN_OVERLAY,
  PARENT_ANCHOR,
  ONCLICK_SCRIPT,
  FORM_ACTION,
  IFRAME_INTERCEPT
}

data class ClickTargetCandidate(
  val label: String,
  val url: String,
  val cleanUrl: String,
  val type: ClickTargetType,
  val tagName: String,
  val isOverlay: Boolean,
  val isTransparent: Boolean,
  val confidence: ConfidenceLevel,
  val details: String,
)

data class ClickInspectionResult(
  val candidates: List<ClickTargetCandidate>,
  val hasOverlay: Boolean,
  val primaryTarget: ClickTargetCandidate?,
)

/**
 * Click Transparency & DOM Inspection Pipeline
 * Bounded inspection for identifying real click targets, deceptive full-screen/high z-index overlays,
 * static JS navigations (location.href, location.assign, location.replace, window.open, indexed access),
 * and presenting user-controllable candidate destinations.
 */
object ClickTargetAnalyzer {

  private val JS_NAV_PATTERNS = listOf(
    // 1. location.href = "..." | location.assign("...") | location.replace("...")
    Pattern.compile("""(?:window\.)?location(?:\.href|\.assign|\.replace)?\s*(?:=|\()\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE),
    // 2. window.open("...")
    Pattern.compile("""window\.open\s*\(\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE),
    // 3. location["href"] = "..." | window["location"]["replace"]("...") | window["location"]["assign"]("...")
    Pattern.compile("""(?:window\["location"\]|location)\["href"\]\s*=\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE),
    Pattern.compile("""(?:window\["location"\]|location)\["(?:replace|assign)"\]\s*\(\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE),
  )

  /**
   * Statically parses JavaScript snippets (e.g. onclick, inline event handler) for navigation patterns.
   */
  fun parseJsNavigationPatterns(script: String): List<Pair<String, ConfidenceLevel>> {
    if (script.isBlank()) return emptyList()
    val results = mutableListOf<Pair<String, ConfidenceLevel>>()
    val seen = mutableSetOf<String>()

    for (pattern in JS_NAV_PATTERNS) {
      val matcher = pattern.matcher(script)
      while (matcher.find()) {
        val extracted = matcher.group(1)?.trim().orEmpty()
        if (extracted.isNotBlank() && isValidUrlCandidate(extracted)) {
          if (seen.add(extracted)) {
            val confidence = if (extracted.startsWith("http://") || extracted.startsWith("https://")) {
              ConfidenceLevel.HIGH
            } else {
              ConfidenceLevel.MEDIUM
            }
            results.add(Pair(extracted, confidence))
          }
        }
      }
    }

    // Try decoding nested URI / Base64 patterns within onclick scripts
    val decodedNested = extractNestedFromScript(script)
    for (item in decodedNested) {
      if (seen.add(item.first)) {
        results.add(item)
      }
    }

    return results
  }

  private fun extractNestedFromScript(script: String): List<Pair<String, ConfidenceLevel>> {
    val found = mutableListOf<Pair<String, ConfidenceLevel>>()

    // Look for URL-encoded parameters (e.g. url%3Dhttps%253A%252F%252F...)
    if (script.contains("%3A%2F%2F", ignoreCase = true) || script.contains("%2F")) {
      try {
        val decoded = URLDecoder.decode(script, "UTF-8")
        val matcher = Pattern.compile("""https?://[^\s"'<>]+""").matcher(decoded)
        while (matcher.find()) {
          val u = matcher.group()
          found.add(Pair(u, ConfidenceLevel.MEDIUM))
        }
      } catch (_: Exception) {}
    }

    // Look for Base64 encoded URLs in scripts (e.g. aHR0cHM6Ly9...)
    val base64Matcher = Pattern.compile("""["']([A-Za-z0-9+/=]{16,})["']""").matcher(script)
    while (base64Matcher.find()) {
      val token = base64Matcher.group(1) ?: continue
      try {
        val decoded = String(Base64.decode(token, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_PADDING), StandardCharsets.UTF_8)
        if ((decoded.startsWith("http://") || decoded.startsWith("https://")) && decoded.length < 2048) {
          found.add(Pair(decoded, ConfidenceLevel.MEDIUM))
        }
      } catch (_: Exception) {}
    }

    return found
  }

  fun getRegistrableDomain(urlStr: String): String {
    return try {
      val uri = URI(urlStr)
      val host = uri.host?.lowercase() ?: return urlStr
      if (host.matches(Regex("""^[\d.]+$|^\[.*\]$"""))) return host
      val parts = host.split('.')
      if (parts.size <= 2) return host
      val tld2 = parts.takeLast(2).joinToString(".")
      val multiPartTlds = setOf("co.uk", "com.au", "co.nz", "org.uk", "co.jp", "com.br", "gov.uk", "edu.au")
      if (multiPartTlds.contains(tld2) && parts.size > 2) {
        parts.takeLast(3).joinToString(".")
      } else {
        parts.takeLast(2).joinToString(".")
      }
    } catch (_: Exception) {
      urlStr
    }
  }

  /**
   * Evaluates combined evidence to decide if pre-navigation interception is warranted.
   */
  fun shouldIntercept(
    candidates: List<ClickTargetCandidate>,
    hasOverlay: Boolean = false,
    directTargetText: String? = null
  ): Boolean {
    if (candidates.isEmpty()) return false

    // 1. Suspicious overlay candidate exists with navigation target
    val overlayCandidate = candidates.firstOrNull { it.isOverlay }
    if (overlayCandidate != null) {
      val visibleCandidate = candidates.firstOrNull { !it.isOverlay }
      if (visibleCandidate != null) {
        val regOverlay = getRegistrableDomain(overlayCandidate.url)
        val regVisible = getRegistrableDomain(visibleCandidate.url)
        if (overlayCandidate.url != visibleCandidate.url || regOverlay != regVisible) {
          return true
        }
      }
      if (overlayCandidate.details.contains("popup", ignoreCase = true)) {
        return true
      }
      if (!directTargetText.isNullOrBlank()) {
        val text = directTargetText.trim()
        if (text.startsWith("http://") || text.startsWith("https://")) {
          val textDomain = getRegistrableDomain(text)
          val overlayDomain = getRegistrableDomain(overlayCandidate.url)
          if (textDomain.isNotBlank() && overlayDomain.isNotBlank() && textDomain != overlayDomain) {
            return true
          }
        }
      }
    }

    // 2. Suspicious popup on transparent/overlay layer
    if (candidates.any { it.details.contains("popup", ignoreCase = true) && (it.isOverlay || it.isTransparent) }) {
      return true
    }

    // 3. Visible text domain mismatch
    if (!directTargetText.isNullOrBlank()) {
      val text = directTargetText.trim()
      if (text.startsWith("http://") || text.startsWith("https://")) {
        val textDomain = getRegistrableDomain(text)
        val targetDomain = getRegistrableDomain(candidates[0].url)
        if (textDomain.isNotBlank() && targetDomain.isNotBlank() && textDomain != targetDomain) {
          return true
        }
      }
    }

    // 4. Multi-target cross-domain conflict (do NOT intercept same-site nested links)
    val distinctUrls = candidates.map { it.url }.distinct()
    if (distinctUrls.size > 1) {
      val registrableDomains = distinctUrls.map { getRegistrableDomain(it) }.distinct()
      if (registrableDomains.size > 1) {
        val hasConflictingLayers = candidates.any {
          it.isTransparent || it.isOverlay || it.type == ClickTargetType.SUSPICIOUS_OVERLAY || it.tagName != candidates[0].tagName
        }
        if (hasConflictingLayers) {
          return true
        }
      }
    }

    return false
  }

  private fun isValidUrlCandidate(str: String): Boolean {
    val trimmed = str.trim()
    if (trimmed.startsWith("javascript:void") || trimmed == "#" || trimmed == "/") return false
    return trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("/")
  }

  /**
   * Converts JSON payload received from WebExtension content script to ClickInspectionResult.
   */
  fun fromExtensionJson(jsonCandidates: List<JSONObject>, hasOverlay: Boolean): ClickInspectionResult {
    val candidates = mutableListOf<ClickTargetCandidate>()
    for (json in jsonCandidates) {
      val label = json.optString("label", "Target")
      val url = json.optString("url", "")
      if (url.isBlank()) continue
      val tagName = json.optString("tagName", "div").lowercase()
      val typeStr = json.optString("type", "VISIBLE_ELEMENT")
      val type = try {
        ClickTargetType.valueOf(typeStr)
      } catch (_: Exception) {
        if (json.optBoolean("isOverlay", false)) ClickTargetType.OVERLAY else ClickTargetType.VISIBLE_ELEMENT
      }
      val isOverlay = json.optBoolean("isOverlay", false)
      val isTransparent = json.optBoolean("isTransparent", false)
      val confStr = json.optString("confidence", "HIGH")
      val confidence = try {
        ConfidenceLevel.valueOf(confStr)
      } catch (_: Exception) {
        ConfidenceLevel.HIGH
      }
      val details = json.optString("details", "")
      val cleanUrl = RedirectInspector.stripTrackingParameters(url)

      candidates.add(
        ClickTargetCandidate(
          label = label,
          url = url,
          cleanUrl = cleanUrl,
          type = type,
          tagName = tagName,
          isOverlay = isOverlay,
          isTransparent = isTransparent,
          confidence = confidence,
          details = details
        )
      )
    }

    // Determine Visible / Semantic Target vs Suspicious Overlay Target.
    // The visible/intended target is primary; suspicious overlays are flagged for review.
    val primary = candidates.firstOrNull { !it.isOverlay } ?: candidates.firstOrNull()

    return ClickInspectionResult(
      candidates = candidates,
      hasOverlay = hasOverlay || candidates.any { it.isOverlay },
      primaryTarget = primary
    )
  }

  /**
   * Bounded DOM snippet analyzer for analyzing context HTML or element structures.
   */
  fun analyzeDomSnippet(htmlSnippet: String, baseUrl: String? = null): ClickInspectionResult {
    if (htmlSnippet.isBlank()) {
      return ClickInspectionResult(emptyList(), false, null)
    }

    val candidates = mutableListOf<ClickTargetCandidate>()
    val seen = mutableSetOf<String>()

    // 1. Anchors <a href="...">
    val anchorMatcher = Pattern.compile("""<a\s+[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(htmlSnippet)
    while (anchorMatcher.find()) {
      val rawHref = anchorMatcher.group(1)?.trim().orEmpty()
      val fullTag = anchorMatcher.group(0) ?: ""
      if (rawHref.isNotBlank() && isValidUrlCandidate(rawHref)) {
        val resolved = resolveCandidateUrl(rawHref, baseUrl)
        if (seen.add(resolved)) {
          val isTransparent = fullTag.contains("opacity: 0", ignoreCase = true) || fullTag.contains("opacity:0", ignoreCase = true) || fullTag.contains("transparent", ignoreCase = true)
          val isOverlay = isTransparent || fullTag.contains("position: fixed", ignoreCase = true) || fullTag.contains("position:fixed", ignoreCase = true) || fullTag.contains("z-index: 9", ignoreCase = true)
          
          val type = if (isOverlay) ClickTargetType.OVERLAY else ClickTargetType.VISIBLE_ELEMENT
          val label = if (isOverlay) "Deceptive Overlay Anchor" else "Anchor Link <a>"

          candidates.add(
            ClickTargetCandidate(
              label = label,
              url = resolved,
              cleanUrl = RedirectInspector.stripTrackingParameters(resolved),
              type = type,
              tagName = "a",
              isOverlay = isOverlay,
              isTransparent = isTransparent,
              confidence = ConfidenceLevel.HIGH,
              details = if (isOverlay) "Overlay anchor with transparent/fixed style" else "Standard HTML hyperlink"
            )
          )
        }
      }
    }

    // 2. Onclick navigation attributes
    val onclickMatcher = Pattern.compile("""onclick\s*=\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE).matcher(htmlSnippet)
    while (onclickMatcher.find()) {
      val onclickCode = onclickMatcher.group(1)?.trim().orEmpty()
      val extractedUrls = parseJsNavigationPatterns(onclickCode)
      for ((extracted, conf) in extractedUrls) {
        val resolved = resolveCandidateUrl(extracted, baseUrl)
        if (seen.add(resolved)) {
          candidates.add(
            ClickTargetCandidate(
              label = "JavaScript Event Navigation",
              url = resolved,
              cleanUrl = RedirectInspector.stripTrackingParameters(resolved),
              type = ClickTargetType.ONCLICK_SCRIPT,
              tagName = "button",
              isOverlay = false,
              isTransparent = false,
              confidence = conf,
              details = "Extracted from onclick event handler"
            )
          )
        }
      }
    }

    // 3. Form action
    val formMatcher = Pattern.compile("""<form\s+[^>]*action\s*=\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE).matcher(htmlSnippet)
    while (formMatcher.find()) {
      val action = formMatcher.group(1)?.trim().orEmpty()
      if (action.isNotBlank() && isValidUrlCandidate(action)) {
        val resolved = resolveCandidateUrl(action, baseUrl)
        if (seen.add(resolved)) {
          candidates.add(
            ClickTargetCandidate(
              label = "Form Submission Action",
              url = resolved,
              cleanUrl = RedirectInspector.stripTrackingParameters(resolved),
              type = ClickTargetType.FORM_ACTION,
              tagName = "form",
              isOverlay = false,
              isTransparent = false,
              confidence = ConfidenceLevel.MEDIUM,
              details = "Form submission destination"
            )
          )
        }
      }
    }

    val hasOverlay = candidates.any { it.isOverlay }
    val primary = candidates.firstOrNull { !it.isOverlay } ?: candidates.firstOrNull()

    return ClickInspectionResult(
      candidates = candidates,
      hasOverlay = hasOverlay,
      primaryTarget = primary
    )
  }

  private fun resolveCandidateUrl(raw: String, baseUrl: String?): String {
    if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
    if (baseUrl.isNullOrBlank()) return raw
    return try {
      val base = URI(baseUrl)
      base.resolve(raw).toString()
    } catch (_: Exception) {
      if (raw.startsWith("/")) {
        val prefix = if (baseUrl.startsWith("https://")) "https://" else "http://"
        val host = URI(baseUrl).host ?: ""
        "$prefix$host$raw"
      } else {
        "$baseUrl/$raw"
      }
    }
  }
}
