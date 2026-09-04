package com.remmi.browser.security

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.remmi.browser.engine.GeckoPreferenceController
import com.remmi.browser.engine.TabManager
import com.remmi.browser.util.CrashHandlerHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteTransitionRegressionTest {

  private lateinit var tabManager: TabManager
  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    tabManager = TabManager.getInstance()
    tabManager.closeAllTabs()
    CurrentTorRoute.clearRoute()
    GeckoPreferenceController.resetCache()
  }

  @After
  fun tearDown() {
    tabManager.closeAllTabs()
    CurrentTorRoute.clearRoute()
    GeckoPreferenceController.resetCache()
  }

  @Test
  fun testSwitchingTabsPreservesTabUrlAndNavigationState() {
    runBlocking {
      // Create initial Shield tab with specific URL and back history
      val shieldTab = tabManager.activeTab!!
      val originalUrl = "https://example.com/articles/privacy"
      tabManager.updateTab(shieldTab.id) {
        it.copy(
          url = originalUrl,
          title = "Privacy Article",
          canGoBack = true,
          canGoForward = false,
          profile = PrivacyProfile.SHIELD
        )
      }

      // Create a Ghost tab
      val ghostTab = tabManager.createTab(
        url = "https://duckduckgo.com",
        profile = PrivacyProfile.GHOST
      )
      tabManager.switchToTab(ghostTab.id)

      // Switch back to original Shield tab
      tabManager.switchToTab(shieldTab.id)
      val retrievedTab = tabManager.activeTab

      // Invariants: URL must not be wiped to about:blank, back history must be preserved
      assertEquals(shieldTab.id, retrievedTab?.id)
      assertEquals(originalUrl, retrievedTab?.url)
      assertNotEquals("about:blank", retrievedTab?.url)
      assertTrue("Back navigation capability must be preserved", retrievedTab?.canGoBack == true)
      assertEquals("Privacy Article", retrievedTab?.title)
    }
  }

  @Test
  fun testPrivacyProfileModeSwitchPreservesActiveTabContent() {
    runBlocking {
      val tab = tabManager.activeTab!!
      val pageUrl = "https://en.wikipedia.org/wiki/Tor_(network)"
      tabManager.updateTab(tab.id) {
        it.copy(
          url = pageUrl,
          title = "Tor Article",
          canGoBack = true,
          profile = PrivacyProfile.SHIELD
        )
      }

      // Simulate user toggling to Ghost mode on the active tab
      tabManager.updateTab(tab.id) {
        it.copy(profile = PrivacyProfile.GHOST)
      }

      val updatedTab = tabManager.getTab(tab.id)
      assertEquals("URL must be preserved across profile switch", pageUrl, updatedTab?.url)
      assertEquals("Title must be preserved across profile switch", "Tor Article", updatedTab?.title)
      assertTrue("canGoBack must be preserved across profile switch", updatedTab?.canGoBack == true)
      assertEquals(PrivacyProfile.GHOST, updatedTab?.profile)
      assertNotEquals("Tab must never reset to about:blank on profile switch", "about:blank", updatedTab?.url)
    }
  }

  @Test
  fun testCrashHandlerLifecycleDoesNotCrashOrBlock() {
    // Verify that onProcessStart and markCleanShutdown execute smoothly without throwing exceptions
    CrashHandlerHelper.onProcessStart(context)
    CrashHandlerHelper.markCleanShutdown(context)

    val prefs = context.getSharedPreferences(CrashHandlerHelper.PREFS_NAME, Context.MODE_PRIVATE)
    assertTrue("Previous run clean flag should be recorded", prefs.getBoolean(CrashHandlerHelper.KEY_PREVIOUS_RUN_CLEAN, false))
  }

  @Test
  fun testGeckoPreferenceControllerCacheReset() {
    // Verify resetCache operates cleanly
    GeckoPreferenceController.resetCache()
    // Verify controller instantiation is clean
    val controller = GeckoPreferenceController(null)
    assertEquals(0, GeckoPreferenceController.REQUIRED_PROXY_ROUTING.filter { it.isEmpty() }.size)
    assertTrue(GeckoPreferenceController.REQUIRED_PROXY_ROUTING.contains("network.proxy.type"))
  }
}
