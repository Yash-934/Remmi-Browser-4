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
class Step30GeckoKillIsolationTest {

  private lateinit var context: Context
  private lateinit var manager: GeckoEngineManager
  private lateinit var tabManager: TabManager
  private lateinit var bridge: AdblockBridge
  private lateinit var blockExt: BlockExtension

  private var capturedLoading: Boolean? = null
  private var capturedProgress: Int? = null

  private val testCallbacks = object : GeckoTabCallbacks {
    override fun onUrlChange(url: String) {}
    override fun onTitleChange(title: String) {}
    override fun onProgressChange(progress: Int) {
      capturedProgress = progress
    }
    override fun onLoadingChange(isLoading: Boolean) {
      capturedLoading = isLoading
    }
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
    capturedLoading = null
    capturedProgress = null
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
   * Test 1: Distinguish Gecko termination reasons:
   * onCrash -> CONTENT_CRASH
   * onKill -> CONTENT_KILL (GECKO_KILL / content process exit)
   */
  @Test
  fun testTerminationReasonClassification() = runBlocking {
    val tab = tabManager.createTab("https://example.com/test")
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
      callbacks = testCallbacks
    )

    // Trigger onKill
    session.contentDelegate?.onKill(session)
    val logs = DebugLogManager.logs.value
    assertTrue("Must log CONTENT_KILL event for onKill",
      logs.any { it.contains("[FORENSIC][CONTENT_KILL]") }
    )
    assertTrue("Must log CONTENT_PROCESS_EVENT with event=KILL and reason=GECKO_KILL",
      logs.any { it.contains("[FORENSIC][CONTENT_PROCESS_EVENT]") && it.contains("event=KILL") && it.contains("reason=GECKO_KILL") }
    )

    // Trigger onCrash
    session.contentDelegate?.onCrash(session)
    val logsAfterCrash = DebugLogManager.logs.value
    assertTrue("Must log CONTENT_CRASH event for onCrash",
      logsAfterCrash.any { it.contains("[FORENSIC][CONTENT_CRASH]") }
    )
    assertTrue("Must log CONTENT_PROCESS_EVENT with event=CRASH and reason=GECKO_CRASH",
      logsAfterCrash.any { it.contains("[FORENSIC][CONTENT_PROCESS_EVENT]") && it.contains("event=CRASH") && it.contains("reason=GECKO_CRASH") }
    )
  }

  /**
   * Test 2: Progress bar lifecycle & loading-state transitions:
   * onPageStart -> onLoadingChange(true), onProgressChange(10)
   * onPageStop -> onLoadingChange(false), onProgressChange(100)
   */
  @Test
  fun testProgressBarLifecycleTransitions() = runBlocking {
    val tab = tabManager.createTab("https://example.com/page")
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
      callbacks = testCallbacks
    )

    // Simulate navigation start
    session.progressDelegate?.onPageStart(session, "https://example.com/page")
    assertEquals(true, capturedLoading)
    assertEquals(10, capturedProgress)

    // Simulate progress updates
    session.progressDelegate?.onProgressChange(session, 50)
    assertEquals(50, capturedProgress)

    session.progressDelegate?.onProgressChange(session, 80)
    assertEquals(80, capturedProgress)

    // Simulate navigation stop
    session.progressDelegate?.onPageStop(session, true)
    assertEquals(false, capturedLoading)
    assertEquals(0, capturedProgress)
  }

  /**
   * Test 3: Controlled 4-Way Navigation Matrix (Case A, B, C, D)
   * Case A: Normal Page + Adblock ON
   * Case B: Stress / Adblock Test Page + Adblock ON
   * Case C: Stress Page + Diagnostic Bypass
   * Case D: Stress Page + Recovery Suppression
   */
  @Test
  fun testControlledFourWayMatrix() = runBlocking {
    val normalUrl = "https://news.ycombinator.com/"
    val stressUrl = "https://adblock-tester.com/"

    // Case A: Normal page with Adblock ON
    bridge.diagnosticBypassForTesting = false
    val tabA = tabManager.createTab(normalUrl)
    val sessionA = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tabA.id, sessionA)
    manager.attachView(
      tabId = tabA.id,
      geckoView = GeckoView(context).apply { tag = tabA.id },
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = testCallbacks
    )
    
    val decisionA = bridge.evaluateDecision("https://news.ycombinator.com/yc.js", normalUrl, "script")
    assertFalse("Case A: First-party script allowed", decisionA.blocked)
    assertTrue("Case A: Session active", manager.isViewAttached(tabA.id))

    // Case B: Stress page with Adblock ON
    val tabB = tabManager.createTab(stressUrl)
    val sessionB = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tabB.id, sessionB)
    manager.attachView(
      tabId = tabB.id,
      geckoView = GeckoView(context).apply { tag = tabB.id },
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = testCallbacks
    )

    val decisionB = bridge.evaluateDecision("https://google-analytics.com/ga.js", stressUrl, "script")
    assertTrue("Case B: Tracker blocked", decisionB.blocked)

    // Case C: Same stress page with diagnostic bypass
    bridge.diagnosticBypassForTesting = true
    val decisionC = bridge.evaluateDecision("https://google-analytics.com/ga.js", stressUrl, "script")
    assertFalse("Case C: Tracker bypassed for diagnostic", decisionC.blocked)
    assertEquals("diagnostic_bypass", decisionC.ruleId)
    bridge.diagnosticBypassForTesting = false

    // Case D: Recovery suppression on second kill
    val reloadCount = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> reloadCount.add(url) }
    sessionB.contentDelegate?.onKill(sessionB)
    assertEquals("First kill recovers", 1, reloadCount.size)
    reloadCount.clear()

    // Second kill in same generation suppressed
    sessionB.contentDelegate?.onKill(sessionB)
    assertEquals("Second kill suppressed", 0, reloadCount.size)
  }

  /**
   * Test 4: Recovery does not produce blank page when target URL matches
   */
  @Test
  fun testRecoveryDoesNotProduceBlankPageOnTargetMatch() = runBlocking {
    val tab = tabManager.createTab("https://news.ycombinator.com/")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = testCallbacks
    )

    val reloaded = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> reloaded.add(url) }
    manager.loadUrl(tabId, "https://news.ycombinator.com/")
    reloaded.clear()

    // Trigger Kill
    session.contentDelegate?.onKill(session)
    assertEquals(1, reloaded.size)
    assertEquals("https://news.ycombinator.com/", reloaded.first())

    // Simulate transient about:blank location and stop during recovery - should NOT clear loading state
    session.navigationDelegate?.onLocationChange(session, "about:blank", mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true) // about:blank
    assertTrue("Active recovery exists before target load", manager.hasActiveRecovery(tabId))

    // Simulate target URL location, start and stop
    session.navigationDelegate?.onLocationChange(session, "https://news.ycombinator.com/", mutableListOf(), false)
    session.progressDelegate?.onPageStart(session, "https://news.ycombinator.com/")
    session.progressDelegate?.onPageStop(session, true)
    assertFalse("Active recovery cleared on target match", manager.hasActiveRecovery(tabId))
    assertEquals(false, capturedLoading)
  }
}
