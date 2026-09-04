package com.remmi.browser.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remmi.adblock.AdblockBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineSeparationTest {

    @Test
    fun test1_defaultEngineNetworkBlock() {
        val bridge = AdblockBridge.getInstance()
        bridge.compileRules("||default-block.com^", "")
        
        val res = bridge.evaluateDecision("https://default-block.com/ad.js")
        assertTrue("Default engine must block", res.blocked)
    }

    @Test
    fun test2_additionalEngineUnbreak() {
        val bridge = AdblockBridge.getInstance()
        bridge.compileRules("||unbreak-me.com^", "@@||unbreak-me.com/safe^")
        
        val blockedRes = bridge.evaluateDecision("https://unbreak-me.com/ad.js")
        assertTrue("Should be blocked by default", blockedRes.blocked)
        
        val allowedRes = bridge.evaluateDecision("https://unbreak-me.com/safe.js")
        assertFalse("Should be allowed by additional unbreak", allowedRes.blocked)
    }

    @Test
    fun test3_networkResultMerging() {
        val bridge = AdblockBridge.getInstance()
        bridge.compileRules("||domain-a.com^", "||domain-b.com^")
        
        assertTrue(bridge.evaluateDecision("https://domain-a.com/").blocked)
        assertTrue(bridge.evaluateDecision("https://domain-b.com/").blocked)
    }

    @Test
    fun test4_defaultCosmeticHideSelector() {
        val bridge = AdblockBridge.getInstance()
        bridge.compileRules("##.default-hide", "")
        
        val res = bridge.getCosmeticResources("https://example.com/")
        assertTrue(res.hideSelectors.contains(".default-hide"))
        assertFalse(res.forceHideSelectors.contains(".default-hide"))
    }

    @Test
    fun test5_additionalForceHideSelector() {
        val bridge = AdblockBridge.getInstance()
        bridge.compileRules("", "##.additional-force-hide")
        
        val res = bridge.getCosmeticResources("https://example.com/")
        assertTrue(res.forceHideSelectors.contains(".additional-force-hide"))
    }

    @Test
    fun test6_cosmeticExceptions() {
        val bridge = AdblockBridge.getInstance()
        bridge.compileRules("##.global-ad\nexample.com#@#.global-ad", "")
        
        val res1 = bridge.getCosmeticResources("https://example.com/")
        assertFalse("Exception should prevent hiding", res1.hideSelectors.contains(".global-ad"))
        
        val res2 = bridge.getCosmeticResources("https://other.com/")
        assertTrue("Without exception, should hide", res2.hideSelectors.contains(".global-ad"))
    }
    
    @Test
    fun test7_shieldStandardVsAggressive() {
        val bridge = AdblockBridge.getInstance()
        bridge.compileRules("example.com#$#log('test')", "")
        
        val resStandard = bridge.getCosmeticResources("https://example.com/", aggressive = false)
        assertFalse("Standard mode should strip procedural from default engine", resStandard.procedural.contains("log('test')"))

        val resAggressive = bridge.getCosmeticResources("https://example.com/", aggressive = true)
        assertTrue("Aggressive mode should include procedural from default engine", resAggressive.procedural.contains("log('test')"))
    }
}
