package com.remmi.browser.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedirectInspectorTest {

  @Test
  fun testTrackingParameterDetection() {
    val trackingUrl = "https://example.com/article?utm_source=twitter&utm_medium=social&utm_campaign=winter_sale"
    val cleanUrl = "https://example.com/article?page=2&sort=recent"

    assertTrue(RedirectInspector.hasTrackingParams(trackingUrl))
    assertFalse(RedirectInspector.hasTrackingParams(cleanUrl))
  }

  @Test
  fun testStripTrackingParameters() {
    val input = "https://example.com/product?id=99&utm_source=newsletter&fbclid=IwAR2xyz&ref=affiliate_123&gclid=test1234"
    val stripped = RedirectInspector.stripTrackingParameters(input)

    assertEquals("https://example.com/product?id=99", stripped)
  }

  @Test
  fun testStripAllTrackingParametersPreservesOtherParamsAndFragment() {
    val input = "https://shop.example.org:8443/checkout?item=shoe&utm_medium=cpc#reviews"
    val stripped = RedirectInspector.stripTrackingParameters(input)

    assertEquals("https://shop.example.org:8443/checkout?item=shoe#reviews", stripped)
  }

  @Test
  fun testAdvancedTrackingTokens() {
    val input = "https://analytics.site.io/click?user=123&s_kwcid=AL!123!456&gbraid=0AAAAAD&_ga=GA1.2.3.4"
    val stripped = RedirectInspector.stripTrackingParameters(input)

    assertEquals("https://analytics.site.io/click?user=123", stripped)
  }

  @Test
  fun testSsrfBlockingForLoopbackAndPrivateRanges() {
    val loopbackUrls = listOf(
      "http://127.0.0.1/admin",
      "http://127.0.0.2:8080/metrics",
      "http://0.0.0.0/test",
      "http://10.0.0.1/router",
      "http://100.64.0.1/cgnat",
      "http://169.254.169.254/latest/meta-data/",
      "http://172.16.0.5/internal",
      "http://192.168.1.1/gateway",
      "http://198.18.0.1/benchmark",
      "http://224.0.0.1/multicast",
      "http://240.0.0.1/reserved",
      "http://localhost:3000/api",
      "http://[::1]/secret",
      "http://[fc00::1]/ula",
      "http://[fe80::1]/linklocal",
      "http://[ff02::1]/multicast",
      "http://[::ffff:127.0.0.1]/ipv4mapped",
      "http://[::ffff:10.0.0.1]/ipv4mapped",
      "http://[::ffff:192.168.1.1]/ipv4mapped",
      "http://service.internal/status",
      "http://printer.local/print",
      "file:///etc/passwd",
      "javascript:alert(1)",
      "data:text/html,<b>pwned</b>",
      "chrome://settings",
      "resource://modules",
      "about:config",
      "intent://example.com#Intent"
    )

    for (badUrl in loopbackUrls) {
      val (isSafe, reason) = RedirectInspector.isTargetSafeForInspection(badUrl)
      assertFalse("URL $badUrl should be blocked by SSRF shield", isSafe)
      assertTrue("Reason should describe security block for $badUrl", reason != null && reason.isNotBlank())
    }
  }

  @Test
  fun testSchemeSafetyForNavigation() {
    assertTrue(RedirectInspector.isSchemeSafeForNavigation("https://example.com"))
    assertTrue(RedirectInspector.isSchemeSafeForNavigation("http://example.com/test"))
    assertTrue(RedirectInspector.isSchemeSafeForNavigation("about:blank"))

    assertFalse(RedirectInspector.isSchemeSafeForNavigation("javascript:alert(1)"))
    assertFalse(RedirectInspector.isSchemeSafeForNavigation("data:text/html,<h1>test</h1>"))
    assertFalse(RedirectInspector.isSchemeSafeForNavigation("file:///etc/hosts"))
    assertFalse(RedirectInspector.isSchemeSafeForNavigation("content://media/external"))
    assertFalse(RedirectInspector.isSchemeSafeForNavigation("chrome://flags"))
    assertFalse(RedirectInspector.isSchemeSafeForNavigation("resource://app"))
    assertFalse(RedirectInspector.isSchemeSafeForNavigation("about:config"))
    assertFalse(RedirectInspector.isSchemeSafeForNavigation("intent://scan#Intent"))
    assertFalse(RedirectInspector.isSchemeSafeForNavigation("blob:https://example.com/1234"))
    assertFalse(RedirectInspector.isSchemeSafeForNavigation("filesystem:https://example.com/temporary"))
  }

  @Test
  fun testFinalUrlNullInvariantOnFailure() = kotlinx.coroutines.test.runTest {
    val ssrfBlocked = RedirectInspector.inspectUrl("http://127.0.0.1/admin", isGhost = false)
    assertEquals(RedirectResolutionStatus.SSRF_BLOCKED, ssrfBlocked.status)
    assertEquals(null, ssrfBlocked.finalUrl)

    val torLost = RedirectInspector.inspectUrl("https://example.com", isGhost = true, socksPort = null)
    assertEquals(RedirectResolutionStatus.TOR_ROUTE_LOST, torLost.status)
    assertEquals(null, torLost.finalUrl)
  }

  @Test
  fun testSsrfAllowedForPublicWebUrls() {
    val publicUrls = listOf(
      "https://example.com",
      "https://duckduckgo.com/html",
      "https://8.8.8.8/dns-query",
      "https://1.1.1.1"
    )

    for (goodUrl in publicUrls) {
      val (isSafe, reason) = RedirectInspector.isTargetSafeForInspection(goodUrl)
      assertTrue("Public URL $goodUrl should be allowed (reason: $reason)", isSafe)
    }
  }

  @Test
  fun testExtractNestedTargetUrlFromQueryParameters() {
    val encodedUrl = "https://tracker.adnetwork.com/click?dest=https%3A%2F%2Fdestination.com%2Ftarget%2Fproduct%3Fid%3D1"
    val extracted = RedirectInspector.extractNestedTargetUrl(encodedUrl)
    assertEquals("https://destination.com/target/product?id=1", extracted)

    val redirectUrl = "https://affiliate.bridge.org/out?url=https%3A%2F%2Ftarget-store.com%2Fitem"
    val extracted2 = RedirectInspector.extractNestedTargetUrl(redirectUrl)
    assertEquals("https://target-store.com/item", extracted2)

    val cleanUrl = "https://example.com/about"
    val extracted3 = RedirectInspector.extractNestedTargetUrl(cleanUrl)
    assertEquals(null, extracted3)
  }

  @Test
  fun testGhostModeRequiresTorSocksProxy() = kotlinx.coroutines.test.runTest {
    val result = RedirectInspector.inspectUrl("https://example.com", isGhost = true, socksPort = null)
    val error = result.error
    org.junit.Assert.assertNotNull("Should fail-safe when Ghost mode has no Tor route", error)
    assertTrue("Error should mention Tor or Ghost", error!!.contains("Tor", ignoreCase = true) || error.contains("Ghost", ignoreCase = true))
  }
}

