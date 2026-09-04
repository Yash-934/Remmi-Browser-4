package com.remmi.adblock

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockExtensionTest {

  private lateinit var bridge: AdblockBridge
  private lateinit var blockExtension: BlockExtension
  private lateinit var mockSender: WebExtension.MessageSender

  @Suppress("UNCHECKED_CAST")
  private fun <T> allocateInstance(clazz: Class<T>): T {
    val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
    unsafeField.isAccessible = true
    val unsafe = unsafeField.get(null) as sun.misc.Unsafe
    return unsafe.allocateInstance(clazz) as T
  }

  @Before
  fun setUp() {
    bridge = AdblockBridge.getInstance()
    bridge.compileRules("||pagead2.googlesyndication.com^\n||google-analytics.com^\n||doubleclick.net^\n||googletagmanager.com^")
    blockExtension = BlockExtension.getInstance(bridge)
    blockExtension.siteSecurityProvider = null
    blockExtension.cosmeticPolicyProvider = null

    val mockExtension = allocateInstance(WebExtension::class.java)
    ReflectionHelpers.setField(mockExtension, "id", "remmi_engine_extension")
    mockSender = allocateInstance(WebExtension.MessageSender::class.java)
    ReflectionHelpers.setField(mockSender, "webExtension", mockExtension)
  }

  @Suppress("UNCHECKED_CAST")
  private fun extractResult(result: GeckoResult<Any>?): JSONObject? {
    if (result == null) return null
    return try {
      val res = result.poll(1000)
      when (res) {
        is JSONObject -> res
        is String -> JSONObject(res)
        else -> null
      }
    } catch (e: Exception) {
      null
    }
  }

  @Test
  fun test100SequentialPings() {
    val count = 100
    val latenciesNanos = LongArray(count)
    var successes = 0
    var failures = 0

    for (i in 0 until count) {
      val pingMsg = JSONObject().apply {
        put("type", "PING")
        put("requestId", "ping_test_$i")
      }
      val elapsed = measureNanoTime {
        val result = blockExtension.onMessage("remmi_engine_extension", pingMsg, mockSender)
        val json = extractResult(result)
        if (json != null && json.optBoolean("ok", false) && json.optBoolean("pong", false)) {
          successes++
        } else {
          failures++
        }
      }
      latenciesNanos[i] = elapsed
    }

    val latenciesMs = latenciesNanos.map { it / 1_000_000.0 }.sorted()
    val p50 = latenciesMs[(count * 0.50).toInt()]
    val p95 = latenciesMs[(count * 0.95).toInt()]
    val max = latenciesMs.last()

    println("[TEST_METRICS] 100 PING Results: successes=$successes, failures=$failures, p50=${"%.2f".format(p50)}ms, p95=${"%.2f".format(p95)}ms, max=${"%.2f".format(max)}ms")

    assertEquals("All 100 PING requests must succeed", 100, successes)
    assertEquals("There must be 0 PING failures", 0, failures)
  }

  @Test
  fun testShouldBlockGoogleTagManager() {
    val shouldBlockMsg = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("requestId", "req_gtag_test")
      put("url", "https://www.googletagmanager.com/gtag/js?id=G-EPK7X69JWC")
      put("sourceUrl", "https://adblock-tester.com/")
      put("initiator", "https://adblock-tester.com/")
      put("method", "GET")
      put("resourceType", "script")
      put("aggressive", false)
      put("thirdParty", true)
    }

    val result = blockExtension.onMessage("remmi_engine_extension", shouldBlockMsg, mockSender)
    val responseJson = extractResult(result)

    assertNotNull("Response must be JSON", responseJson)
    assertTrue("Response ok must be true", responseJson!!.optBoolean("ok", false))
    assertTrue("Google Tag Manager script must have cancel=true", responseJson.optBoolean("cancel", false))
  }

  @Test
  fun testShouldBlockGoogleAnalyticsCollect() {
    val shouldBlockMsg = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("requestId", "req_ga_test")
      put("url", "https://www.google-analytics.com/g/collect?v=2&tid=G-EPK7X69JWC&cid=555.666")
      put("sourceUrl", "https://adblock-tester.com/")
      put("initiator", "https://adblock-tester.com/")
      put("method", "POST")
      put("resourceType", "ping")
      put("aggressive", false)
      put("thirdParty", true)
    }

    val result = blockExtension.onMessage("remmi_engine_extension", shouldBlockMsg, mockSender)
    val responseJson = extractResult(result)

    assertNotNull("Response must be JSON", responseJson)
    assertTrue("Response ok must be true", responseJson!!.optBoolean("ok", false))
    assertTrue("Google Analytics collect ping must have cancel=true", responseJson.optBoolean("cancel", false))
  }

  @Test
  fun testShouldAllowFirstPartyCleanResource() {
    val shouldBlockMsg = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("requestId", "req_clean_test")
      put("url", "https://adblock-tester.com/assets/app.js")
      put("sourceUrl", "https://adblock-tester.com/")
      put("initiator", "https://adblock-tester.com/")
      put("method", "GET")
      put("resourceType", "script")
      put("aggressive", false)
      put("thirdParty", false)
    }

    val result = blockExtension.onMessage("remmi_engine_extension", shouldBlockMsg, mockSender)
    val responseJson = extractResult(result)

    assertNotNull("Response must be JSON", responseJson)
    assertTrue("Response ok must be true", responseJson!!.optBoolean("ok", false))
    assertFalse("First party script must have cancel=false", responseJson.optBoolean("cancel", true))
  }

  @Test
  fun testCosmeticResourcesMessagePath() {
    val cosmeticMsg = JSONObject().apply {
      put("type", "GET_COSMETIC_RESOURCES")
      put("requestId", "req_cosmetic_test")
      put("url", "https://adblock-tester.com/")
      put("hostname", "adblock-tester.com")
      put("classes", JSONArray(listOf("banner", "adsbygoogle")))
      put("ids", JSONArray(listOf("sponsor-frame", "header-ad")))
      put("exceptions", JSONArray())
    }

    val result = blockExtension.onMessage("remmi_engine_extension", cosmeticMsg, mockSender)
    val responseJson = extractResult(result)

    assertNotNull("Cosmetic response must be JSON", responseJson)
    assertTrue("Cosmetic response ok must be true", responseJson!!.optBoolean("ok", false))
    assertNotNull("hideSelectors must be present", responseJson.optJSONArray("hideSelectors"))
  }

  @Test
  fun testHiddenClassIdSelectorsMessagePath() {
    val classIdMsg = JSONObject().apply {
      put("type", "GET_HIDDEN_CLASS_ID_SELECTORS")
      put("requestId", "req_classid_test")
      put("classes", JSONArray(listOf("ad-banner", "sponsored-post")))
      put("ids", JSONArray(listOf("ad-unit-1", "sidebar-sponsor")))
      put("exceptions", JSONArray())
    }

    val result = blockExtension.onMessage("remmi_engine_extension", classIdMsg, mockSender)
    val responseJson = extractResult(result)

    assertNotNull("ClassId response must be JSON", responseJson)
    assertTrue("ClassId response ok must be true", responseJson!!.optBoolean("ok", false))
    assertNotNull("hideSelectors array must be present", responseJson.optJSONArray("hideSelectors"))
  }

  @Test
  fun testInvalidAndUnsupportedMessages() {
    // 1. Unsupported type
    val unsupportedMsg = JSONObject().apply {
      put("type", "UNKNOWN_ACTION")
      put("requestId", "req_unknown")
    }
    val unsuppResult = blockExtension.onMessage("remmi_engine_extension", unsupportedMsg, mockSender)
    val unsuppJson = extractResult(unsuppResult)
    assertNotNull(unsuppJson)
    assertFalse("Unsupported type ok must be false", unsuppJson!!.optBoolean("ok", true))
    assertEquals("unsupported_type", unsuppJson.optString("error"))

    // 2. Empty URL in SHOULD_BLOCK
    val emptyUrlMsg = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("url", "")
    }
    val emptyUrlResult = blockExtension.onMessage("remmi_engine_extension", emptyUrlMsg, mockSender)
    val emptyUrlJson = extractResult(emptyUrlResult)
    assertNotNull(emptyUrlJson)
    assertFalse("Empty URL ok must be false", emptyUrlJson!!.optBoolean("ok", true))
  }

  @Test
  fun testConcurrentNativeMessagingStress() {
    val count = 100
    val latch = CountDownLatch(count)
    val responseSuccess = java.util.concurrent.atomic.AtomicInteger(0)

    val threads = (0 until count).map { i ->
      Thread {
        try {
          val isBlockCandidate = i % 2 == 0
          val url = if (isBlockCandidate) {
            "https://www.googletagmanager.com/gtag/js?id=G-CONCURRENT_$i"
          } else {
            "https://example.com/asset_$i.js"
          }

          val msg = JSONObject().apply {
            put("type", "SHOULD_BLOCK")
            put("requestId", "req_concurrent_$i")
            put("url", url)
            put("sourceUrl", "https://adblock-tester.com/")
            put("resourceType", "script")
            put("thirdParty", isBlockCandidate)
          }

          val result = blockExtension.onMessage("remmi_engine_extension", msg, mockSender)
          val json = extractResult(result)
          if (json != null && json.optBoolean("ok", false)) {
            val cancel = json.optBoolean("cancel", false)
            if (isBlockCandidate == cancel) {
              responseSuccess.incrementAndGet()
            }
          }
        } finally {
          latch.countDown()
        }
      }
    }

    threads.forEach { it.start() }
    assertTrue("All threads must finish within 10s", latch.await(10, TimeUnit.SECONDS))
    assertEquals("All 100 concurrent requests must receive exact matching response", count, responseSuccess.get())
  }

  @Test
  fun testConcurrentLoadTiers() {
    val tiers = listOf(1, 5, 10, 25, 50, 100, 250)

    for (tier in tiers) {
      val latch = CountDownLatch(tier)
      val latenciesNanos = java.util.concurrent.ConcurrentLinkedQueue<Long>()
      val successCount = java.util.concurrent.atomic.AtomicInteger(0)

      val threads = (0 until tier).map { i ->
        Thread {
          try {
            val isBlock = i % 2 == 0
            val url = if (isBlock) "https://www.google-analytics.com/analytics.js?t=$i" else "https://example.com/style_$i.css"
            val msg = JSONObject().apply {
              put("type", "SHOULD_BLOCK")
              put("requestId", "tier_${tier}_req_$i")
              put("url", url)
              put("sourceUrl", "https://adblock-tester.com/")
              put("resourceType", if (isBlock) "script" else "stylesheet")
              put("thirdParty", isBlock)
            }
            val start = System.nanoTime()
            val result = blockExtension.onMessage("remmi_engine_extension", msg, mockSender)
            val json = extractResult(result)
            val elapsed = System.nanoTime() - start
            latenciesNanos.add(elapsed)
            if (json != null && json.optBoolean("ok", false)) {
              successCount.incrementAndGet()
            }
          } finally {
            latch.countDown()
          }
        }
      }

      val totalElapsedMs = measureNanoTime {
        threads.forEach { it.start() }
        assertTrue("Tier $tier must complete within 15s", latch.await(15, TimeUnit.SECONDS))
      } / 1_000_000.0

      val latenciesMs = latenciesNanos.map { it / 1_000_000.0 }.sorted()
      val p50 = latenciesMs[(latenciesMs.size * 0.50).toInt().coerceAtMost(latenciesMs.size - 1)]
      val p95 = latenciesMs[(latenciesMs.size * 0.95).toInt().coerceAtMost(latenciesMs.size - 1)]
      val p99 = latenciesMs[(latenciesMs.size * 0.99).toInt().coerceAtMost(latenciesMs.size - 1)]
      val max = latenciesMs.last()

      println("[LOAD_BENCHMARK] Tier=$tier concurrent | Success=${successCount.get()}/$tier | Total=${"%.2f".format(totalElapsedMs)}ms | p50=${"%.2f".format(p50)}ms | p95=${"%.2f".format(p95)}ms | p99=${"%.2f".format(p99)}ms | max=${"%.2f".format(max)}ms")
      assertEquals("All requests in tier $tier must succeed", tier, successCount.get())
    }
  }

  @Test
  fun testSequentialRequestsAndPing() {
    // Test 1: Single PING test
    val pingMsg = JSONObject().apply {
      put("type", "PING")
      put("requestId", "port_ping_0")
      put("portGeneration", 1L)
    }
    val pingResult = blockExtension.onMessage("remmi_engine_extension", pingMsg, mockSender)
    val pingJson = extractResult(pingResult)
    assertNotNull(pingJson)
    assertTrue("PING must return ok: true", pingJson!!.optBoolean("ok"))
    assertTrue("PING must return pong: true", pingJson.optBoolean("pong"))

    // Test 2: 1 Single SHOULD_BLOCK request
    val singleMsg = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("requestId", "single_req_1")
      put("url", "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js")
      put("sourceUrl", "https://adblock-tester.com/")
      put("resourceType", "script")
      put("thirdParty", true)
    }
    val singleRes = extractResult(blockExtension.onMessage("remmi_engine_extension", singleMsg, mockSender))
    assertNotNull(singleRes)
    assertTrue("Ad script must be cancelled", singleRes!!.optBoolean("cancel"))

    // Test 3: 5 Sequential SHOULD_BLOCK requests
    for (i in 1..5) {
      val isAd = i % 2 == 1
      val url = if (isAd) "https://www.google-analytics.com/collect?v=$i" else "https://example.com/app_$i.js"
      val req = JSONObject().apply {
        put("type", "SHOULD_BLOCK")
        put("requestId", "seq_5_$i")
        put("url", url)
        put("sourceUrl", "https://adblock-tester.com/")
        put("resourceType", "script")
        put("thirdParty", isAd)
      }
      val res = extractResult(blockExtension.onMessage("remmi_engine_extension", req, mockSender))
      assertNotNull(res)
      assertEquals("Cancel result for $url must match expectation", isAd, res!!.optBoolean("cancel"))
    }

    // Test 4: 10 Sequential SHOULD_BLOCK requests
    for (i in 1..10) {
      val isAd = i <= 5
      val url = if (isAd) "https://stats.g.doubleclick.net/r/collect?i=$i" else "https://cdn.example.com/lib_$i.js"
      val req = JSONObject().apply {
        put("type", "SHOULD_BLOCK")
        put("requestId", "seq_10_$i")
        put("url", url)
        put("sourceUrl", "https://adblock-tester.com/")
        put("resourceType", "script")
        put("thirdParty", isAd)
      }
      val res = extractResult(blockExtension.onMessage("remmi_engine_extension", req, mockSender))
      assertNotNull(res)
      assertEquals("Cancel result for $url in 10-seq must match expectation", isAd, res!!.optBoolean("cancel"))
    }
  }

  @Test
  fun testPortGenerationCorrelationRegression() {
    val receivedMessages = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()

    // Custom test port implementation
    class TestWebExtensionPort : WebExtension.Port() {
      var internalDelegate: WebExtension.PortDelegate? = null
      override fun setDelegate(d: WebExtension.PortDelegate?) {
        this.internalDelegate = d
      }
      override fun postMessage(msgObj: JSONObject) {
        receivedMessages.add(msgObj)
      }
    }

    // 1. First connection
    val mockPort1 = TestWebExtensionPort()
    blockExtension.onConnect(mockPort1)
    val portDelegate1 = mockPort1.internalDelegate
    assertNotNull("Port delegate 1 must be registered", portDelegate1)

    fun sendAndCapture1(msg: JSONObject): JSONObject {
      receivedMessages.clear()
      portDelegate1!!.onPortMessage(msg, mockPort1)
      val start = System.currentTimeMillis()
      while (receivedMessages.isEmpty() && System.currentTimeMillis() - start < 3000) {
        try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Throwable) {}
        Thread.sleep(10)
      }
      try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Throwable) {}
      val resp = receivedMessages.poll()
      assertNotNull("Must return exactly one response", resp)
      return resp!!
    }

    // 1. First connection: JS sends PORT_STATUS with generation=1
    val statusMsg1 = JSONObject().apply {
      put("type", "PORT_STATUS")
      put("status", "CONNECTED")
      put("instanceId", 1)
      put("portGeneration", 1L)
      put("generation", 1L)
    }
    portDelegate1!!.onPortMessage(statusMsg1, mockPort1)

    // 2. PING with gen=1 -> Kotlin response gen=1
    val pingMsg1 = JSONObject().apply {
      put("type", "PING")
      put("requestId", "ping_req_gen1")
      put("portGeneration", 1L)
    }
    val pingResp1 = sendAndCapture1(pingMsg1)
    assertTrue("PING must succeed", pingResp1.optBoolean("ok"))
    assertTrue("PING must pong", pingResp1.optBoolean("pong"))
    assertEquals("PING response portGeneration must be 1", 1L, pingResp1.optLong("portGeneration"))
    assertEquals("PING response requestId must match", "ping_req_gen1", pingResp1.optString("requestId"))

    // 3. SHOULD_BLOCK with gen=1 -> Kotlin response gen=1
    val blockMsg1 = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("requestId", "block_req_gen1")
      put("url", "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js")
      put("sourceUrl", "https://adblock-tester.com/")
      put("resourceType", "script")
      put("thirdParty", true)
      put("portGeneration", 1L)
    }
    val blockResp1 = sendAndCapture1(blockMsg1)
    assertTrue("SHOULD_BLOCK response ok must be true", blockResp1.optBoolean("ok"))
    assertTrue("SHOULD_BLOCK must block ad script", blockResp1.optBoolean("cancel"))
    assertEquals("SHOULD_BLOCK response portGeneration must be 1", 1L, blockResp1.optLong("portGeneration"))
    assertEquals("SHOULD_BLOCK response requestId must match", "block_req_gen1", blockResp1.optString("requestId"))

    // 4. Cosmetic message with gen=1 -> Kotlin response gen=1
    val cosmeticMsg1 = JSONObject().apply {
      put("type", "GET_COSMETIC_RESOURCES")
      put("requestId", "cosmetic_req_gen1")
      put("url", "https://example.com")
      put("portGeneration", 1L)
    }
    val cosmeticResp1 = sendAndCapture1(cosmeticMsg1)
    assertTrue("COSMETIC response ok must be true", cosmeticResp1.optBoolean("ok"))
    assertEquals("COSMETIC response portGeneration must be 1", 1L, cosmeticResp1.optLong("portGeneration"))
    assertEquals("COSMETIC response requestId must match", "cosmetic_req_gen1", cosmeticResp1.optString("requestId"))

    // 5. Reconnection: New port with gen=2
    val mockPort2 = TestWebExtensionPort()
    blockExtension.onConnect(mockPort2)
    val portDelegate2 = mockPort2.internalDelegate
    assertNotNull("New port delegate must be registered", portDelegate2)

    val statusMsg2 = JSONObject().apply {
      put("type", "PORT_STATUS")
      put("status", "CONNECTED")
      put("instanceId", 2)
      put("portGeneration", 2L)
      put("generation", 2L)
    }
    portDelegate2!!.onPortMessage(statusMsg2, mockPort2)

    fun sendAndCapture2(msg: JSONObject): JSONObject {
      receivedMessages.clear()
      portDelegate2.onPortMessage(msg, mockPort2)
      val start = System.currentTimeMillis()
      while (receivedMessages.isEmpty() && System.currentTimeMillis() - start < 3000) {
        try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Throwable) {}
        Thread.sleep(10)
      }
      try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Throwable) {}
      val resp = receivedMessages.poll()
      assertNotNull("Must return exactly one response", resp)
      return resp!!
    }

    // 6. PING with gen=2 -> Kotlin response gen=2
    val pingMsg2 = JSONObject().apply {
      put("type", "PING")
      put("requestId", "ping_req_gen2")
      put("portGeneration", 2L)
    }
    val pingResp2 = sendAndCapture2(pingMsg2)
    assertTrue("PING gen=2 must succeed", pingResp2.optBoolean("ok"))
    assertEquals("PING gen=2 response portGeneration must be 2", 2L, pingResp2.optLong("portGeneration"))
    assertEquals("PING gen=2 requestId must match", "ping_req_gen2", pingResp2.optString("requestId"))

    // 7. SHOULD_BLOCK with gen=2 -> Kotlin response gen=2
    val blockMsg2 = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("requestId", "block_req_gen2")
      put("url", "https://google-analytics.com/analytics.js")
      put("sourceUrl", "https://adblock-tester.com/")
      put("resourceType", "script")
      put("thirdParty", true)
      put("portGeneration", 2L)
    }
    val blockResp2 = sendAndCapture2(blockMsg2)
    assertTrue("SHOULD_BLOCK gen=2 response ok must be true", blockResp2.optBoolean("ok"))
    assertTrue("SHOULD_BLOCK gen=2 must block tracker", blockResp2.optBoolean("cancel"))
    assertEquals("SHOULD_BLOCK gen=2 response portGeneration must be 2", 2L, blockResp2.optLong("portGeneration"))

    // 8. JS Correlation Discard Simulation:
    // Old response carrying portGeneration=1 received while JS is at generation=2 is safely discarded
    val pendingJsGen = 2L
    val isOldAccepted = (pingResp1.optLong("portGeneration") == pendingJsGen)
    assertFalse("Old response with gen=1 must NOT match JS pending request expecting gen=2", isOldAccepted)
    val isNewAccepted = (pingResp2.optLong("portGeneration") == pendingJsGen)
    assertTrue("New response with gen=2 MUST match JS pending request expecting gen=2", isNewAccepted)
  }

  @Test
  fun testZeroDiagnosticTrafficOnBlockingChannel() {
    val receivedMessages = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()

    class TestWebExtensionPort : WebExtension.Port() {
      var internalDelegate: WebExtension.PortDelegate? = null
      override fun setDelegate(d: WebExtension.PortDelegate?) {
        this.internalDelegate = d
      }
      override fun postMessage(msgObj: JSONObject) {
        receivedMessages.add(msgObj)
      }
    }

    val mockPort = TestWebExtensionPort()
    blockExtension.onConnect(mockPort)
    val portDelegate = mockPort.internalDelegate
    assertNotNull("Port delegate must be registered", portDelegate)

    // Send initial status
    portDelegate!!.onPortMessage(
      JSONObject().apply {
        put("type", "PORT_STATUS")
        put("status", "CONNECTED")
        put("portGeneration", 1L)
      },
      mockPort
    )

    // Execute 100 SHOULD_BLOCK requests
    val count = 100
    for (i in 0 until count) {
      val isAd = (i % 2 == 0)
      val url = if (isAd) "https://doubleclick.net/ad_$i.js" else "https://example.com/script_$i.js"
      val req = JSONObject().apply {
        put("type", "SHOULD_BLOCK")
        put("requestId", "req_blocking_hotpath_$i")
        put("url", url)
        put("sourceUrl", "https://example.com")
        put("resourceType", "script")
        put("thirdParty", isAd)
        put("portGeneration", 1L)
      }
      portDelegate.onPortMessage(req, mockPort)
    }

    val startTraffic = System.currentTimeMillis()
    while (receivedMessages.size < count && System.currentTimeMillis() - startTraffic < 5000) {
      try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Throwable) {}
      Thread.sleep(10)
    }
    try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Throwable) {}

    // Verify exactly 100 responses, ALL must be SHOULD_BLOCK_RESULT, ZERO LOG messages
    assertEquals("Must receive exactly 100 responses for 100 requests", count, receivedMessages.size)
    val logMessages = receivedMessages.filter { it.optString("type") == "LOG" || it.optString("type") == "log" }
    assertEquals("There must be ZERO LOG messages on the blocking port", 0, logMessages.size)

    val resultTypes = receivedMessages.map { it.optString("type") }.toSet()
    assertEquals("All responses must be SHOULD_BLOCK_RESULT", setOf("SHOULD_BLOCK_RESULT"), resultTypes)
  }

  @Test
  fun testFailureHandlingNoDiagnosticIpcAmplification() {
    val receivedMessages = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()

    class TestWebExtensionPort : WebExtension.Port() {
      var internalDelegate: WebExtension.PortDelegate? = null
      override fun setDelegate(d: WebExtension.PortDelegate?) {
        this.internalDelegate = d
      }
      override fun postMessage(msgObj: JSONObject) {
        receivedMessages.add(msgObj)
      }
    }

    val mockPort = TestWebExtensionPort()
    blockExtension.onConnect(mockPort)
    val portDelegate = mockPort.internalDelegate
    assertNotNull("Port delegate must be registered", portDelegate)

    // Execute 100 requests with empty or malformed parameters (failure cases)
    val count = 100
    for (i in 0 until count) {
      val req = JSONObject().apply {
        put("type", "SHOULD_BLOCK")
        put("requestId", "req_fail_case_$i")
        put("url", "") // empty URL failure case
        put("portGeneration", 1L)
      }
      portDelegate!!.onPortMessage(req, mockPort)
    }

    val startFailure = System.currentTimeMillis()
    while (receivedMessages.size < count && System.currentTimeMillis() - startFailure < 5000) {
      try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Throwable) {}
      Thread.sleep(10)
    }
    try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Throwable) {}

    // Verify exactly 100 responses and ZERO LOG message amplification
    assertEquals("Must receive exactly 100 responses for 100 failure requests", count, receivedMessages.size)
    val logMessages = receivedMessages.filter { it.optString("type") == "LOG" || it.optString("type") == "log" }
    assertEquals("There must be ZERO diagnostic LOG messages produced on error", 0, logMessages.size)
  }

  @Test
  fun testBackgroundJsContainsNoHotPathLogIpc() {
    val paths = listOf(
      "src/main/assets/extensions/remmi_engine_extension/background.js",
      "app/src/main/assets/extensions/remmi_engine_extension/background.js"
    )
    val bgFile = paths.map { java.io.File(it) }.firstOrNull { it.exists() }
    assertNotNull("background.js asset file must exist", bgFile)
    val content = bgFile!!.readText()
    // Verify logToNative does not postMessage({type:"LOG"})
    assertFalse("background.js must not postMessage type LOG", content.contains("""port.postMessage({ type: "LOG""""))
    assertFalse("background.js must not postMessage type 'LOG'", content.contains("""port.postMessage({type:"LOG""""))
    assertFalse("background.js must not send WEBEXT_METRICS on port every 50 requests", content.contains("""type: "WEBEXT_METRICS""""))
  }

  @Test
  fun testStrictPingFirstAndShouldBlockProof() {
    val receivedMessages = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()

    class TestWebExtensionPort : WebExtension.Port() {
      var internalDelegate: WebExtension.PortDelegate? = null
      override fun setDelegate(d: WebExtension.PortDelegate?) {
        this.internalDelegate = d
      }
      override fun postMessage(msgObj: JSONObject) {
        receivedMessages.add(msgObj)
      }
    }

    val mockPort = TestWebExtensionPort()
    blockExtension.onConnect(mockPort)
    val portDelegate = mockPort.internalDelegate
    assertNotNull("Port delegate must be registered", portDelegate)

    // Helper for sending and capturing
    fun sendAndCapture(msg: JSONObject): JSONObject {
      val targetReqId = msg.optString("requestId")
      portDelegate!!.onPortMessage(msg, mockPort)
      val start = System.currentTimeMillis()
      while (System.currentTimeMillis() - start < 5000) {
        try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Throwable) {}
        val found = receivedMessages.firstOrNull { it.optString("requestId") == targetReqId }
        if (found != null) {
          receivedMessages.remove(found)
          return found
        }
        Thread.sleep(10)
      }
      try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Throwable) {}
      val found = receivedMessages.firstOrNull { it.optString("requestId") == targetReqId }
      assertNotNull("Must return response for $targetReqId", found)
      return found!!
    }

    // 1. Initial Handshake: PORT_STATUS
    val statusMsg = JSONObject().apply {
      put("type", "PORT_STATUS")
      put("status", "CONNECTED")
      put("jsInstanceId", 1)
      put("portGeneration", 1L)
      put("generation", 1L)
    }
    portDelegate!!.onPortMessage(statusMsg, mockPort)

    // 2. Strict PING Handshake (Must be FIRST before any SHOULD_BLOCK)
    val pingMsg = JSONObject().apply {
      put("type", "PING")
      put("requestId", "ping_proof_01")
      put("portGeneration", 1L)
      put("jsInstanceId", 1)
    }
    val pingResp = sendAndCapture(pingMsg)
    assertTrue("PING must succeed", pingResp.optBoolean("ok"))
    assertTrue("PING must return pong=true", pingResp.optBoolean("pong"))
    assertEquals("PING portGeneration must match", 1L, pingResp.optLong("portGeneration"))
    assertEquals("PING requestId must match", "ping_proof_01", pingResp.optString("requestId"))

    // 3. Single SHOULD_BLOCK proof
    val singleBlockMsg = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("requestId", "block_proof_single")
      put("url", "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js")
      put("sourceUrl", "https://example.com")
      put("resourceType", "script")
      put("thirdParty", true)
      put("portGeneration", 1L)
      put("jsInstanceId", 1)
    }
    val singleBlockResp = sendAndCapture(singleBlockMsg)
    assertTrue("Single SHOULD_BLOCK ok must be true", singleBlockResp.optBoolean("ok"))
    assertTrue("Single SHOULD_BLOCK must cancel ad", singleBlockResp.optBoolean("cancel"))
    assertEquals("Single SHOULD_BLOCK generation must match", 1L, singleBlockResp.optLong("portGeneration"))

    // 4. 10 Sequential requests proof
    for (i in 0 until 10) {
      val isAd = (i % 2 == 0)
      val url = if (isAd) "https://doubleclick.net/ad_$i.js" else "https://example.com/item_$i.js"
      val req = JSONObject().apply {
        put("type", "SHOULD_BLOCK")
        put("requestId", "seq10_req_$i")
        put("url", url)
        put("sourceUrl", "https://example.com")
        put("resourceType", "script")
        put("thirdParty", isAd)
        put("portGeneration", 1L)
      }
      val resp = sendAndCapture(req)
      assertTrue("Seq10 request $i must succeed", resp.optBoolean("ok"))
      assertEquals("Seq10 request $i cancel must match", isAd, resp.optBoolean("cancel"))
      assertEquals("Seq10 portGeneration must match", 1L, resp.optLong("portGeneration"))
    }

    // 5. 100 Sequential requests proof
    for (i in 0 until 100) {
      val isAd = (i % 2 == 0)
      val url = if (isAd) "https://doubleclick.net/ad_$i.js" else "https://example.com/item_$i.js"
      val req = JSONObject().apply {
        put("type", "SHOULD_BLOCK")
        put("requestId", "seq100_req_$i")
        put("url", url)
        put("sourceUrl", "https://example.com")
        put("resourceType", "script")
        put("thirdParty", isAd)
        put("portGeneration", 1L)
      }
      val resp = sendAndCapture(req)
      assertTrue("Seq100 request $i must succeed", resp.optBoolean("ok"))
      assertEquals("Seq100 request $i cancel must match", isAd, resp.optBoolean("cancel"))
    }

    // 6. 100 Concurrent requests proof
    receivedMessages.clear()
    val executor = java.util.concurrent.Executors.newFixedThreadPool(16)
    val futures = (0 until 100).map { i ->
      executor.submit<Unit> {
        val isAd = (i % 2 == 0)
        val url = if (isAd) "https://doubleclick.net/ad_conc_$i.js" else "https://example.com/conc_$i.js"
        val req = JSONObject().apply {
          put("type", "SHOULD_BLOCK")
          put("requestId", "conc100_req_$i")
          put("url", url)
          put("sourceUrl", "https://example.com")
          put("resourceType", "script")
          put("thirdParty", isAd)
          put("portGeneration", 1L)
        }
        portDelegate.onPortMessage(req, mockPort)
      }
    }
    futures.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
    executor.shutdown()

    val startConc = System.currentTimeMillis()
    while (receivedMessages.size < 100 && System.currentTimeMillis() - startConc < 5000) {
      try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Throwable) {}
      Thread.sleep(10)
    }
    try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Throwable) {}

    assertEquals("Must receive all 100 concurrent responses", 100, receivedMessages.size)
    val concurrentLogCount = receivedMessages.filter { it.optString("type") == "LOG" }.size
    assertEquals("Concurrent test must generate zero LOG messages", 0, concurrentLogCount)
  }
}
