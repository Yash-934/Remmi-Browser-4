package com.remmi.browser.security

import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * Remmi Anti-Fingerprinting Engine
 * Provides GeckoView RFP (Resist Fingerprinting) & FPP (Fingerprinting Protection)
 * preference sets and user agent spoofing.
 */
object AntiFingerprint {

  const val TOR_USER_AGENT =
    "Mozilla/5.0 (Android; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0"

  const val SHIELD_USER_AGENT =
    "Mozilla/5.0 (Android 14; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0 Remmi/1.0"

  /**
   * Applies privacy profile and security level settings to a GeckoSession
   */
  fun configureGeckoSession(
    session: GeckoSession,
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD
  ) {
    val settings = session.settings
    settings.useTrackingProtection = true
    settings.allowJavascript = securityLevel.javascriptEnabled
    settings.suspendMediaWhenInactive = true

    if (profile == PrivacyProfile.GHOST) {
      settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
    } else {
      settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE
    }
  }

  fun getPreferencesMap(
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    socksPort: Int? = CurrentTorRoute.currentSocksPort,
  ): Map<String, Any> {
    val activePort = socksPort ?: CurrentTorRoute.currentSocksPort ?: 0
    val baseMap = if (profile == PrivacyProfile.SHIELD) {
      mutableMapOf<String, Any>(
        "privacy.fingerprintingProtection" to true,
        "privacy.fingerprintingProtection.overrides" to "+AllTargets,-FrameRate",
        "privacy.resistFingerprinting" to false,
        "privacy.firstparty.isolate" to true,
        "network.cookie.cookieBehavior" to 5,
        "media.peerconnection.enabled" to false,
        "privacy.resistFingerprinting.letterboxing" to false,
        "privacy.trackingprotection.enabled" to true,
        "privacy.trackingprotection.socialtracking.enabled" to true,
        "privacy.trackingprotection.cryptomining.enabled" to true,
        "privacy.trackingprotection.fingerprinting.enabled" to true,
        "general.smoothScroll" to true,
        "general.smoothScroll.lines" to true,
        "general.smoothScroll.pages" to true,
        "general.smoothScroll.scrollbars" to true,
        "general.smoothScroll.other" to true,
        "general.smoothScroll.msdPhysics.enabled" to true,
        "apz.overscroll.enabled" to true,
        "apz.allow_zooming" to true,
        "apz.touch_start_tolerance" to "0.05",
        "apz.velocity_relevance_time_ms" to 300,
        "apz.max_velocity_inches_per_ms" to "70.0",
        "apz.fling_friction" to "0.002",
        "layers.acceleration.force-enabled" to true,
        "layers.async-pan-zoom.enabled" to true,
        "layers.offmainthreadcomposition.enabled" to true,
        "gfx.webrender.all" to true,
        "gfx.webrender.compositor" to true,
        "layout.css.touch_action.enabled" to true,
        "layout.css.scroll-behavior.enabled" to true,
      )
    } else {
      mutableMapOf<String, Any>(
        "privacy.resistFingerprinting" to true,
        "privacy.resistFingerprinting.letterboxing" to true,
        "privacy.firstparty.isolate" to true,
        "privacy.fingerprintingProtection" to false,
        "privacy.spoof_english" to 2,
        "javascript.use_us_english_locale" to true,
        "general.useragent.override" to TOR_USER_AGENT,
        "dom.maxHardwareConcurrency" to 2,
        "media.peerconnection.enabled" to false,
        "media.navigator.enabled" to false,
        "dom.battery.enabled" to false,
        "dom.gamepad.enabled" to false,
        "dom.vibrator.enabled" to false,
        "device.sensors.enabled" to false,
        "network.proxy.type" to 1,
        "network.proxy.socks" to "127.0.0.1",
        "network.proxy.socks_port" to activePort,
        "network.proxy.socks_version" to 5,
        "network.proxy.socks_remote_dns" to true,
        "network.proxy.failover_direct" to false,
        "network.captive-portal-service.enabled" to false,
        "network.dns.disablePrefetch" to true,
        "network.http.speculative-parallel-limit" to 0,
        "general.smoothScroll" to true,
        "general.smoothScroll.lines" to true,
        "general.smoothScroll.pages" to true,
        "general.smoothScroll.scrollbars" to true,
        "general.smoothScroll.other" to true,
        "general.smoothScroll.msdPhysics.enabled" to true,
        "apz.overscroll.enabled" to true,
        "apz.allow_zooming" to true,
        "apz.touch_start_tolerance" to "0.05",
        "apz.velocity_relevance_time_ms" to 300,
        "apz.max_velocity_inches_per_ms" to "70.0",
        "apz.fling_friction" to "0.002",
        "layers.acceleration.force-enabled" to true,
        "layers.async-pan-zoom.enabled" to true,
        "layers.offmainthreadcomposition.enabled" to true,
        "gfx.webrender.all" to true,
        "gfx.webrender.compositor" to true,
        "layout.css.touch_action.enabled" to true,
        "layout.css.scroll-behavior.enabled" to true,
        "privacy.resistFingerprinting.reduceTimerPrecision.microseconds" to 16666,
      )
    }

    // Apply SecurityLevel specific hardening
    when (securityLevel) {
      SecurityLevel.STANDARD -> {
        baseMap["javascript.enabled"] = true
        baseMap["media.autoplay.default"] = 0 // Allow autoplay
      }
      SecurityLevel.SAFER -> {
        baseMap["javascript.enabled"] = true
        baseMap["media.autoplay.default"] = 5 // Block autoplay
        baseMap["dom.audiochannel.mutedByDefault"] = true
        baseMap["security.mixed_content.block_active_content"] = true
        baseMap["security.mixed_content.block_display_content"] = true
        baseMap["svg.disabled"] = false
      }
      SecurityLevel.SAFEST -> {
        baseMap["javascript.enabled"] = false
        baseMap["media.autoplay.default"] = 5
        baseMap["media.play-stand-alone"] = false
        baseMap["security.mixed_content.block_active_content"] = true
        baseMap["security.mixed_content.block_display_content"] = true
        baseMap["svg.disabled"] = true
        baseMap["webgl.disabled"] = true
      }
    }

    return baseMap
  }
}
