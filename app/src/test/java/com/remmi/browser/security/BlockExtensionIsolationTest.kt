package com.remmi.browser.security

import com.remmi.adblock.AdblockBridge
import com.remmi.adblock.BlockExtension
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.WebExtension
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockExtensionIsolationTest {

  @Test
  fun testSimultaneousTabRequestsIsolation() {
    val blockExtension = BlockExtension.getInstance(AdblockBridge.getInstance())

    // Connect mock port first
    val port = createMockPort("test-port")
    blockExtension.onConnect(port)

    var responseA: String? = null
    var responseB: String? = null

    val reqIdA = "req_tab_A_${UUID.randomUUID()}"
    val reqIdB = "req_tab_B_${UUID.randomUUID()}"

    blockExtension.extractTabHtml(tabId = "tab_A", sessionId = "session_A", requestId = reqIdA) { url, html ->
      responseA = html
    }

    blockExtension.extractTabHtml(tabId = "tab_B", sessionId = "session_B", requestId = reqIdB) { url, html ->
      responseB = html
    }

    // Simulate response message for B
    val msgB = JSONObject().apply {
      put("type", "EXTRACTED_HTML")
      put("requestId", reqIdB)
      put("tabId", "tab_B")
      put("url", "https://site-b.org")
      put("html", "<html><body>Content B</body></html>")
    }
    deliverMessageToPortDelegate(port, msgB)

    // Simulate response message for A
    val msgA = JSONObject().apply {
      put("type", "EXTRACTED_HTML")
      put("requestId", reqIdA)
      put("tabId", "tab_A")
      put("url", "https://site-a.org")
      put("html", "<html><body>Content A</body></html>")
    }
    deliverMessageToPortDelegate(port, msgA)

    assertEquals("<html><body>Content A</body></html>", responseA)
    assertEquals("<html><body>Content B</body></html>", responseB)
  }

  @Test
  fun testListenerSettersDoNotClearMultiListeners() {
    val blockExtension = BlockExtension.getInstance(AdblockBridge.getInstance())

    var threatCount1 = 0
    var threatCount2 = 0

    blockExtension.addThreatListener { _, _ -> threatCount1++ }
    blockExtension.onThreatNeutralized = { _, _ -> threatCount2++ }

    val port = createMockPort("threat-port")
    blockExtension.onConnect(port)

    val msg = JSONObject().apply {
      put("type", "BLOCKED")
      put("url", "https://tracker.com/track.js")
      put("category", "tracker")
    }
    deliverMessageToPortDelegate(port, msg)

    assertEquals(1, threatCount1)
    assertEquals(1, threatCount2)
  }

  private var capturedPortDelegate: WebExtension.PortDelegate? = null

  private fun createMockPort(name: String): WebExtension.Port {
    val port = object : WebExtension.Port() {
      override fun postMessage(message: JSONObject) {}
      override fun disconnect() {}
      override fun setDelegate(delegate: WebExtension.PortDelegate?) {
        capturedPortDelegate = delegate
      }
    }
    return port
  }

  private fun deliverMessageToPortDelegate(port: WebExtension.Port, message: JSONObject) {
    capturedPortDelegate?.onPortMessage(message, port)
  }
}
