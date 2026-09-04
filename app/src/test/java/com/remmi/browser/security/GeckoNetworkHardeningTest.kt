package com.remmi.browser.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.remmi.browser.engine.GeckoPreferenceController

class GeckoNetworkHardeningTest {

  @Test
  fun testCurrentTorRouteLifecycle() {
    CurrentTorRoute.clearRoute()
    assertNull(CurrentTorRoute.currentSocksPort)
    assertFalse(CurrentTorRoute.isGhostActive)
    assertFalse(CurrentTorRoute.isVerified)
    assertNull(CurrentTorRoute.exitIp)

    val gen = CurrentTorRoute.markStartingGhost()
    CurrentTorRoute.updateRoute(
      socksPort = 9150,
      isGhostActive = true,
      isVerified = true,
      exitIp = "185.220.101.5",
      generation = gen
    )

    assertEquals(9150, CurrentTorRoute.currentSocksPort)
    assertTrue(CurrentTorRoute.isGhostActive)
    assertTrue(CurrentTorRoute.isVerified)
    assertEquals("185.220.101.5", CurrentTorRoute.exitIp)

    CurrentTorRoute.clearRoute()
    assertNull(CurrentTorRoute.currentSocksPort)
    assertFalse(CurrentTorRoute.isGhostActive)
  }

  @Test
  fun testNetworkHardeningTorPreferences() {
    val prefs = NetworkHardening.getTorPreferences(9250)
    assertEquals(1, prefs["network.proxy.type"])
    assertEquals("127.0.0.1", prefs["network.proxy.socks"])
    assertEquals(9250, prefs["network.proxy.socks_port"])
    assertEquals(5, prefs["network.proxy.socks_version"])
    assertEquals(true, prefs["network.proxy.socks_remote_dns"])
    assertEquals(false, prefs["network.proxy.failover_direct"])
    assertEquals(false, prefs["network.proxy.allow_bypass"])
    assertEquals(5, prefs["network.trr.mode"])
    assertEquals(false, prefs["media.peerconnection.enabled"])
    assertEquals(true, prefs["privacy.resistFingerprinting"])
    assertEquals(true, prefs["privacy.firstparty.isolate"])
  }

  @Test
  fun testNetworkHardeningShieldPreferences() {
    val prefs = NetworkHardening.getShieldPreferences()
    assertEquals(0, prefs["network.proxy.type"])
    assertEquals("", prefs["network.proxy.socks"])
    assertEquals(0, prefs["network.proxy.socks_port"])
    assertEquals(true, prefs["network.proxy.failover_direct"])
    assertEquals(false, prefs["media.peerconnection.enabled"])
  }

  @Test
  fun ghostMandatoryRoutingContainsAllCriticalPrefs() {
    val prefs = NetworkHardening.getMandatoryTorRoutingPreferences(9050)

    assertEquals(1, prefs["network.proxy.type"])
    assertEquals("127.0.0.1", prefs["network.proxy.socks"])
    assertEquals(9050, prefs["network.proxy.socks_port"])
    assertEquals(5, prefs["network.proxy.socks_version"])

    assertEquals(true, prefs["network.proxy.socks5_remote_dns"])
    assertEquals(true, prefs["network.proxy.socks_remote_dns"])

    assertEquals(false, prefs["network.proxy.failover_direct"])
    assertEquals(false, prefs["network.proxy.allow_bypass"])
    assertEquals("", prefs["network.proxy.no_proxies_on"])

    assertEquals(false, prefs["network.proxy.system_wpad"])
    assertEquals(false, prefs["network.proxy.system_wpad.allowed"])
    assertEquals(false, prefs["network.proxy.retry_failed_proxies"])
    assertEquals(false, prefs["network.proxy.detect_system_proxy_changes"])
  }

  @Test
  fun testMandatoryTorPreferencesImmutable() {
    val mandatory = NetworkHardening.getMandatoryTorRoutingPreferences(9050)
    assertEquals(1, mandatory["network.proxy.type"])
    assertEquals("127.0.0.1", mandatory["network.proxy.socks"])
    assertEquals(9050, mandatory["network.proxy.socks_port"])
    assertEquals(false, mandatory["network.proxy.failover_direct"])
    assertEquals(true, mandatory["network.proxy.socks5_remote_dns"])
    assertEquals(true, mandatory["network.proxy.socks_remote_dns"])
  }
}
