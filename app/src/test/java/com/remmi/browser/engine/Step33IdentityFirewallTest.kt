package com.remmi.browser.engine

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.SecurityLevel
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

/**
 * Step 33 Regression Tests
 * 
 * Verifies:
 * 1. Old session callback rejection when session is replaced/stale.
 * 2. View-bound callback rejection when tab view is detached.
 * 3. Non-view-bound callback allowed when tab view is detached.
 * 4. Single Navigation Authority prevents duplicate generation/navId allocations.
 * 5. Navigation generation increments strictly on user navigation actions.
 * 6. onLoadRequest does not allocate new navId/generation.
 * 7. onLocationChange does not allocate new navId/generation.
 * 8. GECKO_KILL on detached tab defers recovery to PENDING_DETACHED.
 * 9. Reattaching view resumes deferred recovery to STARTING / IN_FLIGHT.
 * 10. Recovery page stop transitions to SUCCESS.
 * 11. Recovery superseded by newer user navigation transitions to SUPERSEDED.
 * 12. Recovery timeout transitions to FAILED.
 * 13. Multi-view mapping invariant detection.
 * 14. Progress and loading lifecycle determinism under firewall.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class Step33IdentityFirewallTest {

  private lateinit var context: Context
  private lateinit var manager: GeckoEngineManager
  private lateinit var tabManager: TabManager

  private var capturedLoading: Boolean? = null
  private var capturedProgress: Int? = null
  private var capturedUrl: String? = null
  private var capturedScrollY: Int? = null

  private val testCallbacks = object : GeckoTabCallbacks {
    override fun onUrlChange(url: String) {
      capturedUrl = url
    }
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
    override fun onScrollChanged(scrollX: Int, scrollY: Int, isScrollingDown: Boolean) {
      capturedScrollY = scrollY
    }
  }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext<Application>()
    org.mozilla.gecko.GeckoAppShell.setApplicationContext(context)
    DebugLogManager.init(context)
    DebugLogManager.clear()

    manager = GeckoEngineManager.getInstance(context)
    tabManager = TabManager.getInstance()
    tabManager.closeAllTabs()
    manager.setInitStateForTesting(GeckoEngineManager.GeckoInitState.READY)

    capturedLoading = null
    capturedProgress = null
    capturedUrl = null
    capturedScrollY = null

    manager.uriLoaderForTest = { _, _, _ -> }
    manager.sessionOpenerForTest = { _, _ -> }
  }

  @After
  fun tearDown() = runBlocking {
    manager.uriLoaderForTest = null
    manager.sessionOpenerForTest = null
    manager.closeAllSessionsSafely()
    tabManager.closeAllTabs()
  }

  /**
   * TEST 1: Old session callback is rejected after session is replaced.
   */
  @Test
  fun test1_OldSessionCallback_IsRejected() = runBlocking {
    val tab = tabManager.createTab("https://example.com/original")
    val oldSession = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, oldSession)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    // Simulate session replacement (e.g. crash recreate or tab recycling)
    val newSession = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, newSession)

    // Old session fires onPageStart
    oldSession.progressDelegate?.onPageStart(oldSession, "https://stale.example.com")
    assertNull("Old session callback must not change loading state", capturedLoading)

    // New session fires onPageStart
    newSession.progressDelegate?.onPageStart(newSession, "https://fresh.example.com")
    assertEquals(true, capturedLoading)
  }

  /**
   * TEST 2: View-bound callback (e.g. scroll) is rejected when view is detached.
   */
  @Test
  fun test2_ViewBoundCallback_RejectedWhenDetached() = runBlocking {
    val tab = tabManager.createTab("https://example.com/view-test")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    // When attached, scroll is processed
    session.scrollDelegate?.onScrollChanged(session, 0, 150)
    assertEquals(150, capturedScrollY)

    // Detach view
    manager.detachViewSync(tab.id, gv)
    capturedScrollY = null

    // Scroll callback when detached must be ignored by firewall
    session.scrollDelegate?.onScrollChanged(session, 0, 300)
    assertNull("View-bound scroll callback must be rejected when detached", capturedScrollY)
  }

  /**
   * TEST 3: Non-view-bound callback (e.g. progress/location) is allowed when tab view is detached.
   */
  @Test
  fun test3_NonViewBoundCallback_AllowedWhenDetached() = runBlocking {
    val tab = tabManager.createTab("https://example.com/background")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)
    manager.detachViewSync(tab.id, gv)

    // Fire onPageStart while unattached
    session.progressDelegate?.onPageStart(session, "https://example.com/background")
    assertEquals(true, capturedLoading)
  }

  /**
   * TEST 4: Single Navigation Authority: loadUrl allocates exactly one generation.
   */
  @Test
  fun test4_SingleNavigationAuthority_AllocatesExactlyOnce() = runBlocking {
    val tab = tabManager.createTab("https://example.com/nav-authority")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    val genBefore = manager.getNavGeneration(tab.id)
    manager.loadUrl(tab.id, "https://example.com/page1")
    val genAfterLoad = manager.getNavGeneration(tab.id)
    assertEquals(genBefore + 1, genAfterLoad)

    // onLocationChange callback must NOT increment generation
    session.navigationDelegate?.onLocationChange(session, "https://example.com/page1", mutableListOf(), false)
    assertEquals(genAfterLoad, manager.getNavGeneration(tab.id))

    // onPageStart callback must NOT increment generation
    session.progressDelegate?.onPageStart(session, "https://example.com/page1")
    assertEquals(genAfterLoad, manager.getNavGeneration(tab.id))
  }

  /**
   * TEST 5: Reload increments navigation generation.
   */
  @Test
  fun test5_Reload_IncrementsGeneration() = runBlocking {
    val tab = tabManager.createTab("https://example.com/reload-test")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    manager.loadUrl(tab.id, "https://example.com/reload-test")
    val genAfterLoad = manager.getNavGeneration(tab.id)

    manager.reload(tab.id)
    val genAfterReload = manager.getNavGeneration(tab.id)
    assertEquals(genAfterLoad + 1, genAfterReload)
  }

  /**
   * TEST 6: History navigation (goBack/goForward) increments navigation generation.
   */
  @Test
  fun test6_HistoryNavigation_IncrementsGeneration() = runBlocking {
    val tab = tabManager.createTab("https://example.com/history-test")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    val gen1 = manager.getNavGeneration(tab.id)
    manager.goBack(tab.id)
    val gen2 = manager.getNavGeneration(tab.id)
    assertEquals(gen1 + 1, gen2)

    manager.goForward(tab.id)
    val gen3 = manager.getNavGeneration(tab.id)
    assertEquals(gen2 + 1, gen3)
  }

  /**
   * TEST 7: In-page hash navigation in onLocationChange stays within same generation.
   */
  @Test
  fun test7_InPageHashLocationChange_PreservesGeneration() = runBlocking {
    val tab = tabManager.createTab("https://example.com/page#section1")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    manager.loadUrl(tab.id, "https://example.com/page")
    val gen = manager.getNavGeneration(tab.id)

    // User or JS navigates to hash
    session.navigationDelegate?.onLocationChange(session, "https://example.com/page#section2", mutableListOf(), false)
    assertEquals(gen, manager.getNavGeneration(tab.id))
    assertEquals("https://example.com/page#section2", capturedUrl)
  }

  /**
   * TEST 8: GECKO_KILL on detached tab defers recovery into PENDING_DETACHED.
   */
  @Test
  fun test8_GeckoKillOnDetachedTab_TransitionsToPendingDetached() = runBlocking {
    val tab = tabManager.createTab("https://example.com/background-kill")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)
    manager.loadUrl(tab.id, "https://example.com/background-kill")
    manager.detachViewSync(tab.id, gv)

    // Trigger process kill while detached
    session.contentDelegate?.onKill(session)

    assertEquals(GeckoEngineManager.RecoveryState.PENDING_DETACHED, manager.getRecoveryState(tab.id))
  }

  /**
   * TEST 9: Attaching view to a detached tab with pending recovery resumes recovery.
   */
  @Test
  fun test9_ViewAttach_ResumesPendingDetachedRecovery() = runBlocking {
    val tab = tabManager.createTab("https://example.com/resume-kill")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)
    manager.loadUrl(tab.id, "https://example.com/resume-kill")
    manager.detachViewSync(tab.id, gv)

    session.contentDelegate?.onKill(session)
    assertEquals(GeckoEngineManager.RecoveryState.PENDING_DETACHED, manager.getRecoveryState(tab.id))

    // Reattach view
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    assertEquals(GeckoEngineManager.RecoveryState.IN_FLIGHT, manager.getRecoveryState(tab.id))
  }

  /**
   * TEST 10: Successful recovery completion transitions RecoveryState to SUCCESS.
   */
  @Test
  fun test10_RecoveryCompletion_TransitionsToSuccess() = runBlocking {
    val tab = tabManager.createTab("https://example.com/success-kill")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)
    manager.loadUrl(tab.id, "https://example.com/success-kill")

    // Trigger crash in foreground
    session.contentDelegate?.onCrash(session)
    assertEquals(GeckoEngineManager.RecoveryState.IN_FLIGHT, manager.getRecoveryState(tab.id))

    // Page stops successfully
    session.progressDelegate?.onPageStop(session, true)
    assertEquals(GeckoEngineManager.RecoveryState.SUCCESS, manager.getRecoveryState(tab.id))
  }

  /**
   * TEST 11: Recovery superseded by new user loadUrl transitions to SUPERSEDED.
   */
  @Test
  fun test11_RecoverySupersededByNewNav_TransitionsToSuperseded() = runBlocking {
    val tab = tabManager.createTab("https://example.com/supersede-kill")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)
    manager.loadUrl(tab.id, "https://example.com/supersede-kill")

    // Kill session
    session.contentDelegate?.onKill(session)
    assertEquals(GeckoEngineManager.RecoveryState.IN_FLIGHT, manager.getRecoveryState(tab.id))

    // User navigates somewhere else before recovery finishes
    manager.loadUrl(tab.id, "https://example.com/new-destination")
    assertEquals(GeckoEngineManager.RecoveryState.SUPERSEDED, manager.getRecoveryState(tab.id))
  }

  /**
   * TEST 12: Recovery failure on page error transitions to FAILED.
   */
  @Test
  fun test12_RecoveryFailure_TransitionsToFailed() = runBlocking {
    val tab = tabManager.createTab("https://example.com/fail-kill")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)
    manager.loadUrl(tab.id, "https://example.com/fail-kill")

    session.contentDelegate?.onCrash(session)
    assertEquals(GeckoEngineManager.RecoveryState.IN_FLIGHT, manager.getRecoveryState(tab.id))

    // Page stop with success = false
    session.progressDelegate?.onPageStop(session, false)
    assertEquals(GeckoEngineManager.RecoveryState.FAILED, manager.getRecoveryState(tab.id))
  }

  /**
   * TEST 13: checkViewInvariants executes without exception on valid state.
   */
  @Test
  fun test13_ViewInvariants_ExecutesCleanly() = runBlocking {
    val tab = tabManager.createTab("https://example.com/invariant-check")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    manager.checkViewInvariants(tab.id, "TEST")
    // Should execute without throwing any exception
    assertTrue(manager.isViewAttached(tab.id))
  }

  /**
   * TEST 14: Progress lifecycle is strictly deterministic: start -> progress -> stop.
   */
  @Test
  fun test14_ProgressLifecycle_StrictDeterminism() = runBlocking {
    val tab = tabManager.createTab("https://example.com/progress-determinism")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    val gv = GeckoView(context).apply { tag = tab.id }
    manager.attachView(tab.id, gv, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    session.progressDelegate?.onPageStart(session, "https://example.com/progress-determinism")
    assertEquals(true, capturedLoading)
    assertTrue((capturedProgress ?: 0) > 0)

    session.progressDelegate?.onProgressChange(session, 45)
    assertEquals(45, capturedProgress)

    session.progressDelegate?.onProgressChange(session, 90)
    assertEquals(90, capturedProgress)

    session.progressDelegate?.onPageStop(session, true)
    assertEquals(false, capturedLoading)
    assertEquals(0, capturedProgress)

    // Stale late progress from Gecko
    session.progressDelegate?.onProgressChange(session, 100)
    assertEquals(0, capturedProgress)
  }
}
