package com.remmi.browser.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.adblock.AdblockBridge
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NetworkFilterCoverageTest {

    private lateinit var context: Context
    private lateinit var bridge: AdblockBridge

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        bridge = AdblockBridge.getInstance()
        bridge.initializeAsync()
    }

    @Test
    fun testResourceTypes() {
        val rules = "||test-beacon.com^\n||test-ping.com^\n||test-xhr.com^\n||test-csp.com^\n||test-websocket.com^\n||test-image.com^\$image\n||test-script.com^\$script"
        bridge.compileRules(rules)

        assertTrue("Beacon should be blocked", bridge.evaluateDecision("http://test-beacon.com", resourceType = "beacon").blocked)
        assertTrue("Ping should be blocked", bridge.evaluateDecision("http://test-ping.com", resourceType = "ping").blocked)
        assertTrue("XHR should be blocked", bridge.evaluateDecision("http://test-xhr.com", resourceType = "xmlhttprequest").blocked)
        assertTrue("CSP should be blocked", bridge.evaluateDecision("http://test-csp.com", resourceType = "csp_report").blocked)
        assertTrue("WebSocket should be blocked", bridge.evaluateDecision("wss://test-websocket.com", resourceType = "websocket").blocked)
        assertTrue("Image should be blocked", bridge.evaluateDecision("http://test-image.com", resourceType = "image").blocked)
        assertTrue("Script should be blocked", bridge.evaluateDecision("http://test-script.com", resourceType = "script").blocked)
        
        // Type specific exceptions
        assertFalse("Script shouldn't match image rule", bridge.evaluateDecision("http://test-image.com", resourceType = "script").blocked)
    }

    @Test
    fun testMethodAwareMatching() {
        val rules = "||test-method.com^\$method=post"
        bridge.compileRules(rules)

        assertTrue("POST should be blocked", bridge.evaluateDecision("http://test-method.com", method = "POST").blocked)
        assertFalse("GET should not be blocked", bridge.evaluateDecision("http://test-method.com", method = "GET").blocked)
    }

    @Test
    fun testThirdPartyMatching() {
        val rules = "||test-third-party.com^\$third-party"
        bridge.compileRules(rules)

        // First party
        assertFalse("First party should not be blocked", bridge.evaluateDecision("http://test-third-party.com", sourceUrl = "http://test-third-party.com", thirdParty = false).blocked)
        // Third party
        assertTrue("Third party should be blocked", bridge.evaluateDecision("http://test-third-party.com", sourceUrl = "http://example.com", thirdParty = true).blocked)
    }

    @Test
    fun testMergeSemantics_ExceptionOverridesBlock() {
        bridge.compileRules("||test-merge.com^", "@@||test-merge.com^")
        assertFalse("Exception should override block", bridge.evaluateDecision("http://test-merge.com").blocked)
    }

    @Test
    fun testMergeSemantics_ImportantOverridesException() {
        bridge.compileRules("||test-merge.com^\$important", "@@||test-merge.com^")
        assertTrue("Important block should override exception", bridge.evaluateDecision("http://test-merge.com").blocked)
    }

    @Test
    fun testMergeSemantics_ExceptionOverridesImportantException() {
        bridge.compileRules("@@||test-merge.com^\$important", "||test-merge.com^")
        assertFalse("Important exception should override block", bridge.evaluateDecision("http://test-merge.com").blocked)
    }
}
