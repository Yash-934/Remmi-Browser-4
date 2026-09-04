package com.remmi.browser.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationSecurityAuthorityTest {

  @Test
  fun testHttpsUpgrade() {
    val res = NavigationSecurityAuthority.validateAndSanitizeNavigation("http://example.com/test", isGhost = false)
    assertEquals(NavigationDecision.ALLOW, res.decision)
    assertEquals("https://example.com/test", res.sanitizedUrl)
  }

  @Test
  fun testDangerousSchemesBlocked() {
    val jsRes = NavigationSecurityAuthority.validateAndSanitizeNavigation("javascript:alert(1)", isGhost = false)
    assertEquals(NavigationDecision.BLOCK, jsRes.decision)

    val dataRes = NavigationSecurityAuthority.validateAndSanitizeNavigation("data:text/html,<b>Hello</b>", isGhost = false)
    assertEquals(NavigationDecision.BLOCK, dataRes.decision)

    val intentRes = NavigationSecurityAuthority.validateAndSanitizeNavigation("intent://view#Intent;scheme=http;end", isGhost = false)
    assertEquals(NavigationDecision.BLOCK, intentRes.decision)

    val fileRes = NavigationSecurityAuthority.validateAndSanitizeNavigation("file:///etc/passwd", isGhost = false)
    assertEquals(NavigationDecision.BLOCK, fileRes.decision)
  }

  @Test
  fun testLocalNetworkAccessBlocking() {
    val privateIpRes = NavigationSecurityAuthority.validateAndSanitizeNavigation("http://192.168.1.1/admin", isGhost = false)
    assertEquals(NavigationDecision.BLOCK, privateIpRes.decision)

    val router10Res = NavigationSecurityAuthority.validateAndSanitizeNavigation("http://10.0.0.1/", isGhost = false)
    assertEquals(NavigationDecision.BLOCK, router10Res.decision)

    val router172Res = NavigationSecurityAuthority.validateAndSanitizeNavigation("http://172.16.0.1/", isGhost = false)
    assertEquals(NavigationDecision.BLOCK, router172Res.decision)
  }

  @Test
  fun testOnionRoutingIsolation() {
    CurrentTorRoute.clearRoute()

    // Clearnet / Shield mode attempting .onion without verified Tor
    val res = NavigationSecurityAuthority.validateAndSanitizeNavigation("http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion", isGhost = false)
    assertEquals(NavigationDecision.BLOCK, res.decision)

    // With verified Tor active
    val gen = CurrentTorRoute.markStartingGhost()
    CurrentTorRoute.updateRoute(
      socksPort = 9050,
      isGhostActive = true,
      isVerified = true,
      exitIp = "185.220.101.5",
      generation = gen
    )
    CurrentTorRoute.setPhase(GhostRoutePhase.READY, gen)

    val resGhost = NavigationSecurityAuthority.validateAndSanitizeNavigation("http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion", isGhost = true)
    assertEquals(NavigationDecision.ALLOW, resGhost.decision)

    CurrentTorRoute.clearRoute()
  }
}
