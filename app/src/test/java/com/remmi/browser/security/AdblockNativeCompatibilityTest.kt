package com.remmi.browser.security

import com.remmi.adblock.AdblockBridge
import com.remmi.adblock.AdblockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression and safety verification test for JNI ABI & signature compatibility.
 * Verifies that stale prebuilt native binaries with legacy JNI signatures are safely
 * detected and gated, preventing native crashes while preserving core adblock capabilities.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdblockNativeCompatibilityTest {

  private lateinit var adblockBridge: AdblockBridge

  @Before
  fun setUp() {
    adblockBridge = AdblockBridge()
  }

  @Test
  fun testVerifyNativeCompatibilityMatrix() {
    // Legacy 4-argument binary version must be rejected
    assertFalse(
      "Legacy version 0.8.0-remmi must not be marked compatible for 3-arg JNI signature",
      adblockBridge.verifyNativeCompatibility("adblock-rust-0.8.0-remmi", "build-12345", "arm64-v8a")
    )
    assertFalse(
      "Unknown version must be rejected",
      adblockBridge.verifyNativeCompatibility("unknown", "unknown", "unknown")
    )
    assertFalse(
      "Legacy 0.8.0 with arbitrary build ID must be rejected",
      adblockBridge.verifyNativeCompatibility("adblock-rust-0.8.0-legacy", "commit-abc1234", "x86_64")
    )

    // Rebuilt binary with version >= 0.8.1 or v2-compat build flag is accepted
    assertTrue(
      "Version 0.8.1-remmi must be accepted",
      adblockBridge.verifyNativeCompatibility("adblock-rust-0.8.1-remmi", "build-12345", "arm64-v8a")
    )
    assertTrue(
      "Version 0.8.2-remmi must be accepted",
      adblockBridge.verifyNativeCompatibility("adblock-rust-0.8.2-remmi", "ci-build-987", "arm64-v8a")
    )
    assertTrue(
      "Build with v2-compat flag must be accepted",
      adblockBridge.verifyNativeCompatibility("adblock-rust-0.8.0-remmi", "arm64-v2-compat-build", "arm64-v8a")
    )
  }

  @Test
  fun testHiddenClassIdSelectorsSafeFallbackWhenGated() {
    // When gated (isNativeHiddenClassIdCompatible = false), getHiddenClassIdSelectors must return safe fallback
    val result = adblockBridge.getHiddenClassIdSelectors(
      classes = listOf("ad-banner", "promo-box", "sponsored-item"),
      ids = listOf("top-ad", "footer-banner"),
      exceptions = listOf("good-item")
    )

    assertNotNull("CosmeticResources must not be null", result)
    assertTrue("Fallback must report ok=true", result.ok)
    assertTrue("hideSelectors must be empty on fallback", result.hideSelectors.isEmpty())
    assertTrue("forceHideSelectors must be empty on fallback", result.forceHideSelectors.isEmpty())
  }

  @Test
  fun testAdblockCoreOperationsFunctionSafely() {
    // Verify baseline adblocking operates reliably
    val trackerBlocked = adblockBridge.shouldBlock("https://google-analytics.com/collect")
    assertTrue("Tracker domain must be blocked", trackerBlocked)

    val regularAllowed = adblockBridge.shouldBlock("https://remmi-browser.org/index.html")
    assertFalse("Clean domain must be allowed", regularAllowed)

    // Verify dynamic rule compilation works
    val compiledCount = adblockBridge.compileRules("||tracker-network.com^\n@@||safe-sub.tracker-network.com^")
    assertTrue("Compiled rules count must be positive", compiledCount > 0)

    val domainBlocked = adblockBridge.shouldBlock("https://tracker-network.com/ads.js")
    assertTrue("Custom rule must block matching domain", domainBlocked)

    val exceptionAllowed = adblockBridge.shouldBlock("https://safe-sub.tracker-network.com/app.js")
    assertFalse("Exception rule must allow matching domain", exceptionAllowed)
  }
}
