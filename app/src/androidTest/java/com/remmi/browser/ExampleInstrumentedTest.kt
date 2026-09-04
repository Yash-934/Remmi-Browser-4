package com.remmi.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.remmi.adblock.AdblockBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Instrumented tests executing against native libadblock_rust.so on Android.
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

  @Test
  fun useAppContext() {
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
  }

  @Test
  fun test1_nativeAdblockLibraryLoads() {
    val adblockBridge = AdblockBridge.getInstance()
    assertTrue("Native adblock library (libadblock_rust.so) MUST be available on Android device", adblockBridge.isNativeAvailable())
    assertTrue("Native adblock self-test must succeed on Android device", adblockBridge.selfTest())
  }

  @Test
  fun test2_deterministicTestRuleBlocks() {
    val adblockBridge = AdblockBridge.getInstance()
    assertTrue("Native engine required", adblockBridge.isNativeAvailable())

    val compiled = adblockBridge.compileRules("||test-ad.example^")
    assertTrue("Compiled count must be positive", compiled > 0)

    val decision = adblockBridge.evaluateDecision(
      url = "https://test-ad.example/banner.js",
      sourceUrl = "https://example.com/",
      resourceType = "script"
    )
    assertTrue("Deterministic test rule https://test-ad.example/banner.js MUST be blocked", decision.blocked)
  }

  @Test
  fun test3_cleanContentIsAllowed() {
    val adblockBridge = AdblockBridge.getInstance()
    assertTrue("Native engine required", adblockBridge.isNativeAvailable())

    val decision = adblockBridge.evaluateDecision(
      url = "https://example.com/content.js",
      sourceUrl = "https://example.com/",
      resourceType = "script"
    )
    assertFalse("Clean content https://example.com/content.js must NOT be blocked", decision.blocked)
  }

  @Test
  fun test4_100ConcurrentNativeMatchesCalls() = runBlocking(Dispatchers.Default) {
    val adblockBridge = AdblockBridge.getInstance()
    assertTrue("Native engine required", adblockBridge.isNativeAvailable())

    // Ensure baseline + test rule compiled
    adblockBridge.compileRules("||test-ad.example^\n||concurrent-banner-ad.net^")

    val completedCount = AtomicInteger(0)
    val blockedCount = AtomicInteger(0)
    val allowedCount = AtomicInteger(0)

    val jobs = (1..100).map { i ->
      async {
        val isAd = (i % 2 == 0)
        val url = if (isAd) {
          "https://concurrent-banner-ad.net/ad_$i.js"
        } else {
          "https://wikipedia.org/wiki/Page_$i.html"
        }
        val decision = adblockBridge.evaluateDecision(
          url = url,
          sourceUrl = "https://example.com/",
          resourceType = if (isAd) "script" else "main_frame"
        )
        if (decision.blocked) {
          blockedCount.incrementAndGet()
        } else {
          allowedCount.incrementAndGet()
        }
        completedCount.incrementAndGet()
      }
    }

    jobs.awaitAll()

    assertEquals("All 100 concurrent requests must complete", 100, completedCount.get())
    assertEquals("50 ad requests must be blocked", 50, blockedCount.get())
    assertEquals("50 clean requests must be allowed", 50, allowedCount.get())
  }

  @Test
  fun test5_validOldEngineSurvivesFailedOrEmptyUpdate() {
    val adblockBridge = AdblockBridge.getInstance()
    assertTrue("Native engine required", adblockBridge.isNativeAvailable())

    // 1. Compile valid rule
    adblockBridge.compileRules("||persistent-tracker.org^")
    val initialGen = adblockBridge.getEngineGeneration()

    val beforeDecision = adblockBridge.evaluateDecision(
      url = "https://persistent-tracker.org/script.js",
      sourceUrl = "https://example.com/",
      resourceType = "script"
    )
    assertTrue("Initial rule must block", beforeDecision.blocked)

    // 2. Attempt empty/invalid compilation
    val compiledEmpty = adblockBridge.compileRules("   \n! only comments\n   ")
    assertEquals("Empty rules must compile 0 rules", 0, compiledEmpty)

    // 3. Engine generation should remain consistent and previous rules must still block
    val afterDecision = adblockBridge.evaluateDecision(
      url = "https://persistent-tracker.org/script.js",
      sourceUrl = "https://example.com/",
      resourceType = "script"
    )
    assertTrue("Valid engine must survive empty/failed update", afterDecision.blocked)
  }

  @Test
  fun test6_nativeCosmeticResourceLookup() {
    val adblockBridge = AdblockBridge.getInstance()
    assertTrue("Native engine required", adblockBridge.isNativeAvailable())

    adblockBridge.compileRules("example.com##.native-banner\n##.universal-hide")
    val res = adblockBridge.getCosmeticResources("https://example.com/index.html")
    assertTrue("Cosmetic query must succeed", res.ok)
    assertTrue("Should contain domain-specific selector", res.hideSelectors.contains(".native-banner"))
    assertTrue("Should contain generic selector", res.hideSelectors.contains(".universal-hide"))
    assertTrue("Generation should be valid", res.generation > 0)
  }

  @Test
  fun test7_nativeHiddenClassIdLookup() {
    val adblockBridge = AdblockBridge.getInstance()
    assertTrue("Native engine required", adblockBridge.isNativeAvailable())

    val hidden = adblockBridge.getHiddenClassIdSelectors(
      classes = listOf("ad-slot", "main-content"),
      ids = listOf("sponsor-box", "footer"),
      exceptions = emptyList()
    )
    assertTrue("Hidden class/id query must return valid object", hidden.ok)
  }
}

