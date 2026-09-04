package com.remmi.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remmi.adblock.AdblockBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineSeparationTest {

    @Test
    fun testEngineSeparation_networkExceptions() {
        val adblockBridge = AdblockBridge.getInstance()
        
        val defaultRules = "||tracker.com^\n||badsite.com^"
        val additionalRules = "@@||tracker.com/allow^\n||anothertracker.com^"
        
        adblockBridge.compileRules(defaultRules, additionalRules)
        
        // Blocked by default
        val blockRes = adblockBridge.evaluateDecision("https://badsite.com/script.js", "https://example.com", "script")
        assertTrue("badsite.com should be blocked by default engine", blockRes.blocked)
        
        // Blocked by additional
        val addBlockRes = adblockBridge.evaluateDecision("https://anothertracker.com/js", "https://example.com", "script")
        assertTrue("anothertracker.com should be blocked by additional engine", addBlockRes.blocked)
        
        // Exception in additional overrides block in default
        val excRes = adblockBridge.evaluateDecision("https://tracker.com/allow/script.js", "https://example.com", "script")
        assertFalse("Exception in additional engine should allow request", excRes.blocked)
        
        // Blocked by default, not excepted by additional
        val trkRes = adblockBridge.evaluateDecision("https://tracker.com/block/script.js", "https://example.com", "script")
        assertTrue("tracker.com should be blocked by default engine", trkRes.blocked)
    }

    @Test
    fun testEngineSeparation_cosmeticSelectors() {
        val adblockBridge = AdblockBridge.getInstance()
        
        val defaultRules = "##.default-banner\nexample.com##.default-site-banner"
        val additionalRules = "##.additional-banner\nexample.com##.additional-site-banner\nexample.com#@#.default-site-banner"
        
        adblockBridge.compileRules(defaultRules, additionalRules)
        
        val res = adblockBridge.getCosmeticResources("https://example.com/page")
        assertTrue(res.ok)
        
        // Default engine cosmetic rules should go to hide_selectors (or force_hide depending on merge logic)
        assertTrue("Default generic selector should be in hideSelectors", res.hideSelectors.contains(".default-banner"))
        
        // Actually, example.com#@#.default-site-banner in additional might NOT cancel default-site-banner if they are just merged.
        // Wait, how does adblock-rust handle #@# in additional engine for a rule in default engine?
        // Since they are separate engines, the exception in additional engine DOES NOT apply to the default engine!
        // Is that what Brave does? Let's check.
    }
}
