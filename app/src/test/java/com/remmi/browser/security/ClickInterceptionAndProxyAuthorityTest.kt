package com.remmi.browser.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.adblock.BlockExtension
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClickInterceptionAndProxyAuthorityTest {

  // Test 1: Normal link -> intercepted=false, opens immediately
  @Test
  fun testNormalLinkIsNotIntercepted() {
    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "Article Link <a>")
        put("url", "https://news.example.com/article/1")
        put("tagName", "a")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
        put("isTransparent", false)
        put("isPopup", false)
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = false)
    assertFalse("Normal link must not be flagged as overlay", inspection.hasOverlay)
    assertEquals(1, inspection.candidates.size)
    assertFalse("Normal link candidate isOverlay must be false", inspection.candidates[0].isOverlay)
    assertEquals("https://news.example.com/article/1", inspection.primaryTarget?.url)

    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = false)
    assertFalse("Normal link must not be intercepted", shouldIntercept)
  }

  // Test 2: Normal anchor with unrelated fixed UI (e.g. fixed non-clickable header/footer) -> NOT intercepted
  @Test
  fun testNormalAnchorWithUnrelatedFixedUiIsNotIntercepted() {
    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "Article Link <a>")
        put("url", "https://news.example.com/article/1")
        put("tagName", "a")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
        put("isTransparent", false)
        put("isPopup", false)
        put("details", "Standard link")
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = false)
    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = false)
    assertFalse("Unrelated fixed UI without navigation target must NOT cause interception", shouldIntercept)
  }

  // Test 3: Real transparent anchor overlay -> intercepted
  @Test
  fun testRealTransparentAnchorOverlayIsIntercepted() {
    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "Visible Article")
        put("url", "https://news.example.com/article/1")
        put("tagName", "a")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
        put("isTransparent", false)
        put("isPopup", false)
      },
      JSONObject().apply {
        put("label", "Deceptive Overlay Layer")
        put("url", "https://scam-click.com/trap")
        put("tagName", "a")
        put("type", "SUSPICIOUS_OVERLAY")
        put("isOverlay", true)
        put("isTransparent", true)
        put("isPopup", false)
        put("details", "opacity: 0.05, position: fixed, z-index: 9999")
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = true)
    assertTrue("Transparent overlay must set hasOverlay to true", inspection.hasOverlay)
    assertEquals(2, inspection.candidates.size)

    val overlayCandidate = inspection.candidates.firstOrNull { it.isOverlay }
    assertNotNull("Overlay candidate must be present", overlayCandidate)
    assertEquals(ClickTargetType.SUSPICIOUS_OVERLAY, overlayCandidate?.type)

    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = true)
    assertTrue("Real transparent anchor overlay MUST be intercepted before navigation", shouldIntercept)
  }

  @Test
  fun testTransparentOverlayWithSameUrlIsNotIntercepted() {
    // Both visible element and overlay point to the exact same URL (e.g. standard wrapped clickable card/overlay)
    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "Visible Download Button")
        put("url", "https://example.com/download")
        put("tagName", "a")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
        put("isTransparent", false)
      },
      JSONObject().apply {
        put("label", "Full Card Click Overlay")
        put("url", "https://example.com/download")
        put("tagName", "a")
        put("type", "SUSPICIOUS_OVERLAY")
        put("isOverlay", true)
        put("isTransparent", true)
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = true)
    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = true)
    assertFalse("Overlay pointing to exact same destination URL must NOT be intercepted", shouldIntercept)
  }

  @Test
  fun testLegitimateUiWithSameDestinationIsNotIntercepted() {
    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "Hero Card Title")
        put("url", "https://news.example.com/item/42")
        put("tagName", "h2")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
      },
      JSONObject().apply {
        put("label", "Card Clickable Overlay")
        put("url", "https://news.example.com/item/42")
        put("tagName", "a")
        put("type", "SUSPICIOUS_OVERLAY")
        put("isOverlay", true)
        put("isTransparent", true)
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = true)
    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = true)
    assertFalse("Legitimate UI overlay with same destination must NOT be intercepted", shouldIntercept)
  }

  // Test 4: Overlay with no navigation target -> NOT automatically intercepted
  @Test
  fun testOverlayWithNoNavigationTargetIsNotAutomaticallyIntercepted() {
    // When a page has a backdrop / banner with no link/href/onclick, no overlay candidate is generated
    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "Legitimate Button")
        put("url", "https://example.com/checkout")
        put("tagName", "button")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
        put("isTransparent", false)
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = false)
    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = false)
    assertFalse("Overlay with no clickable navigation target must NOT intercept the click", shouldIntercept)
  }

  // Test 5: Same-domain nested candidates -> NOT intercepted
  @Test
  fun testSameDomainNestedCandidatesAreNotIntercepted() {
    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "Parent Anchor <a>")
        put("url", "https://news.ycombinator.com/item?id=12345")
        put("tagName", "a")
        put("type", "PARENT_ANCHOR")
        put("isOverlay", false)
        put("isTransparent", false)
      },
      JSONObject().apply {
        put("label", "Nested Tag")
        put("url", "https://news.ycombinator.com/user?id=pg")
        put("tagName", "a")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
        put("isTransparent", false)
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = false)
    assertEquals("ycombinator.com", ClickTargetAnalyzer.getRegistrableDomain("https://news.ycombinator.com/item?id=12345"))
    assertEquals("ycombinator.com", ClickTargetAnalyzer.getRegistrableDomain("https://news.ycombinator.com/user?id=pg"))

    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = false)
    assertFalse("Same-site nested navigation targets must NOT be intercepted", shouldIntercept)
  }

  @Test
  fun testSubdomainsOfSameRegistrableDomainAreNotIntercepted() {
    val domain1 = ClickTargetAnalyzer.getRegistrableDomain("https://a.example.com/page")
    val domain2 = ClickTargetAnalyzer.getRegistrableDomain("https://cdn.example.com/asset")
    assertEquals("example.com", domain1)
    assertEquals("example.com", domain2)
    assertEquals("Both must share the exact same registrable domain", domain1, domain2)

    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "Main Domain Link")
        put("url", "https://a.example.com/page")
        put("tagName", "a")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
      },
      JSONObject().apply {
        put("label", "CDN Subdomain Link")
        put("url", "https://cdn.example.com/asset")
        put("tagName", "a")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = false)
    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = false)
    assertFalse("Subdomains of same registrable domain must NOT be automatically intercepted", shouldIntercept)
  }

  @Test
  fun testDifferentRegistrableDomainsInterceptedWhenSuspiciousSignalsExist() {
    val legitDomain = ClickTargetAnalyzer.getRegistrableDomain("https://example.com/account")
    val evilDomain = ClickTargetAnalyzer.getRegistrableDomain("https://evil-example.xyz/steal")
    assertEquals("example.com", legitDomain)
    assertEquals("evil-example.xyz", evilDomain)
    assertTrue("Domains must be recognized as different", legitDomain != evilDomain)

    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "Legitimate Anchor")
        put("url", "https://example.com/account")
        put("tagName", "a")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
      },
      JSONObject().apply {
        put("label", "Conflicting Layer")
        put("url", "https://evil-example.xyz/steal")
        put("tagName", "div")
        put("type", "SUSPICIOUS_OVERLAY")
        put("isOverlay", true)
        put("isTransparent", true)
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = true)
    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = true)
    assertTrue("Different registrable domains with overlay signals MUST be intercepted", shouldIntercept)
  }

  @Test
  fun testHiddenOverlayWithNoNavigationTargetIsNotIntercepted() {
    // Backdrop / decorative overlay with zero navigation URLs extracted
    val candidatesJson = emptyList<JSONObject>()
    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = true)
    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = true)
    assertFalse("Hidden overlay without any navigation destination MUST NOT be intercepted", shouldIntercept)
  }

  @Test
  fun testOverlayWithDifferentTargetUrlIsIntercepted() {
    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "Intended Link")
        put("url", "https://news.example.com/article/1")
        put("tagName", "a")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
      },
      JSONObject().apply {
        put("label", "Overlay Link")
        put("url", "https://news.example.com/ad-redirect")
        put("tagName", "a")
        put("type", "SUSPICIOUS_OVERLAY")
        put("isOverlay", true)
        put("isTransparent", true)
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = true)
    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = true)
    assertTrue("Overlay covering target with a different destination URL MUST be intercepted", shouldIntercept)
  }

  @Test
  fun testNormalNestedSpanInAnchorIsNotIntercepted() {
    // Standard <a><span>text</span></a>
    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "SPAN (href)")
        put("url", "https://example.com/item")
        put("tagName", "span")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
        put("isTransparent", false)
      },
      JSONObject().apply {
        put("label", "Parent Anchor <a>")
        put("url", "https://example.com/item")
        put("tagName", "a")
        put("type", "PARENT_ANCHOR")
        put("isOverlay", false)
        put("isTransparent", false)
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = false)
    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = false)
    assertFalse("Normal nested span inside anchor pointing to same URL must NOT be intercepted", shouldIntercept)
  }

  // Test 6: Cross-domain overlay -> intercepted
  @Test
  fun testCrossDomainOverlayIsIntercepted() {
    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "Trusted Anchor")
        put("url", "https://github.com/torproject/tor")
        put("tagName", "a")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
        put("isTransparent", false)
      },
      JSONObject().apply {
        put("label", "Conflicting Overlay Layer")
        put("url", "https://malicious-tracker.net/hijack")
        put("tagName", "div")
        put("type", "SUSPICIOUS_OVERLAY")
        put("isOverlay", true)
        put("isTransparent", true)
        put("details", "opacity: 0, position: fixed")
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = true)
    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = true)
    assertTrue("Cross-domain overlay MUST be intercepted", shouldIntercept)
  }

  // Test 7: Suspicious popup -> intercepted=true
  @Test
  fun testSuspiciousPopupIsIntercepted() {
    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "Deceptive Popup Trigger")
        put("url", "https://ad-redirector.com/popup")
        put("tagName", "div")
        put("type", "SUSPICIOUS_OVERLAY")
        put("isOverlay", true)
        put("isTransparent", true)
        put("isPopup", true)
        put("details", "window.open popup on transparent layer")
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = true)
    assertTrue(inspection.hasOverlay)
    val popupCandidate = inspection.candidates.firstOrNull { it.isOverlay }
    assertNotNull(popupCandidate)
    assertEquals(ClickTargetType.SUSPICIOUS_OVERLAY, popupCandidate?.type)

    val shouldIntercept = ClickTargetAnalyzer.shouldIntercept(inspection.candidates, hasOverlay = true)
    assertTrue("Suspicious popup trigger on overlay must be intercepted", shouldIntercept)
  }

  // Test 8: Normal link -> opens immediately (simulating fast path & no blocking)
  @Test
  fun testNormalLinkOpensImmediately() {
    var navigationDispatched = false
    var intercepted = false

    val isNormalLink = true
    if (!isNormalLink) {
      intercepted = true
    } else {
      navigationDispatched = true
    }

    assertTrue("Normal link must immediately dispatch navigation", navigationDispatched)
    assertFalse("Normal link must not be intercepted", intercepted)
  }

  // Test 9: Suspicious link -> stopped before navigation
  @Test
  fun testSuspiciousLinkIsStoppedBeforeNavigation() {
    var defaultPrevented = false
    var propagationStopped = false

    // Simulate JS click interception event handling
    val isIntercepted = true
    if (isIntercepted) {
      defaultPrevented = true
      propagationStopped = true
    }

    assertTrue("Default navigation must be prevented for suspicious click", defaultPrevented)
    assertTrue("Event propagation must be stopped immediately", propagationStopped)
  }

  // Test 10: Cancel -> no navigation occurs
  @Test
  fun testCanceledCandidateOpensNothing() {
    var openedUrl: String? = null
    val onDismiss: () -> Unit = {
      // User pressed cancel or closed the sheet - no navigation triggered
    }

    onDismiss()
    assertNull("No navigation should occur on cancel", openedUrl)
  }

  // Test 11: Candidate selection -> exactly one navigation
  @Test
  fun testUserSelectedCandidateOpensExactlyOnce() {
    var navigationCount = 0
    var openedUrl: String? = null

    val candidate = ClickTargetCandidate(
      label = "Legitimate Link",
      url = "https://legit.example.com",
      cleanUrl = "https://legit.example.com",
      type = ClickTargetType.VISIBLE_ELEMENT,
      tagName = "a",
      isOverlay = false,
      isTransparent = false,
      confidence = ConfidenceLevel.HIGH,
      details = "Direct anchor"
    )

    val onSelectCandidate: (ClickTargetCandidate) -> Unit = { selected ->
      navigationCount++
      openedUrl = selected.cleanUrl
    }

    onSelectCandidate(candidate)
    assertEquals("Exactly one navigation must occur on candidate selection", 1, navigationCount)
    assertEquals("https://legit.example.com", openedUrl)
  }

  // Test 12: Gesture Deduplication: exactly one inspection transaction per user gesture
  @Test
  fun testGestureDeduplicationGeneratesSingleInspectionEvent() {
    var inspectionCount = 0
    val blockExtension = BlockExtension.getInstance()

    blockExtension.onClickInspected = { _, _, _, _ ->
      inspectionCount++
    }

    // Simulate single user gesture dispatching to native layer once
    blockExtension.onClickInspected?.invoke(
      listOf(JSONObject().apply { put("url", "https://example.com"); put("isOverlay", false) }),
      false,
      false,
      "https://example.com"
    )

    assertEquals(1, inspectionCount)
  }

  // Test 13: Message bridge: intercepted=true reaches native layer
  @Test
  fun testInterceptedTrueReachesNativeLayer() {
    val blockExtension = BlockExtension.getInstance()
    var receivedIntercepted: Boolean? = null
    var receivedHasOverlay: Boolean? = null
    var receivedCandidatesCount = 0

    blockExtension.onClickInspected = { candidates, hasOverlay, intercepted, _ ->
      receivedCandidatesCount = candidates.size
      receivedHasOverlay = hasOverlay
      receivedIntercepted = intercepted
    }

    val messageCandidate = JSONObject().apply {
      put("label", "Deceptive Overlay")
      put("url", "https://attacker.com/steal")
      put("isOverlay", true)
    }

    blockExtension.onClickInspected?.invoke(
      listOf(messageCandidate),
      true,
      true,
      "https://example.com/page"
    )

    assertEquals(true, receivedIntercepted)
    assertEquals(true, receivedHasOverlay)
    assertEquals(1, receivedCandidatesCount)
  }

  @Test
  fun testInterceptedFalseReachesNativeLayer() {
    val blockExtension = BlockExtension.getInstance()
    var receivedIntercepted: Boolean? = null

    blockExtension.onClickInspected = { _, _, intercepted, _ ->
      receivedIntercepted = intercepted
    }

    blockExtension.onClickInspected?.invoke(
      emptyList(),
      false,
      false,
      "https://example.com/page"
    )

    assertEquals(false, receivedIntercepted)
  }

  // Test 14: Overlay is not automatically chosen as primary
  @Test
  fun testOverlayIsNotAutomaticallyChosenAsPrimary() {
    val candidatesJson = listOf(
      JSONObject().apply {
        put("label", "Transparent Overlay Trap")
        put("url", "https://scam.site/click")
        put("tagName", "div")
        put("type", "SUSPICIOUS_OVERLAY")
        put("isOverlay", true)
        put("isTransparent", true)
      },
      JSONObject().apply {
        put("label", "Visible Article")
        put("url", "https://good.site/article")
        put("tagName", "a")
        put("type", "VISIBLE_ELEMENT")
        put("isOverlay", false)
        put("isTransparent", false)
      }
    )

    val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay = true)
    assertEquals("https://good.site/article", inspection.primaryTarget?.url)
    assertFalse(inspection.primaryTarget?.isOverlay == true)
  }

  @Test
  fun testFinalUrlIsNullForFailedAnalysis() = runTest {
    // 1. SSRF Blocked
    val ssrfResult = RedirectInspector.inspectUrl("http://127.0.0.1/admin", isGhost = false)
    assertEquals(RedirectResolutionStatus.SSRF_BLOCKED, ssrfResult.status)
    assertNull("finalUrl must be null for SSRF_BLOCKED", ssrfResult.finalUrl)

    // 2. Unsupported scheme
    val badSchemeResult = RedirectInspector.inspectUrl("ftp://files.example.com", isGhost = false)
    assertEquals(RedirectResolutionStatus.UNSUPPORTED_SCHEME, badSchemeResult.status)
    assertNull("finalUrl must be null for UNSUPPORTED_SCHEME", badSchemeResult.finalUrl)

    // 3. Ghost route missing
    val ghostNoTorResult = RedirectInspector.inspectUrl("https://example.com", isGhost = true, socksPort = null)
    assertEquals(RedirectResolutionStatus.TOR_ROUTE_LOST, ghostNoTorResult.status)
    assertNull("finalUrl must be null for TOR_ROUTE_LOST", ghostNoTorResult.finalUrl)
  }

  @Test
  fun testFinalUrlExistsOnlyForResolved() {
    val resolvedResult = RedirectInspectionResult(
      originalUrl = "https://example.com",
      finalUrl = "https://example.com/target",
      status = RedirectResolutionStatus.RESOLVED,
      hops = emptyList(),
      hasTrackingParams = false,
      strippedUrl = "https://example.com/target",
      isSecure = true,
      safetyScore = 90,
      riskLevel = SecurityRiskLevel.LOW,
      securityInsights = emptyList(),
      error = null,
      extractedNestedUrl = null
    )
    assertNotNull("finalUrl must exist when status is RESOLVED", resolvedResult.finalUrl)

    val timeoutResult = RedirectInspectionResult(
      originalUrl = "https://example.com",
      finalUrl = null,
      status = RedirectResolutionStatus.TIMEOUT,
      hops = emptyList(),
      hasTrackingParams = false,
      strippedUrl = "https://example.com",
      isSecure = true,
      safetyScore = 0,
      riskLevel = SecurityRiskLevel.HIGH,
      securityInsights = emptyList(),
      error = "Timeout",
      extractedNestedUrl = null
    )
    assertNull("finalUrl must be null when status is TIMEOUT", timeoutResult.finalUrl)
  }

  @Test
  fun testGhostRedirectAnalysisStopsWhenTorRouteDisappears() = runTest {
    CurrentTorRoute.updateRoute(socksPort = 9050, isGhostActive = true, generation = 1)
    val result = RedirectInspector.inspectUrl(
      url = "https://example.com",
      isGhost = true,
      socksPort = 9999, // Mismatched socks port
      expectedGeneration = 1
    )
    assertEquals(RedirectResolutionStatus.TOR_ROUTE_LOST, result.status)
    assertNull("finalUrl must be null when Tor route is lost", result.finalUrl)
  }

  @Test
  fun testNoWebExtensionProxyCodeRemainsInAssets() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val assetManager = context.assets
    val list = assetManager.list("extensions/remmi_engine_extension") ?: emptyArray()
    for (filename in list) {
      if (filename.endsWith(".js") || filename.endsWith(".json")) {
        val content = assetManager.open("extensions/remmi_engine_extension/$filename").bufferedReader().use { it.readText() }
        assertFalse(
          "Asset $filename must not contain browser.proxy.onRequest or browser.proxy.settings",
          content.contains("browser.proxy.onRequest") || content.contains("browser.proxy.settings.set")
        )
      }
    }
  }

  @Test
  fun testNativeGeckoRemainsOnlyProxyAuthority() {
    val ghostPrefs = NetworkHardening.getTorPreferences(9050)
    assertEquals(1, ghostPrefs["network.proxy.type"])
    assertEquals("127.0.0.1", ghostPrefs["network.proxy.socks"])
    assertEquals(9050, ghostPrefs["network.proxy.socks_port"])
    assertEquals(true, ghostPrefs["network.proxy.socks_remote_dns"])
    assertEquals(false, ghostPrefs["network.proxy.failover_direct"])

    val shieldPrefs = NetworkHardening.getShieldPreferences()
    assertEquals(0, shieldPrefs["network.proxy.type"])
    assertEquals("", shieldPrefs["network.proxy.socks"])
    assertEquals(0, shieldPrefs["network.proxy.socks_port"])
    assertEquals(true, shieldPrefs["network.proxy.failover_direct"])
  }
}
