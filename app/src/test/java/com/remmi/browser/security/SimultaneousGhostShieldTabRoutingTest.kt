package com.remmi.browser.security

import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.engine.TabManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimultaneousGhostShieldTabRoutingTest {

  private lateinit var tabManager: TabManager

  @Before
  fun setUp() {
    tabManager = TabManager.getInstance()
    tabManager.closeAllTabs()
    CurrentTorRoute.clearRoute()
  }

  @After
  fun tearDown() {
    tabManager.closeAllTabs()
    CurrentTorRoute.clearRoute()
  }

  @Test
  fun testSimultaneousGhostAndShieldTabsMaintainRouteInvariant() {
    runBlocking {
      // 1. Initial reset gives 1 blank tab; let's configure it
      val initialTab = tabManager.activeTab!!
      tabManager.updateTab(initialTab.id) { it.copy(url = "https://example.com", profile = PrivacyProfile.SHIELD) }

      assertFalse("Tor should not be active initially", CurrentTorRoute.isGhostActive)
      assertFalse("Tor should not be ready initially", CurrentTorRoute.isReady)

      // 2. Create a Ghost tab and activate Tor route
      val ghostTab = tabManager.createTab("https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion", profile = PrivacyProfile.GHOST)
      val gen = CurrentTorRoute.markStartingGhost()
      CurrentTorRoute.updateRoute(
        socksPort = 9050,
        isGhostActive = true,
        isVerified = true,
        exitIp = "185.220.101.5",
        generation = gen
      )
      CurrentTorRoute.setPhase(GhostRoutePhase.READY, gen)

      assertTrue("CurrentTorRoute must be active", CurrentTorRoute.isGhostActive)
      assertTrue("CurrentTorRoute must be verified", CurrentTorRoute.isVerified)
      assertTrue("CurrentTorRoute must be ready", CurrentTorRoute.isReady)

      // 3. Rapidly switch active tab to Shield tab
      tabManager.switchToTab(initialTab.id)

      // Verify Tor route is NOT torn down or cleared while Ghost tab exists
      assertTrue("Tor route must remain active when switching to Shield tab while Ghost tab exists", CurrentTorRoute.isGhostActive)
      assertEquals(9050, CurrentTorRoute.currentSocksPort)

      // 4. Verify .onion navigation fails for Shield tab and succeeds for Ghost tab
      val checkShieldOnion = NavigationSecurityAuthority.validateAndSanitizeNavigation(
        "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
        isGhost = false
      )
      assertEquals("Shield tab must block .onion navigation", NavigationDecision.BLOCK, checkShieldOnion.decision)

      val checkGhostOnion = NavigationSecurityAuthority.validateAndSanitizeNavigation(
        "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
        isGhost = true
      )
      assertEquals("Ghost tab must allow .onion navigation", NavigationDecision.ALLOW, checkGhostOnion.decision)
    }
  }

  @Test
  fun testClosingLastGhostTabEnforcesDirectClearnetSafety() {
    runBlocking {
      val initialTab = tabManager.activeTab!!
      tabManager.updateTab(initialTab.id) { it.copy(url = "https://example.com", profile = PrivacyProfile.SHIELD) }

      val ghostTab = tabManager.createTab("https://torproject.org", profile = PrivacyProfile.GHOST)

      val gen = CurrentTorRoute.markStartingGhost()
      CurrentTorRoute.updateRoute(
        socksPort = 9050,
        isGhostActive = true,
        isVerified = true,
        exitIp = "185.220.101.5",
        generation = gen
      )
      CurrentTorRoute.setPhase(GhostRoutePhase.READY, gen)

      // Close the ghost tab
      tabManager.closeTab(ghostTab.id)
      tabManager.switchToTab(initialTab.id)

      // Active tab is now Shield tab
      assertEquals(1, tabManager.tabs.value.size)
      assertEquals(initialTab.id, tabManager.activeTab?.id)
      assertEquals(PrivacyProfile.SHIELD, tabManager.activeTab?.profile)
    }
  }
}
