package com.remmi.browser.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.adblock.AdblockBridge
import com.remmi.adblock.FilterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterSubscriptionPipelineTest {

  private lateinit var context: Context
  private lateinit var adblockBridge: AdblockBridge
  private lateinit var filterManager: FilterManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    adblockBridge = AdblockBridge()
    filterManager = FilterManager(adblockBridge, context)
  }

  @Test
  fun testAdblockBridgeFallbackRulesAndMatching() = runBlocking {
    // Verify default tracker domain is blocked by default fallback
    val blocked = adblockBridge.shouldBlock("https://google-analytics.com/analytics.js")
    assertTrue("google-analytics.com should be blocked", blocked)

    val doubleclickBlocked = adblockBridge.shouldBlock("https://adservice.google.com/ads?id=123")
    assertTrue("adservice.google.com should be blocked", doubleclickBlocked)

    // Clean site should be allowed
    val allowed = adblockBridge.shouldBlock("https://en.wikipedia.org/wiki/Tor")
    assertFalse("wikipedia.org should be allowed", allowed)

    // Custom compilation
    val customRules = """
      ||malware-tracker.com^
      ||bad-ad-network.net^
      @@||good-tracker.org^
    """.trimIndent()

    val count = adblockBridge.compileRules(customRules)
    assertTrue("Compiled count should be at least 3", count >= 3)

    val blockedCustom = adblockBridge.shouldBlock("https://malware-tracker.com/track")
    assertTrue("malware-tracker.com should be blocked", blockedCustom)

    val allowedException = adblockBridge.shouldBlock("https://good-tracker.org/ping")
    assertFalse("good-tracker.org should be allowed via @@ exception", allowedException)
  }

  @Test
  fun testFilterManagerSubscriptionsLifecycle() {
    val subs = filterManager.subscriptions.value
    assertTrue("Default subscription list should not be empty", subs.isNotEmpty())

    // Fresh install metadata should not have fake hardcoded numbers
    for (sub in subs) {
      if (sub.lastUpdated == 0L) {
        assertEquals("Un-downloaded list must have 0 ruleCount initially", 0, sub.ruleCount)
      }
    }

    val firstSub = subs.first()
    val initialEnabled = firstSub.enabled

    filterManager.toggleSubscription(firstSub.id)
    val toggledSub = filterManager.subscriptions.value.first { it.id == firstSub.id }
    assertEquals(!initialEnabled, toggledSub.enabled)

    // Toggle back
    filterManager.toggleSubscription(firstSub.id)
    val restoredSub = filterManager.subscriptions.value.first { it.id == firstSub.id }
    assertEquals(initialEnabled, restoredSub.enabled)
  }

  @Test
  fun testAdblockBridgePreservesBaselineRulesAcrossCompilations() {
    val baselineDecision = adblockBridge.evaluateDecision("https://google-analytics.com/analytics.js")
    assertTrue("Baseline rule must be blocked before custom compilation", baselineDecision.blocked)

    // Compile external list
    val count = adblockBridge.compileRules("||custom-popup-ad.org^")
    assertTrue("Compiled count must be positive", count > 0)

    // Verify both external rule AND baseline rule are active
    val externalDecision = adblockBridge.evaluateDecision("https://custom-popup-ad.org/ad.js")
    assertTrue("External custom rule must be blocked", externalDecision.blocked)

    val postCompileBaselineDecision = adblockBridge.evaluateDecision("https://google-analytics.com/analytics.js")
    assertTrue("Baseline rule must remain active after external compilation", postCompileBaselineDecision.blocked)
    assertTrue("Engine generation must be positive", postCompileBaselineDecision.engineGeneration > 0)
  }

  @Test
  fun testAdblockBridgeConcurrentEvaluation(): Unit = runBlocking(Dispatchers.Default) {
    adblockBridge.compileRules("||concurrent-test-tracker.com^")
    val jobs = (1..100).map { i ->
      async {
        val isAd = (i % 2 == 0)
        val url = if (isAd) "https://concurrent-test-tracker.com/pixel_$i.png" else "https://github.com/torproject/tor/commit_$i"
        val decision = adblockBridge.evaluateDecision(url)
        assertEquals(isAd, decision.blocked)
        assertTrue(decision.engineGeneration > 0)
      }
    }
    jobs.awaitAll()
  }

  @Test
  fun testValidOldEngineSurvivesFailedOrEmptyUpdate() {
    adblockBridge.compileRules("||resilient-ad-server.com^")
    val before = adblockBridge.evaluateDecision("https://resilient-ad-server.com/banner.js")
    assertTrue("Initial rule must block", before.blocked)

    // Compile empty rules
    val compiledEmpty = adblockBridge.compileRules("   \n! comments only\n  ")
    assertEquals(0, compiledEmpty)

    val after = adblockBridge.evaluateDecision("https://resilient-ad-server.com/banner.js")
    assertTrue("Engine must retain previous rules after failed or empty compile", after.blocked)
  }

  @Test
  fun testGenericCosmeticRule() {
    adblockBridge.compileRules("##.ad-banner\n##.sponsored-post")
    val res = adblockBridge.getCosmeticResources("https://news.example.com/article")
    assertTrue("Cosmetic lookup must succeed", res.ok)
    assertTrue("Generic hide selector must be present", res.hideSelectors.contains(".ad-banner"))
    assertTrue("Generic hide selector 2 must be present", res.hideSelectors.contains(".sponsored-post"))
  }

  @Test
  fun testDomainSpecificCosmeticRule() {
    adblockBridge.compileRules("example.com##.target-ad\nother.com##.other-ad")
    val exampleRes = adblockBridge.getCosmeticResources("https://example.com/feed")
    assertTrue("example.com must receive target-ad selector", exampleRes.hideSelectors.contains(".target-ad"))
    assertFalse("example.com must not receive other-ad selector", exampleRes.hideSelectors.contains(".other-ad"))

    val otherRes = adblockBridge.getCosmeticResources("https://other.com/feed")
    assertTrue("other.com must receive other-ad selector", otherRes.hideSelectors.contains(".other-ad"))
    assertFalse("other.com must not receive target-ad selector", otherRes.hideSelectors.contains(".target-ad"))
  }

  @Test
  fun testCosmeticRuleException() {
    adblockBridge.compileRules("##.global-ad\nexample.com#@#.global-ad")
    val nonExemptRes = adblockBridge.getCosmeticResources("https://clean-site.org/page")
    assertTrue("Non-exempt site must hide global-ad", nonExemptRes.hideSelectors.contains(".global-ad"))

    val exemptRes = adblockBridge.getCosmeticResources("https://example.com/page")
    assertFalse("Exempt site must not hide global-ad", exemptRes.hideSelectors.contains(".global-ad"))
  }

  @Test
  fun testGoogleAnalyticsAndGtagBootstrapEnforcement() {
    // 1. Real-style googletagmanager gtag bootstrap request
    val gtagDecision = adblockBridge.evaluateDecision(
      url = "https://www.googletagmanager.com/gtag/js?id=G-EPK7X69JWC",
      sourceUrl = "https://adblock-tester.com/",
      initiator = "https://adblock-tester.com",
      resourceType = "script",
      thirdParty = true
    )
    assertTrue("googletagmanager.com/gtag/js bootstrapper must be blocked", gtagDecision.blocked)

    // 2. Real-style google-analytics collection beacon request
    val gaCollectDecision = adblockBridge.evaluateDecision(
      url = "https://www.google-analytics.com/g/collect?v=2&tid=G-EPK7X69JWC&cid=123.456",
      sourceUrl = "https://adblock-tester.com/",
      initiator = "https://adblock-tester.com",
      resourceType = "ping",
      thirdParty = true
    )
    assertTrue("google-analytics.com/g/collect telemetry beacon must be blocked", gaCollectDecision.blocked)

    // 3. Harmless first-party scripts must remain allowed
    val firstPartyScriptDecision = adblockBridge.evaluateDecision(
      url = "https://adblock-tester.com/assets/app.js",
      sourceUrl = "https://adblock-tester.com/",
      initiator = "https://adblock-tester.com",
      resourceType = "script",
      thirdParty = false
    )
    assertFalse("Harmless first-party script must be allowed", firstPartyScriptDecision.blocked)

    // 4. Harmless first-party HTML navigation must remain allowed
    val firstPartyDocDecision = adblockBridge.evaluateDecision(
      url = "https://adblock-tester.com/",
      sourceUrl = "",
      initiator = "",
      resourceType = "main_frame",
      thirdParty = false
    )
    assertFalse("Harmless first-party document must be allowed", firstPartyDocDecision.blocked)
  }
}

