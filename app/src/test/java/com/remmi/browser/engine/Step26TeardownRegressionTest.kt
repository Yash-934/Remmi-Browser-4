package com.remmi.browser.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.storage.SessionTabEntity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Step26TeardownRegressionTest {
  private lateinit var context: Application

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext<Application>()
    org.mozilla.gecko.GeckoAppShell.setApplicationContext(context)
  }

  @Test
  fun testRestoreSavedTabsDoesNotClobberActiveBrowsingSession() {
    val tabManager = TabManager.getInstance()
    tabManager.resetToSingleBlankTab()
    
    // User starts navigating
    val activeTabId = tabManager.activeTab?.id ?: fail("No active tab")
    tabManager.updateTab(activeTabId as String) {
      it.copy(url = "https://duckduckgo.com/?q=ad+test+browser", title = "DuckDuckGo")
    }

    assertEquals("https://duckduckgo.com/?q=ad+test+browser", tabManager.activeTab?.url)

    // Attempted disk restore arrives later (e.g. ~3.9s into browsing)
    val staleDiskTabs = listOf(
      SessionTabEntity(
        id = "old-stale-id",
        url = "https://example.com",
        title = "Example",
        position = 0,
        timestamp = System.currentTimeMillis() - 10000,
        profile = "SHIELD",
        isDesktopMode = false,
        isReaderMode = false
      )
    )
    tabManager.restoreSavedTabs(staleDiskTabs)

    // Verify active session was preserved and not clobbered
    assertEquals("https://duckduckgo.com/?q=ad+test+browser", tabManager.activeTab?.url)
    assertEquals(activeTabId, tabManager.activeTab?.id)
    assertEquals(1, tabManager.tabs.value.size)
  }

  @Test
  fun testTabSwitchMaintainsActiveState() {
    val tabManager = TabManager.getInstance()
    tabManager.resetToSingleBlankTab()
    val tab1Id = tabManager.activeTab?.id ?: ""
    val tab2 = tabManager.createTab(url = "https://example.com")
    val tab2Id = tab2.id

    assertEquals(tab2Id, tabManager.activeTab?.id)
    assertEquals(2, tabManager.tabs.value.size)

    tabManager.switchTab(tab1Id)
    assertEquals(tab1Id, tabManager.activeTab?.id)
  }
}
