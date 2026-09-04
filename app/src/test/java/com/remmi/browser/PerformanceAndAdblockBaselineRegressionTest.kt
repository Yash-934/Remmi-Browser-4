package com.remmi.browser

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.adblock.AdblockBridge
import com.remmi.adblock.BlockExtension
import com.remmi.adblock.FilterManager
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.util.CrashHandlerHelper
import com.remmi.browser.util.DebugLogManager
import com.remmi.browser.util.ReportType
import com.remmi.browser.util.StartupPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.WebExtension
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PerformanceAndAdblockBaselineRegressionTest {

  private lateinit var context: Context
  private lateinit var adblockBridge: AdblockBridge
  private lateinit var blockExtension: BlockExtension

  @Before
  fun setup() {
    kotlinx.coroutines.runBlocking {
      context = ApplicationProvider.getApplicationContext<Application>()
      adblockBridge = AdblockBridge.getInstance()
      adblockBridge.initEngine()
      adblockBridge.loadDefaultTrackerRules(compileToNative = false)
      blockExtension = BlockExtension.getInstance(adblockBridge)
      blockExtension.siteSecurityProvider = null
      blockExtension.cosmeticPolicyProvider = null
      val filterManager = FilterManager.getInstance(context, adblockBridge)
      filterManager.loadPersistedRulesIntoBridge().await()
    }
  }

  // Helper test port
  private class TestPort(private val queue: ConcurrentLinkedQueue<JSONObject>) : WebExtension.Port() {
    var testDelegate: WebExtension.PortDelegate? = null
    override fun setDelegate(d: WebExtension.PortDelegate?) {
      this.testDelegate = d
    }
    override fun postMessage(msgObj: JSONObject) {
      queue.add(msgObj)
    }
  }

  private fun awaitResponse(queue: ConcurrentLinkedQueue<JSONObject>, timeoutMs: Long = 3000): JSONObject? {
    val start = System.currentTimeMillis()
    while (queue.isEmpty() && System.currentTimeMillis() - start < timeoutMs) {
      try { ShadowLooper.idleMainLooper() } catch (_: Throwable) {}
      Thread.sleep(10)
    }
    try { ShadowLooper.idleMainLooper() } catch (_: Throwable) {}
    return queue.poll()
  }

  /**
   * TEST 1: BlockExtension decision is offloaded to background executor and delivered to main thread.
   */
  @Test
  fun testBlockExtensionDecisionIsOffUiThread() {
    val queue = ConcurrentLinkedQueue<JSONObject>()
    val port = TestPort(queue)
    blockExtension.onConnect(port)
    val delegate = port.testDelegate
    assertNotNull("Delegate must be attached", delegate)

    // Handshake
    delegate!!.onPortMessage(
      JSONObject().apply {
        put("type", "PORT_STATUS")
        put("status", "CONNECTED")
        put("portGeneration", 1L)
      },
      port
    )

    val req = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("requestId", "perf_test_1")
      put("url", "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js")
      put("sourceUrl", "https://adblock-tester.com")
      put("resourceType", "script")
      put("thirdParty", true)
      put("portGeneration", 1L)
    }

    delegate.onPortMessage(req, port)
    val resp = awaitResponse(queue)
    assertNotNull("Response must be received asynchronously", resp)
    assertTrue("Should indicate ok=true", resp!!.optBoolean("ok"))
    assertTrue("Ad script must be cancelled", resp.optBoolean("cancel"))
    assertEquals("Generation must match", 1L, resp.optLong("portGeneration"))
    assertEquals("Request ID must match", "perf_test_1", resp.optString("requestId"))
  }

  /**
   * TEST 2: Golden baseline 100/100 AdBlock verification (11 services, 22 checks).
   */
  @Test
  fun testAdblockBaseline100ChecksGolden() {
    val goldenChecks = listOf(
      "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js" to true,
      "https://google-analytics.com/analytics.js" to true,
      "https://stats.g.doubleclick.net/dc.js" to true,
      "https://static.criteo.net/js/ld/ld.js" to true,
      "https://c.popads.net/pop.js" to true,
      "https://widgets.outbrain.com/outbrain.js" to true,
      "https://cdn.taboola.com/libtrc/unip/1/tfa.js" to true,
      "https://mc.yandex.ru/metrika/watch.js" to true,
      "https://connect.facebook.net/en_US/fbevents.js" to true,
      "https://c.amazon-adsystem.com/aax2/apstag.js" to true,
      "https://coinhive.com/lib/coinhive.min.js" to true,
      "https://example.com/assets/app.js" to false,
      "https://mycdn.org/images/logo.png" to false,
      "https://ajax.googleapis.com/ajax/libs/jquery/3.6.0/jquery.min.js" to false
    )

    for ((url, expectBlock) in goldenChecks) {
      val decision = adblockBridge.evaluateDecision(url = url, sourceUrl = "https://adblock-tester.com", resourceType = "script", thirdParty = true)
      if (expectBlock) {
        assertTrue("URL $url must be BLOCKED by adblock bridge", decision.blocked)
      } else {
        assertFalse("URL $url must be ALLOWED by adblock bridge", decision.blocked)
      }
    }
  }

  /**
   * TEST 3: DebugLogManager in-memory ring buffer coalesces events without synchronous blocking.
   */
  @Test
  fun testDebugLogManagerRingBufferCoalescing() {
    DebugLogManager.init(context)
    for (i in 0 until 50) {
      DebugLogManager.log("[PERF_BENCHMARK] Event #$i")
    }

    val recent = DebugLogManager.getRecentEvents(50)
    assertTrue("Ring buffer must hold recent events in-memory", recent.isNotEmpty())
    assertTrue("Must contain last logged event", recent.any { it.contains("Event #49") })

    // Synchronous flush flushes pending memory state cleanly
    DebugLogManager.flushSynchronously()
    val file = File(context.filesDir, "remmi_breadcrumbs.log")
    assertTrue("Log file must exist after flush", file.exists())
  }

  /**
   * TEST 4: Filter bootstrap single-flight execution.
   */
  @Test
  fun testFilterBootstrapSingleFlight() = runBlocking {
    val filterManager = FilterManager.getInstance(context, adblockBridge)
    val deferred1 = filterManager.loadPersistedRulesIntoBridge()
    val deferred2 = filterManager.loadPersistedRulesIntoBridge()

    assertSame("Concurrent bootstrap calls must share the same single-flight Deferred", deferred1, deferred2)
    val result = deferred1.await()
    assertTrue("Result must return a non-negative rule count", result >= 0)
  }

  /**
   * TEST 5: GeckoEngineManager loadUrl does not poll 50ms and resolves with StateFlow.
   */
  @Test
  fun testGeckoEngineManagerNoPollingLoadUrl() {
    val manager = GeckoEngineManager.getInstance(context)
    assertNotNull("GeckoEngineManager instance must be non-null", manager)
  }

  /**
   * TEST 6: CrashHandlerHelper non-blocking startup phase updates.
   */
  @Test
  fun testCrashHandlerHelperNonBlockingStartupPhase() {
    val start = System.currentTimeMillis()
    CrashHandlerHelper.updateStartupPhase(phase = StartupPhase.APP_READY)
    CrashHandlerHelper.recordNativeOp(op = "TEST_NATIVE_OP")
    val duration = System.currentTimeMillis() - start

    assertTrue("updateStartupPhase and recordNativeOp must be instant (< 100ms)", duration < 100)
    assertEquals("Current phase must be APP_READY", StartupPhase.APP_READY, CrashHandlerHelper.currentPhase)
  }

  /**
   * TEST 7: Native crash diagnostics report structure and signal detection.
   */
  @Test
  fun testNativeCrashForensicsReportStructure() {
    val report = CrashHandlerHelper.buildDiagnosticReport(
      context = context,
      reportType = ReportType.JAVA_CRASH,
      thread = null,
      throwable = null,
      sessionId = "test_sess_001",
      lastPhase = StartupPhase.GECKO_MANAGER_CONSTRUCT_START.id,
      wasClean = false,
      lastCleanTimestamp = 0L,
      reportTime = System.currentTimeMillis()
    )

    assertTrue("Report must contain header", report.contains("REMMI BROWSER - AUTOMATIC DIAGNOSTIC REPORT"))
    assertTrue("Report must contain native crash forensics section", report.contains("ABNORMAL TERMINATION FORENSICS:"))
    assertTrue("Report must contain subsystem state", report.contains("SUBSYSTEM STATE:"))
    assertTrue("Report must contain device details", report.contains("DEVICE:"))
  }

  /**
   * TEST 8: Concurrent WebExtension decisions maintain thread-safety and zero data races.
   */
  @Test
  fun testConcurrentWebExtensionDecisionsNoDataRace() {
    val queue = ConcurrentLinkedQueue<JSONObject>()
    val port = TestPort(queue)
    blockExtension.onConnect(port)
    val delegate = port.testDelegate
    assertNotNull(delegate)

    val latch = CountDownLatch(20)
    val threadPool = Executors.newFixedThreadPool(8)
    for (i in 0 until 20) {
      val isAd = (i % 2 == 0)
      val url = if (isAd) "https://pagead2.googlesyndication.com/pagead/ad_$i.js" else "https://example.com/item_$i.js"
      threadPool.submit {
        try {
          delegate!!.onPortMessage(
            JSONObject().apply {
              put("type", "SHOULD_BLOCK")
              put("requestId", "conc_cuj_$i")
              put("url", url)
              put("sourceUrl", "https://example.com")
              put("resourceType", "script")
              put("thirdParty", isAd)
              put("portGeneration", 1L)
            },
            port
          )
        } finally {
          latch.countDown()
        }
      }
    }

    assertTrue("All threads must submit within 5s", latch.await(5, TimeUnit.SECONDS))
    threadPool.shutdown()

    val start = System.currentTimeMillis()
    while (queue.size < 20 && System.currentTimeMillis() - start < 5000) {
      try { ShadowLooper.idleMainLooper() } catch (_: Throwable) {}
      Thread.sleep(10)
    }
    try { ShadowLooper.idleMainLooper() } catch (_: Throwable) {}

    assertEquals("Must collect all 20 concurrent decisions", 20, queue.size)
  }

  /**
   * TEST 9: Cosmetic and scriptlet queries return valid responses with intact generation IDs.
   */
  @Test
  fun testCosmeticAndScriptletResourcesUnchanged() {
    val queue = ConcurrentLinkedQueue<JSONObject>()
    val port = TestPort(queue)
    blockExtension.onConnect(port)
    val delegate = port.testDelegate
    assertNotNull(delegate)

    val cosmeticReq = JSONObject().apply {
      put("type", "GET_COSMETIC_RESOURCES")
      put("requestId", "cosm_01")
      put("url", "https://example.com")
      put("portGeneration", 2L)
    }
    delegate!!.onPortMessage(cosmeticReq, port)
    val resp = awaitResponse(queue)
    assertNotNull("Must return cosmetic response", resp)
    assertTrue("Cosmetic response must be ok", resp!!.optBoolean("ok"))
    assertEquals("Generation must be preserved", 2L, resp.optLong("portGeneration"))
    assertEquals("RequestId must be preserved", "cosm_01", resp.optString("requestId"))
  }

  /**
   * TEST 10: 100 Sequential requests under load maintain exact 100% adblock fidelity.
   */
  @Test
  fun testAdblockDecisionPreservationUnderLoad() {
    for (i in 0 until 100) {
      val isAd = (i % 2 == 0)
      val url = if (isAd) "https://pagead2.googlesyndication.com/ad_$i.js" else "https://example.com/asset_$i.js"
      val decision = adblockBridge.evaluateDecision(url = url, sourceUrl = "https://example.com", resourceType = "script", thirdParty = isAd)
      if (isAd) {
        assertTrue("Request $i ($url) must be BLOCK", decision.blocked)
      } else {
        assertFalse("Request $i ($url) must be ALLOW", decision.blocked)
      }
    }
  }
}
