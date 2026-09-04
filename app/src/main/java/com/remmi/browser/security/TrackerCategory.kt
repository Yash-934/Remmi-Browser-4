package com.remmi.browser.security

/**
 * Categories for tracker blocking and classification.
 */
enum class TrackerCategory(
  val displayName: String,
  val description: String
) {
  ADVERTISING("Advertising", "Ad networks, promotional pixels, and conversion trackers"),
  ANALYTICS("Analytics & Metrics", "Telemetry, behavioral analytics, and heatmaps"),
  SOCIAL("Social Tracking", "Social widget trackers, embed beacons, and login monitors"),
  CRYPTOMINING("Cryptomining", "Unauthorized background in-browser cryptocurrency miners"),
  FINGERPRINTING("Fingerprinting", "Canvas, WebGL, AudioContext, and Font probing scripts"),
  OTHER("Unknown / Generic", "Suspicious tracking payloads")
}

data class TrackerEvent(
  val url: String,
  val host: String,
  val category: TrackerCategory,
  val timestamp: Long = System.currentTimeMillis()
)

object TrackerClassifier {
  private val adKeywords = listOf("doubleclick", "adservice", "googleads", "adnxs", "pagead", "moatads", "outbrain", "taboola", "criteo")
  private val analyticsKeywords = listOf("analytics", "telemetry", "statcounter", "hotjar", "mixpanel", "segment.io", "amplitude", "newrelic", "sentry")
  private val socialKeywords = listOf("facebook.com/tr", "connect.facebook", "platform.twitter", "linkedin.com/px", "tiktok.com/i18n/pixel", "pinterest.com/ct")
  private val cryptomineKeywords = listOf("coinhive", "cryptoloot", "jsecoin", "miner", "webminepool")
  private val fingerprintKeywords = listOf("fingerprint", "fpjs", "client-id", "device-id", "canvas-fingerprint", "audio-fingerprint")

  fun classify(url: String): TrackerCategory {
    val lower = url.lowercase()
    return when {
      cryptomineKeywords.any { lower.contains(it) } -> TrackerCategory.CRYPTOMINING
      fingerprintKeywords.any { lower.contains(it) } -> TrackerCategory.FINGERPRINTING
      socialKeywords.any { lower.contains(it) } -> TrackerCategory.SOCIAL
      analyticsKeywords.any { lower.contains(it) } -> TrackerCategory.ANALYTICS
      adKeywords.any { lower.contains(it) } -> TrackerCategory.ADVERTISING
      else -> TrackerCategory.ADVERTISING
    }
  }
}
