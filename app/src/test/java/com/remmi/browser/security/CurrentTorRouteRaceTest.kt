package com.remmi.browser.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTorRouteRaceTest {

  @Test
  fun testRaceConditions() {
    // TEST 1: Tor succeeds
    val gen1 = CurrentTorRoute.markStartingGhost()
    val updated1 = CurrentTorRoute.commitReadyRoute(
      socksPort = 9050,
      exitIp = "1.2.3.4",
      generation = gen1
    )
    assertTrue(updated1)
    assertEquals(9050, CurrentTorRoute.currentSocksPort)
    assertTrue(CurrentTorRoute.isReady)

    // TEST 2: Newer generation starts
    val gen2 = CurrentTorRoute.markStartingGhost()
    assertEquals(GhostRoutePhase.STARTING_TOR, CurrentTorRoute.currentPhase)
    assertFalse(CurrentTorRoute.isReady) // Not ready until verified & committed

    val updated2 = CurrentTorRoute.commitReadyRoute(
      socksPort = 9150,
      exitIp = "1.2.3.4",
      generation = gen2
    )
    assertTrue(updated2)
    assertEquals(9150, CurrentTorRoute.currentSocksPort)
    assertTrue(CurrentTorRoute.isReady)

    // TEST 3: Stale generation update rejected
    val staleUpdate = CurrentTorRoute.commitReadyRoute(
      socksPort = 9050,
      exitIp = "1.2.3.4",
      generation = gen1
    )
    assertFalse(staleUpdate) // Stale generation cannot overwrite
    assertEquals(9150, CurrentTorRoute.currentSocksPort)
    assertEquals(gen2, CurrentTorRoute.currentGeneration)
    assertTrue(CurrentTorRoute.isReady)

    // TEST 4: Null socks port handling
    val gen3 = CurrentTorRoute.markStartingGhost()
    CurrentTorRoute.updateRoute(
      socksPort = null,
      isGhostActive = true,
      isVerified = false,
      generation = gen3
    )
    assertNull(CurrentTorRoute.currentSocksPort)
    assertFalse(CurrentTorRoute.isReady)
  }

  @Test
  fun staleGenerationCannotOverwriteNewRoute() {
    val oldGen = CurrentTorRoute.markStartingGhost()
    val newGen = CurrentTorRoute.markStartingGhost()

    assertFalse(
      CurrentTorRoute.updateRoute(
        socksPort = 9050,
        isGhostActive = true,
        isVerified = true,
        generation = oldGen
      )
    )

    assertEquals(
      newGen,
      CurrentTorRoute.currentGeneration
    )
  }

  @Test
  fun phaseProgressionToReadyRequiresExplicitCommit() {
    val gen = CurrentTorRoute.markStartingGhost()
    assertEquals(GhostRoutePhase.STARTING_TOR, CurrentTorRoute.currentPhase)
    assertFalse(CurrentTorRoute.isReady)

    CurrentTorRoute.setPhase(GhostRoutePhase.VERIFYING_TOR, gen)
    assertEquals(GhostRoutePhase.VERIFYING_TOR, CurrentTorRoute.currentPhase)
    assertFalse(CurrentTorRoute.isReady)

    CurrentTorRoute.setPhase(GhostRoutePhase.APPLYING_GECKO, gen)
    assertEquals(GhostRoutePhase.APPLYING_GECKO, CurrentTorRoute.currentPhase)
    assertFalse(CurrentTorRoute.isReady)

    CurrentTorRoute.setPhase(GhostRoutePhase.VERIFYING_GECKO, gen)
    assertEquals(GhostRoutePhase.VERIFYING_GECKO, CurrentTorRoute.currentPhase)
    assertFalse(CurrentTorRoute.isReady)

    val committed = CurrentTorRoute.updateRoute(
      socksPort = 9050,
      isGhostActive = true,
      isVerified = true,
      exitIp = "185.220.101.5",
      generation = gen
    )
    assertTrue(committed)
    CurrentTorRoute.setPhase(GhostRoutePhase.READY, gen)
    assertEquals(GhostRoutePhase.READY, CurrentTorRoute.currentPhase)
    assertTrue(CurrentTorRoute.isReady)
  }
}
