package com.remmi.browser.engine

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.adblock.AdblockBridge
import com.remmi.adblock.BlockExtension
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class Step29GeckoKillForensicTest {

  private lateinit var context: Context
  private lateinit var manager: GeckoEngineManager
  private lateinit var tabManager: TabManager
  private lateinit var bridge: AdblockBridge
  private lateinit var blockExt: BlockExtension

  private val dummyCallbacks = object : GeckoTabCallbacks {
    override fun onUrlChange(url: String) {}
    override fun onTitleChange(title: String) {}
    override fun onProgressChange(progress: Int) {}
    override fun onLoadingChange(isLoading: Boolean) {}
    override fun onSecurityChange(isSecure: Boolean) {}
    override fun onNavStateChange(canGoBack: Boolean, canGoForward: Boolean) {}
    override fun onTrackerBlocked(url: String, type: String) {}
  }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext<Application>()
    org.mozilla.gecko.GeckoAppShell.setApplicationContext(context)
    DebugLogManager.init(context)
    DebugLogManager.clear()

    bridge = AdblockBridge.getInstance()
    bridge.diagnosticBypassForTesting = false
    blockExt = BlockExtension.getInstance(bridge)

    manager = GeckoEngineManager.getInstance(context)
    tabManager = TabManager.getInstance()
    tabManager.closeAllTabs()
    manager.setInitStateForTesting(GeckoEngineManager.GeckoInitState.READY)
    manager.uriLoaderForTest = { _, _, _ -> }
  }

  @After
  fun tearDown() = runBlocking {
    bridge.diagnosticBypassForTesting = false
    manager.uriLoaderForTest = null
    manager.sessionOpenerForTest = null
    manager.closeAllSessionsSafely()
    tabManager.closeAllTabs()
  }

  /**
   * Regression Test 1:
   * A healthy GeckoView is NOT detached or released while its content process is active and valid.
   */
  @Test
  fun testHealthyGeckoViewIsNotDetachedWhileContentProcessIsValid() = runBlocking {
    val tab = tabManager.createTab("https://news.ycombinator.com/")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks
    )

    assertTrue("View must be attached for healthy tab", manager.isViewAttached(tabId))
    assertEquals("GeckoView tag must match tabId", tabId, geckoView.tag)
    assertSame("Attached session must match active session", session, manager.getSessionForTest(tabId))

    // Perform standard navigation
    val loaded = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loaded.add(url) }
    manager.loadUrl(tabId, "https://news.ycombinator.com/best")

    // Assert view remains attached and valid
    assertTrue("View must remain attached after loadUrl", manager.isViewAttached(tabId))
    assertEquals("https://news.ycombinator.com/best", manager.getLastDispatchedUrl(tabId))
    assertFalse("No pending recovery should exist for healthy session", manager.hasPendingContentRecovery(tabId))
    assertFalse("No active recovery should be in flight for healthy session", manager.hasActiveRecovery(tabId))
  }

  /**
   * Regression Test 2:
   * Recovery never enters a repeated self-triggered GECKO_KILL loop.
   */
  @Test
  fun testRecoveryDoesNotSelfTriggerKillLoop() = runBlocking {
    val tab = tabManager.createTab("https://adblock-tester.com/")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks
    )

    val reloadCount = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> reloadCount.add(url) }
    manager.loadUrl(tabId, "https://adblock-tester.com/")
    reloadCount.clear()

    // 1st kill: must trigger recovery
    session.contentDelegate?.onKill(session)
    assertEquals("First kill on generation must trigger exactly 1 recovery reload", 1, reloadCount.size)

    // 2nd consecutive kill on SAME generation: MUST BE SUPPRESSED to prevent infinite loop
    reloadCount.clear()
    session.contentDelegate?.onKill(session)
    assertEquals("Consecutive kill on same generation must be suppressed to avoid loop", 0, reloadCount.size)

    val logs = DebugLogManager.logs.value
    assertTrue("Must log CONTENT_RECOVERY_SUPPRESSED with reason=max_attempts_exceeded",
      logs.any { it.contains("CONTENT_RECOVERY_SUPPRESSED") && it.contains("max_attempts_exceeded") }
    )
  }

  /**
   * Comparative Test 3:
   * Compare Case A (normal page), Case B (adblock-tester), and Case C (adblock diagnostic bypass).
   */
  @Test
  fun testComparativeNavigationMatrix() = runBlocking {
    // Case A: Normal page navigation with adblock active
    bridge.diagnosticBypassForTesting = false
    val decA = bridge.evaluateDecision(
      url = "https://news.ycombinator.com/item?id=123",
      sourceUrl = "https://news.ycombinator.com/",
      resourceType = "script"
    )
    assertFalse("Normal first-party script is allowed in Case A", decA.blocked)

    // Case B: Adblock test page with adblock active
    val decB = bridge.evaluateDecision(
      url = "https://google-analytics.com/analytics.js",
      sourceUrl = "https://adblock-tester.com/",
      resourceType = "script"
    )
    assertTrue("Tracker script is blocked in Case B", decB.blocked)

    // Case C: Same adblock test page with adblock diagnostic bypass enabled
    bridge.diagnosticBypassForTesting = true
    val decC = bridge.evaluateDecision(
      url = "https://google-analytics.com/analytics.js",
      sourceUrl = "https://adblock-tester.com/",
      resourceType = "script"
    )
    assertFalse("Tracker script is bypassed in Case C for diagnostic profiling", decC.blocked)
    assertEquals("diagnostic_bypass", decC.ruleId)

    // Restore production filtering
    bridge.diagnosticBypassForTesting = false
  }
}
