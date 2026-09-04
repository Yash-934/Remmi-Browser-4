package com.remmi.browser.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorSecurityStateMachineTest {

  @Test
  fun testTorStateTransitions() {
    val offState: TorManager.TorState = TorManager.TorState.OFF
    assertFalse(offState.isConnecting)
    assertEquals(0, offState.progress)
    assertEquals("Offline", offState.statusText)

    val startingServiceState: TorManager.TorState = TorManager.TorState.STARTING_SERVICE
    assertTrue(startingServiceState.isConnecting)
    assertEquals(10, startingServiceState.progress)

    val fgsConfirmedState: TorManager.TorState = TorManager.TorState.SERVICE_FOREGROUND_CONFIRMED
    assertTrue(fgsConfirmedState.isConnecting)
    assertEquals(20, fgsConfirmedState.progress)

    val bootstrapState: TorManager.TorState = TorManager.TorState.TOR_BOOTSTRAPPING(55, "Bootstrapping circuit 55%")
    assertTrue(bootstrapState.isConnecting)
    assertEquals(55, bootstrapState.progress)

    val circuitEstState: TorManager.TorState = TorManager.TorState.TOR_CIRCUIT_ESTABLISHED("TOR-1234ABCD")
    assertTrue(circuitEstState.isConnecting)
    assertEquals(70, circuitEstState.progress)

    val socksDiscState: TorManager.TorState = TorManager.TorState.SOCKS_DISCOVERY(9050)
    assertTrue(socksDiscState.isConnecting)
    assertEquals(80, socksDiscState.progress)

    val socks5VerifyState: TorManager.TorState = TorManager.TorState.SOCKS5_VERIFY(9050)
    assertTrue(socks5VerifyState.isConnecting)
    assertEquals(85, socks5VerifyState.progress)

    val remoteVerifyState: TorManager.TorState = TorManager.TorState.REMOTE_TOR_VERIFY(9050, 1)
    assertTrue(remoteVerifyState.isConnecting)
    assertEquals(95, remoteVerifyState.progress)

    val circuit = TorCircuit(
      circuitId = "TOR-TEST1234",
      socksPort = 9050,
      isVerifiedTor = true,
      verifiedExitIp = "185.220.101.5",
      isRealCircuitAvailable = true,
      latencyMs = 120L
    )
    val readyState: TorManager.TorState = TorManager.TorState.READY(9050, circuit)
    assertFalse(readyState.isConnecting)
    assertEquals(100, readyState.progress)
    assertTrue(readyState.statusText.contains("Connected & Verified"))

    val failedState: TorManager.TorState = TorManager.TorState.FAILED(
      TorErrorCategory.TOR_VERIFICATION_FAILED,
      "Verification failed"
    )
    assertFalse(failedState.isConnecting)
    assertEquals("Verification failed", failedState.statusText)
  }

  @Test
  fun testTorPreferencesDynamicPort() {
    val dynamicPort = 9150
    val prefs = NetworkHardening.getTorPreferences(dynamicPort)
    assertEquals(1, prefs["network.proxy.type"])
    assertEquals("127.0.0.1", prefs["network.proxy.socks"])
    assertEquals(9150, prefs["network.proxy.socks_port"])
    assertEquals(5, prefs["network.proxy.socks_version"])
    assertEquals(true, prefs["network.proxy.socks_remote_dns"])
    assertEquals(false, prefs["network.proxy.failover_direct"])
  }

  @Test
  fun testTorPreferencesFailClosed() {
    val prefs = NetworkHardening.getTorPreferences(9050)
    assertEquals(1, prefs["network.proxy.type"])
    assertEquals("127.0.0.1", prefs["network.proxy.socks"])
    assertEquals(9050, prefs["network.proxy.socks_port"])
    assertEquals(5, prefs["network.proxy.socks_version"])
    assertEquals(true, prefs["network.proxy.socks_remote_dns"])
    assertEquals(false, prefs["network.proxy.failover_direct"]) // Strict fail-closed
    assertEquals(false, prefs["media.peerconnection.enabled"]) // WebRTC blocked
  }

  @Test
  fun testShieldPreferencesDirect() {
    val prefs = NetworkHardening.getShieldPreferences()
    assertEquals(0, prefs["network.proxy.type"])
    assertEquals("", prefs["network.proxy.socks"])
    assertEquals(false, prefs["media.peerconnection.enabled"])
  }

  @Test
  fun testGhostReadyInvariantRequiresAllPhases() {
    // 1. Initial State: Shield
    CurrentTorRoute.clearRoute()
    assertEquals(GhostRoutePhase.SHIELD, CurrentTorRoute.currentPhase)
    assertFalse(CurrentTorRoute.isReady)

    // 2. Starting Tor: Not ready
    val gen = CurrentTorRoute.markStartingGhost()
    assertEquals(GhostRoutePhase.STARTING_TOR, CurrentTorRoute.currentPhase)
    assertFalse(CurrentTorRoute.isReady)

    // 3. Verifying Tor: Not ready
    CurrentTorRoute.setPhase(GhostRoutePhase.VERIFYING_TOR, gen)
    assertFalse(CurrentTorRoute.isReady)

    // 4. Applying Gecko: Not ready
    CurrentTorRoute.setPhase(GhostRoutePhase.APPLYING_GECKO, gen)
    assertFalse(CurrentTorRoute.isReady)

    // 5. Verifying Gecko: Not ready
    CurrentTorRoute.setPhase(GhostRoutePhase.VERIFYING_GECKO, gen)
    assertFalse(CurrentTorRoute.isReady)

    // 6. Only when committed and phase is READY does isReady become true
    CurrentTorRoute.updateRoute(
      socksPort = 9050,
      isGhostActive = true,
      isVerified = true,
      exitIp = "192.42.116.16",
      generation = gen
    )
    CurrentTorRoute.setPhase(GhostRoutePhase.READY, gen)
    assertTrue(CurrentTorRoute.isReady)
  }
}
