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
  private val adKeywords = listOf(
    "doubleclick", "adservice", "googleads", "adnxs", "pagead", "moatads", "outbrain", "taboola",
    "criteo", "amazon-adsystem", "advertising", "adserver", "adskeeper", "popads", "propellerads",
    "mgid", "zergnet", "adroll", "adtech", "bidswitch", "rubiconproject", "pubmatic", "openx",
    "appnexus", "banner", "sponsor", "affiliate", "adblock-tester", "turtlecute", "adsystem",
    "adcolony", "ironsrc", "vungle", "unityads", "admob", "smartadserver", "adition"
  )
  private val analyticsKeywords = listOf(
    "analytics", "telemetry", "statcounter", "hotjar", "mixpanel", "segment.io", "amplitude",
    "newrelic", "sentry", "googletagmanager", "clarity.ms", "yandex.ru/metrika", "scorecardresearch",
    "quantserve", "matomo", "piwik", "chartbeat", "inspectlet", "crazyegg", "mouseflow",
    "fullstory", "beacon", "pixel", "tracking", "log_event", "stats", "hitcounter"
  )
  private val socialKeywords = listOf(
    "facebook.com/tr", "connect.facebook", "platform.twitter", "linkedin.com/px",
    "tiktok.com/i18n/pixel", "pinterest.com/ct", "instagram.com", "threads.net", "snapchat.com/tr"
  )
  private val cryptomineKeywords = listOf(
    "coinhive", "cryptoloot", "jsecoin", "miner", "webminepool", "coin-hive", "minero", "monerominer"
  )
  private val fingerprintKeywords = listOf(
    "fingerprint", "fpjs", "client-id", "device-id", "canvas-fingerprint", "audio-fingerprint",
    "font-probe", "webgl-fingerprint", "deviceinfo", "audio-context", "battery-status"
  )

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
