package com.remmi.browser.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkRouteAuthorityTest {

  @Test
  fun testFailClosedWhenGhostActiveAndNoSocksPort() {
    CurrentTorRoute.clearRoute()
    val gen = CurrentTorRoute.markStartingGhost()
    // Ghost is active but socksPort is null
    assertEquals(GhostRoutePhase.STARTING_TOR, CurrentTorRoute.currentPhase)
    assertNull(CurrentTorRoute.currentSocksPort)
    assertFalse(CurrentTorRoute.isReady)

    // Invariant: createHttpClient MUST throw in Ghost mode if Tor is not verified and ready
    assertThrows(IllegalStateException::class.java) {
      NetworkRouteAuthority.createHttpClient(isGhost = true)
    }

    // createHttpClientOrNull should return null without crashing
    val nullClient = NetworkRouteAuthority.createHttpClientOrNull(isGhost = true)
    assertNull(nullClient)
  }

  @Test
  fun testFailClosedWhenGhostActiveAndSocksPortPresentButUnverified() {
    CurrentTorRoute.clearRoute()
    val gen = CurrentTorRoute.markStartingGhost()
    CurrentTorRoute.updateRoute(
      socksPort = 9050,
      isGhostActive = true,
      isVerified = false, // UNVERIFIED
      exitIp = null,
      generation = gen
    )

    assertFalse(CurrentTorRoute.isReady)

    assertThrows(IllegalStateException::class.java) {
      NetworkRouteAuthority.createHttpClient(isGhost = true)
    }
  }

  @Test
  fun testFailClosedWhenFailoverDirectIsTrue() {
    CurrentTorRoute.clearRoute()
    val gen = CurrentTorRoute.markStartingGhost()
    CurrentTorRoute.updateRoute(
      socksPort = 9050,
      isGhostActive = true,
      isVerified = true,
      failoverDirect = true, // FORBIDDEN IN GHOST
      exitIp = "185.220.101.5",
      generation = gen
    )

    assertFalse(CurrentTorRoute.isReady)

    assertThrows(IllegalStateException::class.java) {
      NetworkRouteAuthority.createHttpClient(isGhost = true)
    }
  }

  @Test
  fun testMarkStartingGhostClearsStaleState() {
    val genOld = CurrentTorRoute.markStartingGhost()
    CurrentTorRoute.updateRoute(
      socksPort = 9050,
      isGhostActive = true,
      isVerified = true,
      exitIp = "185.220.101.5",
      generation = genOld
    )
    CurrentTorRoute.setPhase(GhostRoutePhase.READY, genOld)
    assertTrue(CurrentTorRoute.isReady)

    // Mode switch begins
    val gen = CurrentTorRoute.markStartingGhost()
    assertTrue(gen > genOld)
    assertNull(CurrentTorRoute.currentSocksPort)
    assertFalse(CurrentTorRoute.isReady)

    assertThrows(IllegalStateException::class.java) {
      NetworkRouteAuthority.createHttpClient(isGhost = true)
    }
  }

  @Test
  fun testClearnetClientCreationSucceeds() {
    CurrentTorRoute.clearRoute()

    val client = NetworkRouteAuthority.createHttpClient(isGhost = false)
    assertNotNull(client)
    assertNull(client.proxy)
  }

  @Test
  fun testGhostClientConfiguresSocksProxy() {
    val gen = CurrentTorRoute.markStartingGhost()
    CurrentTorRoute.updateRoute(
      socksPort = 9050,
      isGhostActive = true,
      isVerified = true,
      exitIp = "185.220.101.5",
      generation = gen
    )
    CurrentTorRoute.setPhase(GhostRoutePhase.READY, gen)

    val client = NetworkRouteAuthority.createHttpClient(isGhost = true)
    assertNotNull(client)
    assertNotNull(client.proxy)
    assertEquals(java.net.Proxy.Type.SOCKS, client.proxy?.type())

    CurrentTorRoute.clearRoute()
  }
}
