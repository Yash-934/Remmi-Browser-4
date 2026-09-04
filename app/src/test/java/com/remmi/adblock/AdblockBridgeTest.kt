package com.remmi.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdblockBridgeTest {

  private lateinit var bridge: AdblockBridge

  @Before
  fun setUp() {
    bridge = AdblockBridge.getInstance()
    bridge.compileRules("")
  }

  @Test
  fun testGoogleTagManagerGtagJsBlocked() {
    val decision = bridge.evaluateDecision(
      url = "https://www.googletagmanager.com/gtag/js?id=G-EPK7X69JWC",
      sourceUrl = "https://adblock-tester.com/",
      initiator = "https://adblock-tester.com/",
      method = "GET",
      resourceType = "script",
      aggressive = false,
      thirdParty = true
    )
    assertTrue("gtag.js request must be blocked", decision.blocked)
  }

  @Test
  fun testGoogleAnalyticsCollectBlocked() {
    val decision = bridge.evaluateDecision(
      url = "https://www.google-analytics.com/g/collect?v=2&tid=G-EPK7X69JWC&cid=555.666",
      sourceUrl = "https://adblock-tester.com/",
      initiator = "https://adblock-tester.com/",
      method = "POST",
      resourceType = "ping",
      aggressive = false,
      thirdParty = true
    )
    assertTrue("google-analytics collect request must be blocked", decision.blocked)
  }

  @Test
  fun testFirstPartyCleanResourceAllowed() {
    val decision = bridge.evaluateDecision(
      url = "https://example.com/main.bundle.js",
      sourceUrl = "https://example.com/",
      initiator = "https://example.com/",
      method = "GET",
      resourceType = "script",
      aggressive = false,
      thirdParty = false
    )
    assertFalse("First party script on clean site must be allowed", decision.blocked)
  }

  @Test
  fun testRuleCompilationAndExceptionLifecycle() {
    val customRules = """
      ||custom-adnetwork.net^
      @@||safe.custom-adnetwork.net^
      ||generic-tracker.com^${'$'}important
    """.trimIndent()

    val count = bridge.compileRules(customRules)
    assertTrue("Compiled count must be positive", count > 0)

    val blockedDec = bridge.evaluateDecision("https://custom-adnetwork.net/ads.js")
    assertTrue("custom-adnetwork.net must be blocked", blockedDec.blocked)

    val allowedDec = bridge.evaluateDecision("https://safe.custom-adnetwork.net/script.js")
    assertFalse("safe.custom-adnetwork.net must be allowed via exception", allowedDec.blocked)
  }

  @Test
  fun testApiCompatibilityGatingRegression() {
    // 1. If API version is 0 (missing symbol/unknown), compatibility must be false and safe fallback used
    val compat0 = bridge.verifyNativeCompatibility(0)
    assertFalse("API version 0 must not be considered compatible with v2 extensions", compat0)

    // 2. If API version is 1 (legacy single-string), compatibility must be false
    val compat1 = bridge.verifyNativeCompatibility(1)
    assertFalse("API version 1 must not be considered compatible with v2 extensions", compat1)

    // 3. If API version is 2 (explicit v2), compatibility must be true
    val compat2 = bridge.verifyNativeCompatibility(2)
    assertTrue("API version 2 must be considered compatible with v2 extensions", compat2)

    // 4. In the absence of nativeGetApiVersion(), getApiVersion() must report actual numeric version (0 for unknown)
    val actualApiVersion = bridge.getApiVersion()
    if (!bridge.isNativeLoaded || actualApiVersion == 0) {
      assertFalse("isNativeHiddenClassIdCompatible must be false when API version is UNKNOWN (0)", bridge.isNativeHiddenClassIdCompatible)
    }

    // 5. Verify fallback cosmetic resource extraction executes safely without crashing
    val fallbackCosmetics = bridge.getHiddenClassIdSelectors(
      classes = listOf("ad-banner", "tracker-box"),
      ids = listOf("sponsor-link", "analytics-widget"),
      exceptions = emptyList()
    )
    assertNotNull(fallbackCosmetics)
    assertTrue(fallbackCosmetics.ok)
  }
}
