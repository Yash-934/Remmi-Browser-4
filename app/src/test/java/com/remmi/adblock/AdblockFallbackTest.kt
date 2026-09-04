package com.remmi.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdblockFallbackTest {

  @Test
  fun testHealthyNativeSkipsFallback() {
    val bridge = AdblockBridge.getInstance()
    // Simulate healthy native engine
    ReflectionHelpers.setField(bridge, "isNativeLoaded", true)

    val rules = """
      ||test-healthy-skip.com^
      ||google-analytics.com^
    """.trimIndent()

    val compiledCount = bridge.compileRules(rules)
    val fallbackEngine = ReflectionHelpers.getField<FallbackEngineSet>(bridge, "activeFallbackEngine")
    
    // Default patterns + domains are always added, but the specific 'rules' string is skipped
    // because parseToFallback should return early when isNativeLoaded = true.
    // Wait, our implementation of skipping just avoids calling `parseToFallback` altogether.
    var foundTestDomain = false
    for (netRule in fallbackEngine.fallbackNetworkRules) {
      if (netRule.raw == "||test-healthy-skip.com^") {
        foundTestDomain = true
      }
    }
    
    // In healthy mode, the fallback should NOT contain test-healthy-skip.com from the custom rules
    assertTrue("Fallback must skip parsing when native is loaded", !foundTestDomain)
  }

  @Test
  fun testDegradedNativeBuildsFallback() {
    val bridge = AdblockBridge.getInstance()
    // Simulate degraded/unavailable native engine
    ReflectionHelpers.setField(bridge, "isNativeLoaded", false)

    val rules = """
      ||test-degraded-build.com^
      ||google-analytics.com^
    """.trimIndent()

    val compiledCount = bridge.compileRules(rules)
    val fallbackEngine = ReflectionHelpers.getField<FallbackEngineSet>(bridge, "activeFallbackEngine")
    
    var foundTestDomain = false
    for (netRule in fallbackEngine.fallbackNetworkRules) {
      if (netRule.raw == "||test-degraded-build.com^") {
        foundTestDomain = true
      }
    }
    
    // In degraded mode, the fallback MUST contain test-degraded-build.com from the custom rules
    assertTrue("Fallback must be built when native is unavailable", foundTestDomain)
  }

  @Test
  fun testFallbackRuleOrderingIsIdentical() {
    val bridge = AdblockBridge.getInstance()
    ReflectionHelpers.setField(bridge, "isNativeLoaded", false)
    
    val rules = """
      ||rule1.com^
      ||rule2.com^
      ||rule3.com^
    """.trimIndent()
    
    bridge.compileRules(rules)
    val fallbackEngine = ReflectionHelpers.getField<FallbackEngineSet>(bridge, "activeFallbackEngine")
    
    // Check ordering in fallbackNetworkRules
    val parsedRules = fallbackEngine.fallbackNetworkRules.map { it.raw }
    
    val idx1 = parsedRules.indexOf("||rule1.com^")
    val idx2 = parsedRules.indexOf("||rule2.com^")
    val idx3 = parsedRules.indexOf("||rule3.com^")
    
    // Original add(0) prepended each sequentially, meaning rule3 ends up before rule2, which is before rule1
    assertTrue("Rule3 must be before Rule2", idx3 < idx2)
    assertTrue("Rule2 must be before Rule1", idx2 < idx1)
  }
}
