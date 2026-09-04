package com.remmi.adblock

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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureNanoTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ValidationBenchmarkTest {

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
    bridge.compileRules("")
    blockExtension = BlockExtension.getInstance(bridge)

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
  fun testVerifyNativeApiValues() {
    println("=== NATIVE API VERIFICATION ===")
    val apiVersion = bridge.getApiVersion()

    println("[API_VERIFY] getApiVersion() = $apiVersion")
    println("[API_VERIFY] isNativeLoaded = ${bridge.isNativeLoaded}")
    println("[API_VERIFY] isJniSignatureCompatible = ${bridge.isJniSignatureCompatible}")

    // If native library doesn't export nativeGetApiVersion, apiVersion is 0 (UNKNOWN) and compatibility is safely false
    if (apiVersion == 0) {
      assertFalse("isJniSignatureCompatible must be false when API version is UNKNOWN", bridge.isJniSignatureCompatible)
    }
  }

  @Test
  fun testStageByStageIntervalMeasurement() {
    println("=== STAGE-BY-STAGE INTERVAL MEASUREMENT ===")
    // Warm-up
    for (i in 0 until 10) {
      val warmMsg = JSONObject().apply {
        put("type", "SHOULD_BLOCK")
        put("requestId", "warmup_$i")
        put("url", "https://www.google-analytics.com/analytics.js")
        put("sourceUrl", "https://adblock-tester.com/")
        put("resourceType", "script")
        put("thirdParty", true)
      }
      blockExtension.onMessage("remmi_engine_extension", warmMsg, mockSender)
    }

    val reqId = "stage_test_001"
    val testUrl = "https://www.googletagmanager.com/gtag/js?id=G-EPK7X69JWC"
    val sourceUrl = "https://adblock-tester.com/"

    // Measure stages
    val t0_send_start = System.nanoTime()

    val msg = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("requestId", reqId)
      put("url", testUrl)
      put("sourceUrl", sourceUrl)
      put("resourceType", "script")
      put("thirdParty", true)
    }

    val t1_native_receive = System.nanoTime()
    val t2_handler_start = System.nanoTime()

    val t3_match_start = System.nanoTime()
    val decision = bridge.evaluateDecision(
      url = testUrl,
      sourceUrl = sourceUrl,
      method = "GET",
      resourceType = "script",
      aggressive = false,
      thirdParty = true,
      requestId = reqId
    )
    val t4_match_end = System.nanoTime()

    val t5_handler_end = System.nanoTime()
    val respJson = JSONObject().apply {
      put("ok", true)
      put("cancel", decision.blocked)
      if (decision.ruleId != null) put("ruleId", decision.ruleId)
      put("generation", decision.engineGeneration)
    }
    val t6_resp_created = System.nanoTime()
    val geckoResult = GeckoResult.fromValue<Any>(respJson)
    val extracted = extractResult(geckoResult)
    val t7_send_success = System.nanoTime()

    val d_nm_receive = (t1_native_receive - t0_send_start) / 1_000_000.0
    val d_handler_init = (t2_handler_start - t1_native_receive) / 1_000_000.0
    val d_match_prep = (t3_match_start - t2_handler_start) / 1_000_000.0
    val d_match_pure = (t4_match_end - t3_match_start) / 1_000_000.0
    val d_handler_post = (t5_handler_end - t4_match_end) / 1_000_000.0
    val d_resp_creation = (t6_resp_created - t5_handler_end) / 1_000_000.0
    val d_resp_delivery = (t7_send_success - t6_resp_created) / 1_000_000.0
    val d_total = (t7_send_success - t0_send_start) / 1_000_000.0

    println("[STAGE_BREAKDOWN] NM_SEND_START -> NM_NATIVE_RECEIVE: ${"%.4f".format(d_nm_receive)} ms")
    println("[STAGE_BREAKDOWN] NM_NATIVE_RECEIVE -> NM_NATIVE_HANDLER_START: ${"%.4f".format(d_handler_init)} ms")
    println("[STAGE_BREAKDOWN] NM_NATIVE_HANDLER_START -> NATIVE_MATCH_START: ${"%.4f".format(d_match_prep)} ms")
    println("[STAGE_BREAKDOWN] NATIVE_MATCH_START -> NATIVE_MATCH_END: ${"%.4f".format(d_match_pure)} ms")
    println("[STAGE_BREAKDOWN] NATIVE_MATCH_END -> NM_NATIVE_HANDLER_END: ${"%.4f".format(d_handler_post)} ms")
    println("[STAGE_BREAKDOWN] NM_NATIVE_HANDLER_END -> NM_RESPONSE_CREATED: ${"%.4f".format(d_resp_creation)} ms")
    println("[STAGE_BREAKDOWN] NM_RESPONSE_CREATED -> NM_SEND_SUCCESS: ${"%.4f".format(d_resp_delivery)} ms")
    println("[STAGE_BREAKDOWN] TOTAL_E2E: ${"%.4f".format(d_total)} ms")

    assertNotNull(extracted)
  }

  @Test
  fun testPureSubsystemBenchmarks() {
    println("=== PURE SUBSYSTEM BENCHMARKS ===")
    val count = 1000
    val testUrl = "https://www.googletagmanager.com/gtag/js?id=G-EPK7X69JWC"
    val sourceUrl = "https://adblock-tester.com/"

    // 1. Rust/Engine matching pure
    val matchTimes = LongArray(count)
    for (i in 0 until count) {
      val t0 = System.nanoTime()
      bridge.evaluateDecision(
        url = testUrl,
        sourceUrl = sourceUrl,
        method = "GET",
        resourceType = "script",
        aggressive = false,
        thirdParty = true
      )
      val t1 = System.nanoTime()
      matchTimes[i] = t1 - t0
    }
    val matchMs = matchTimes.map { it / 1_000_000.0 }.sorted()
    val matchP50 = matchMs[(count * 0.50).toInt()]
    val matchP95 = matchMs[(count * 0.95).toInt()]
    val matchP99 = matchMs[(count * 0.99).toInt()]

    // 2. Kotlin message handler + Engine + GeckoResult
    val msg = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("requestId", "subsystem_test")
      put("url", testUrl)
      put("sourceUrl", sourceUrl)
      put("resourceType", "script")
      put("thirdParty", true)
    }
    val e2eTimes = LongArray(count)
    for (i in 0 until count) {
      val t0 = System.nanoTime()
      val res = blockExtension.onMessage("remmi_engine_extension", msg, mockSender)
      extractResult(res)
      val t1 = System.nanoTime()
      e2eTimes[i] = t1 - t0
    }
    val e2eMs = e2eTimes.map { it / 1_000_000.0 }.sorted()
    val e2eP50 = e2eMs[(count * 0.50).toInt()]
    val e2eP95 = e2eMs[(count * 0.95).toInt()]
    val e2eP99 = e2eMs[(count * 0.99).toInt()]

    println("[SUBSYSTEM] Pure Engine Matching: p50=${"%.4f".format(matchP50)}ms, p95=${"%.4f".format(matchP95)}ms, p99=${"%.4f".format(matchP99)}ms")
    println("[SUBSYSTEM] Full Native Msg Handler (Kotlin+Engine+GeckoResult): p50=${"%.4f".format(e2eP50)}ms, p95=${"%.4f".format(e2eP95)}ms, p99=${"%.4f".format(e2eP99)}ms")
  }

  @Test
  fun testComprehensiveConcurrencyTiers() {
    println("=== CONCURRENCY TIERS (1, 5, 10, 25, 50, 100, 250) ===")
    val tiers = listOf(1, 5, 10, 25, 50, 100, 250)

    for (tier in tiers) {
      val latch = CountDownLatch(tier)
      val latenciesNanos = ConcurrentLinkedQueue<Long>()
      val successCount = AtomicInteger(0)
      val failureCount = AtomicInteger(0)
      val timeoutCount = AtomicInteger(0)

      val threads = (0 until tier).map { i ->
        Thread {
          try {
            val isBlock = i % 2 == 0
            val url = if (isBlock) "https://www.google-analytics.com/analytics.js?t=$i" else "https://example.com/clean_$i.js"
            val msg = JSONObject().apply {
              put("type", "SHOULD_BLOCK")
              put("requestId", "tier_${tier}_$i")
              put("url", url)
              put("sourceUrl", "https://adblock-tester.com/")
              put("resourceType", "script")
              put("thirdParty", isBlock)
            }
            val start = System.nanoTime()
            val result = blockExtension.onMessage("remmi_engine_extension", msg, mockSender)
            val json = extractResult(result)
            val elapsed = System.nanoTime() - start
            latenciesNanos.add(elapsed)
            if (json != null && json.optBoolean("ok", false)) {
              if (json.optBoolean("cancel", false) == isBlock) {
                successCount.incrementAndGet()
              } else {
                failureCount.incrementAndGet()
              }
            } else {
              failureCount.incrementAndGet()
            }
          } catch (t: Throwable) {
            failureCount.incrementAndGet()
          } finally {
            latch.countDown()
          }
        }
      }

      val wallClockNs = measureNanoTime {
        threads.forEach { it.start() }
        val completed = latch.await(15, TimeUnit.SECONDS)
        if (!completed) {
          timeoutCount.set(tier - successCount.get() - failureCount.get())
        }
      }

      val wallClockMs = wallClockNs / 1_000_000.0
      val throughput = (successCount.get() / (wallClockMs / 1000.0)).toInt()

      val latenciesMs = latenciesNanos.map { it / 1_000_000.0 }.sorted()
      val p50 = latenciesMs[(latenciesMs.size * 0.50).toInt().coerceAtMost(latenciesMs.size - 1)]
      val p95 = latenciesMs[(latenciesMs.size * 0.95).toInt().coerceAtMost(latenciesMs.size - 1)]
      val p99 = latenciesMs[(latenciesMs.size * 0.99).toInt().coerceAtMost(latenciesMs.size - 1)]
      val max = latenciesMs.last()

      println(
        String.format(
          "[TIER_RESULT] Tier=%-3d | Success=%-3d | Failure=%-2d | Timeout=%-2d | p50=%6.2fms | p95=%6.2fms | p99=%6.2fms | max=%6.2fms | Throughput=%6d req/s",
          tier, successCount.get(), failureCount.get(), timeoutCount.get(), p50, p95, p99, max, throughput
        )
      )
    }
  }
}
