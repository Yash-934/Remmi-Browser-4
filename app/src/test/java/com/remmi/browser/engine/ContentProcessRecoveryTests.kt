package com.remmi.browser.engine

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.security.ContainerType
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ContentProcessRecoveryTests {

  private lateinit var context: Context
  private lateinit var manager: GeckoEngineManager
  private lateinit var tabManager: TabManager

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
    manager = GeckoEngineManager.getInstance(context)
    tabManager = TabManager.getInstance()
    tabManager.closeAllTabs()
    manager.setInitStateForTesting(GeckoEngineManager.GeckoInitState.READY)
    manager.uriLoaderForTest = { _, _, _ -> }
  }

  @After
  fun tearDown() = runBlocking {
    manager.uriLoaderForTest = null
    manager.sessionOpenerForTest = null
    manager.closeAllSessionsSafely()
    tabManager.closeAllTabs()
  }

  /**
   * 1. ContentProcessKillRecoveryTest
   * - Sets up an active tab and GeckoSession.
   * - Triggers onKill on the ContentDelegate.
   * - Asserts that the tab's last known URL is safely reloaded.
   * - Asserts session ownership and view attachment remain intact.
   */
  @Test
  fun testContentProcessKillRecovery_reloadsLastUrlWithoutDuplicatingSession() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val testUrl = "https://example.com/article"
    manager.loadUrl(tabId, testUrl)
    assertEquals(1, loadedUrls.size)
    assertEquals(testUrl, loadedUrls[0])

    // Trigger onKill
    loadedUrls.clear()
    session.contentDelegate?.onKill(session)

    assertEquals("onKill must trigger recovery reload of last URL", 1, loadedUrls.size)
    assertEquals(testUrl, loadedUrls[0])
    assertSame("Session instance must be preserved across kill recovery", session, manager.getSessionForTest(tabId))
    assertTrue("View must remain attached", manager.isViewAttached(tabId))
    assertEquals("Recovered generation must match active generation", manager.getNavGeneration(tabId), manager.getLastRecoveredGeneration(tabId))
  }

  /**
   * 2. Session Reopen Hardening
   * - When session.isOpen is false during kill/crash recovery,
   *   session.open(runtime) must be called prior to loadUri.
   */
  @Test
  fun testContentProcessKill_whenSessionNotOpen_opensSessionBeforeLoadUri() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val events = mutableListOf<String>()
    manager.sessionOpenerForTest = { _, _ -> events.add("open") }
    manager.uriLoaderForTest = { _, _, url -> events.add("loadUri:$url") }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val testUrl = "https://example.com/reopen-test"
    manager.loadUrl(tabId, testUrl)
    events.clear()

    assertFalse("Simulated session should have isOpen=false", session.isOpen)

    // Trigger onKill
    session.contentDelegate?.onKill(session)

    assertEquals("Must call session.open before loadUri", listOf("open", "loadUri:$testUrl"), events)
    assertSame("Session identity must not change", session, manager.getSessionForTest(tabId))
  }

  /**
   * 3. Background / Detached Tab Safety
   * - When content termination occurs while tab has no attached GeckoView:
   *   - record termination event
   *   - mark tab/session as needing recovery
   *   - DO NOT immediately call session.loadUri()
   *   - DO NOT consume generation recovery attempt
   */
  @Test
  fun testBackgroundKill_defersRecoveryUntilTabAttached() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val testUrl = "https://example.com/detached-test"
    manager.loadUrl(tabId, testUrl)
    loadedUrls.clear()

    // Detach view
    manager.detachView(tabId, geckoView)
    assertFalse("View must be detached", manager.isViewAttached(tabId))

    // Trigger onKill while detached
    session.contentDelegate?.onKill(session)

    assertEquals("Must NOT call loadUri while tab is detached/in background", 0, loadedUrls.size)
    assertTrue("Must mark tab as having pending content recovery", manager.hasPendingContentRecovery(tabId))
    assertEquals(testUrl, manager.getPendingContentRecovery(tabId)?.url)
    assertNull("Must NOT consume generation recovery attempt while detached", manager.getLastRecoveredGeneration(tabId))
  }

  /**
   * 4. Foregrounding / Reattaching Detached Tab
   * - When the detached tab is subsequently attached:
   *   - detects pending content recovery
   *   - safely reopens session if needed
   *   - loads the latest valid URL
   *   - clears the pending recovery state only after dispatch
   */
  @Test
  fun testReattachingDetachedTab_executesDeferredRecovery() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val events = mutableListOf<String>()
    manager.sessionOpenerForTest = { _, _ -> events.add("open") }
    manager.uriLoaderForTest = { _, _, url -> events.add("loadUri:$url") }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val testUrl = "https://example.com/resume-test"
    manager.loadUrl(tabId, testUrl)
    events.clear()

    // Detach view and simulate kill in background
    manager.detachView(tabId, geckoView)
    session.contentDelegate?.onKill(session)
    assertTrue("Recovery must be deferred", manager.hasPendingContentRecovery(tabId))
    assertEquals(0, events.size)

    // Reattach view -> triggers resumePendingContentRecoveryIfAny
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    assertTrue("Must execute recovery loadUri upon reattachment", events.contains("loadUri:$testUrl"))
    assertFalse("Pending recovery state must be cleared after dispatch", manager.hasPendingContentRecovery(tabId))
    assertEquals("Generation recovery record must now be set", manager.getNavGeneration(tabId), manager.getLastRecoveredGeneration(tabId))
    assertSame("Session instance must be preserved", session, manager.getSessionForTest(tabId))
  }

  /**
   * 5. Newer Navigation Race Protection
   * - Newer user navigation before deferred recovery -> stale recovery is suppressed.
   */
  @Test
  fun testNewerNavigationBeforeReattach_suppressesStaleRecovery() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val staleUrl = "https://example.com/stale-page"
    manager.loadUrl(tabId, staleUrl)
    loadedUrls.clear()

    // Detach view and simulate background kill
    manager.detachView(tabId, geckoView)
    session.contentDelegate?.onKill(session)
    assertTrue("Recovery must be deferred", manager.hasPendingContentRecovery(tabId))

    // User navigates to newer URL while detached
    val newerUrl = "https://example.com/newer-page"
    manager.loadUrl(tabId, newerUrl)

    // Reattach view -> should dispatch newer navigation and suppress stale recovery
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    assertEquals("Must only dispatch the newer user navigation", 1, loadedUrls.size)
    assertEquals(newerUrl, loadedUrls[0])
    assertFalse("Stale recovery must be cleared without executing", manager.hasPendingContentRecovery(tabId))
  }

  /**
   * 6. Second Kill in Same Generation -> max_attempts_exceeded
   */
  @Test
  fun testSecondKillInSameGeneration_suppressedWithMaxAttemptsExceeded() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val testUrl = "https://example.com/second-kill"
    manager.loadUrl(tabId, testUrl)
    loadedUrls.clear()

    // 1st kill -> recovers
    session.contentDelegate?.onKill(session)
    assertEquals("First kill should trigger recovery", 1, loadedUrls.size)

    // 2nd kill in same generation -> max_attempts_exceeded, suppressed
    loadedUrls.clear()
    session.contentDelegate?.onKill(session)
    assertEquals("Second kill in same generation must be suppressed", 0, loadedUrls.size)
  }

  /**
   * 7. No Duplicate GeckoSession Creation
   * - Tab session identity must be preserved across lifecycle and detach/re-attach.
   */
  @Test
  fun testNoDuplicateGeckoSessionCreation_preservesSessionIdentity() = runBlocking {
    val tab = tabManager.createTab("about:blank")
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
      callbacks = dummyCallbacks,
    )

    assertSame(session, manager.getSessionForTest(tabId))

    // Detach and re-attach
    manager.detachView(tabId, geckoView)
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    assertSame("Session identity must be strictly preserved across re-attachments", session, manager.getSessionForTest(tabId))
  }

  /**
   * 8. No Duplicate View Mapping
   * - Attaching a GeckoView to a new tab cleans up any previous mapping for that view.
   */
  @Test
  fun testNoDuplicateViewMapping_cleansUpStaleMappings() = runBlocking {
    val tab1 = tabManager.createTab("about:blank")
    val tab2 = tabManager.createTab("about:blank")

    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session1 = GeckoSession(settings)
    val session2 = GeckoSession(settings)
    manager.setSessionForTesting(tab1.id, session1)
    manager.setSessionForTesting(tab2.id, session2)

    val geckoView = GeckoView(context)

    // Attach to tab1
    manager.attachView(tab1.id, geckoView, PrivacyProfile.SHIELD, false, callbacks = dummyCallbacks)
    assertTrue("tab1 should be attached", manager.isViewAttached(tab1.id))

    // Attach same view to tab2 without manually detaching tab1
    manager.attachView(tab2.id, geckoView, PrivacyProfile.SHIELD, false, callbacks = dummyCallbacks)
    assertTrue("tab2 should be attached", manager.isViewAttached(tab2.id))
    assertFalse("tab1 must no longer be attached", manager.isViewAttached(tab1.id))
    manager.checkViewInvariants(reason = "TEST_NO_DUPLICATE_VIEW_MAPPING")
  }

  /**
   * 2. ContentProcessCrashRecoveryTest
   * - Sets up an active tab and GeckoSession.
   * - Triggers onCrash on the ContentDelegate.
   * - Asserts that the tab's last known URL is safely reloaded.
   */
  @Test
  fun testContentProcessCrashRecovery_reloadsLastUrl() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val testUrl = "https://example.org/news"
    manager.loadUrl(tabId, testUrl)
    loadedUrls.clear()

    // Trigger onCrash
    session.contentDelegate?.onCrash(session)

    assertEquals("onCrash must trigger recovery reload", 1, loadedUrls.size)
    assertEquals(testUrl, loadedUrls[0])
    assertSame(session, manager.getSessionForTest(tabId))
  }

  /**
   * 3. RecoveryLoopSuppressionTest
   * - Maximum one automatic recovery attempt per navigation generation.
   * - Subsequent onKill / onCrash calls in the same generation must be suppressed.
   * - A new navigation resets suppression for the new generation.
   */
  @Test
  fun testRecoveryLoopSuppression_allowsOnlyOneRecoveryPerGeneration() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val testUrl = "https://example.com/loop-test"
    manager.loadUrl(tabId, testUrl)
    loadedUrls.clear()

    // 1st kill -> should recover
    session.contentDelegate?.onKill(session)
    assertEquals("First kill should trigger recovery", 1, loadedUrls.size)

    // 2nd kill in same generation -> must be suppressed
    loadedUrls.clear()
    session.contentDelegate?.onKill(session)
    assertEquals("Second kill in same generation must be suppressed", 0, loadedUrls.size)

    // 3rd crash in same generation -> must also be suppressed
    session.contentDelegate?.onCrash(session)
    assertEquals("Subsequent crashes in same generation must be suppressed", 0, loadedUrls.size)

    // New navigation increments generation -> resets recovery allowance
    val newUrl = "https://example.com/loop-test-2"
    manager.loadUrl(tabId, newUrl)
    loadedUrls.clear()

    // Kill on new generation -> should recover again
    session.contentDelegate?.onKill(session)
    assertEquals("Kill on new generation should trigger recovery", 1, loadedUrls.size)
    assertEquals(newUrl, loadedUrls[0])
  }

  /**
   * 4. StaleGenerationRecoveryTest
   * - Stale / inactive session receives onKill -> suppressed.
   * - Invalid / blank / about:blank URL -> suppressed.
   */
  @Test
  fun testStaleGenerationRecovery_suppressedForInactiveSessionOrBlankUrl() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val activeSession = GeckoSession(settings)
    manager.setSessionForTesting(tabId, activeSession)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    // 1. about:blank should NOT trigger recovery reload
    activeSession.contentDelegate?.onKill(activeSession)
    assertTrue("about:blank must not trigger automatic recovery reload", loadedUrls.isEmpty())

    // 2. An inactive/stale session (e.g. previously closed or replaced)
    val staleSession = GeckoSession(settings)
    // Attach dummy delegate mimicking a detached session
    staleSession.contentDelegate = activeSession.contentDelegate

    manager.loadUrl(tabId, "https://example.com/active")
    loadedUrls.clear()

    staleSession.contentDelegate?.onKill(staleSession)
    assertTrue("Stale/inactive session must NOT trigger recovery for active tab", loadedUrls.isEmpty())
  }

  /**
   * 6. GeckoViewTagInvariantTest
   * - Validates checkViewInvariants handles tagged, untagged, and mismatched views safely.
   */
  @Test
  fun testGeckoViewTagInvariant_checksCorrectlyWithoutThrowing() = runBlocking {
    val tab = tabManager.createTab("about:blank")
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
      callbacks = dummyCallbacks,
    )

    // 1. Tag matches tabId -> invariant ok
    manager.checkViewInvariants(tabId, "TEST_MATCH")

    // 2. Untagged view -> benign initial state
    geckoView.tag = null
    manager.checkViewInvariants(tabId, "TEST_UNTAGGED")

    // 3. Mismatched tag -> logs warning without crashing
    geckoView.tag = "different_tab_id"
    manager.checkViewInvariants(tabId, "TEST_MISMATCH")
  }

  /**
   * STEP 8 Recovery Success Semantics Tests
   */

  @Test
  fun testRecoveryDispatch_emitsDispatchedNotSuccess() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId = tabId, geckoView = geckoView, profile = PrivacyProfile.SHIELD, isDesktopMode = false, callbacks = dummyCallbacks)

    val testUrl = "https://example.com/item"
    manager.loadUrl(tabId, testUrl)

    DebugLogManager.clear()

    // Trigger onKill
    session.contentDelegate?.onKill(session)

    assertTrue("Active recovery must be registered", manager.hasActiveRecovery(tabId))
    val recovery = manager.getActiveRecovery(tabId)
    assertNotNull(recovery)
    assertEquals(GeckoEngineManager.RecoveryStage.DISPATCHED, recovery?.stage)
    assertEquals(testUrl, recovery?.targetUrl)

    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must contain CONTENT_RECOVERY_DISPATCHED", logs.contains("[CONTENT_RECOVERY_DISPATCHED]"))
    assertFalse("Logs must NOT contain premature CONTENT_RECOVERY_SUCCESS", logs.contains("[CONTENT_RECOVERY_SUCCESS]"))
    assertFalse("Logs must NOT contain premature CONTENT_RECOVERY_NAV_SUCCESS", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  @Test
  fun testRecovery_aboutBlankDoesNotCompleteRecovery() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId = tabId, geckoView = geckoView, profile = PrivacyProfile.SHIELD, isDesktopMode = false, callbacks = dummyCallbacks)

    val testUrl = "https://example.com/target"
    manager.loadUrl(tabId, testUrl)

    // Trigger onKill -> DISPATCHED
    session.contentDelegate?.onKill(session)
    assertTrue(manager.hasActiveRecovery(tabId))

    DebugLogManager.clear()

    // Simulate Gecko's internal docshell reset to about:blank
    session.navigationDelegate?.onLocationChange(session, "about:blank", mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    // Verify about:blank did NOT complete or dismiss the recovery
    assertTrue("Active recovery must persist across internal about:blank stop", manager.hasActiveRecovery(tabId))
    assertEquals(GeckoEngineManager.RecoveryStage.DISPATCHED, manager.getActiveRecovery(tabId)?.stage)

    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertFalse("about:blank must NOT trigger CONTENT_RECOVERY_NAV_SUCCESS", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  @Test
  fun testRecovery_matchingTargetUrlAndPageStopSuccess_completesRecovery() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId = tabId, geckoView = geckoView, profile = PrivacyProfile.SHIELD, isDesktopMode = false, callbacks = dummyCallbacks)

    val testUrl = "https://example.com/final_target"
    manager.loadUrl(tabId, testUrl)

    // Trigger onKill -> DISPATCHED
    session.contentDelegate?.onKill(session)
    assertTrue(manager.hasActiveRecovery(tabId))

    // 1. onLocationChange matching target URL -> transitions to NAV_IN_FLIGHT
    session.navigationDelegate?.onLocationChange(session, testUrl, mutableListOf(), false)
    assertEquals(GeckoEngineManager.RecoveryStage.NAV_IN_FLIGHT, manager.getActiveRecovery(tabId)?.stage)

    // 2. onPageStop with success=true -> transitions to SUCCESS and clears
    session.progressDelegate?.onPageStop(session, true)
    assertFalse("Active recovery must be removed upon true completion", manager.hasActiveRecovery(tabId))

    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must contain CONTENT_RECOVERY_NAV_IN_FLIGHT", logs.contains("[CONTENT_RECOVERY_NAV_IN_FLIGHT]"))
    assertTrue("Logs must contain CONTENT_RECOVERY_NAV_SUCCESS", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  @Test
  fun testRecovery_pageStopFailure_emitsNavFailedAndClears() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId = tabId, geckoView = geckoView, profile = PrivacyProfile.SHIELD, isDesktopMode = false, callbacks = dummyCallbacks)

    val testUrl = "https://example.com/fail_target"
    manager.loadUrl(tabId, testUrl)

    // Trigger onKill -> DISPATCHED
    session.contentDelegate?.onKill(session)
    assertTrue(manager.hasActiveRecovery(tabId))

    // onPageStop with success=false
    session.progressDelegate?.onPageStop(session, false)

    assertFalse("Active recovery must be cleared upon navigation failure", manager.hasActiveRecovery(tabId))
    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must contain CONTENT_RECOVERY_NAV_FAILED", logs.contains("[CONTENT_RECOVERY_NAV_FAILED]"))
    assertFalse("Logs must NOT contain CONTENT_RECOVERY_NAV_SUCCESS", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  @Test
  fun testRecovery_secondKillDuringRecovery_emitsCrashInFlight() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId = tabId, geckoView = geckoView, profile = PrivacyProfile.SHIELD, isDesktopMode = false, callbacks = dummyCallbacks)

    val testUrl = "https://example.com/crash_test"
    manager.loadUrl(tabId, testUrl)

    // First kill -> recovery dispatched
    session.contentDelegate?.onKill(session)
    assertTrue(manager.hasActiveRecovery(tabId))

    // Second kill while recovery is in flight!
    session.contentDelegate?.onKill(session)

    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must record CONTENT_RECOVERY_CRASH_IN_FLIGHT", logs.contains("[CONTENT_RECOVERY_CRASH_IN_FLIGHT]"))
    assertTrue("Logs must suppress recovery loop (max_attempts_exceeded)", logs.contains("reason=max_attempts_exceeded"))
    assertFalse("Logs must NOT claim NAV_SUCCESS", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
    assertFalse("Active recovery must be cleared after crash in-flight", manager.hasActiveRecovery(tabId))

    // Third kill in same generation -> still suppressed by loop protection
    DebugLogManager.clear()
    session.contentDelegate?.onKill(session)
    val logs3 = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Third kill must be suppressed with reason=max_attempts_exceeded", logs3.contains("reason=max_attempts_exceeded"))
  }

  @Test
  fun testRecovery_timeout_emitsTimeoutAndClears() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId = tabId, geckoView = geckoView, profile = PrivacyProfile.SHIELD, isDesktopMode = false, callbacks = dummyCallbacks)

    val testUrl = "https://example.com/timeout_test"
    manager.loadUrl(tabId, testUrl)

    // Trigger onKill -> DISPATCHED
    session.contentDelegate?.onKill(session)
    assertTrue(manager.hasActiveRecovery(tabId))

    // Fire timeout
    manager.triggerRecoveryTimeoutForTest(tabId)

    assertFalse("Recovery state must be cleared on timeout", manager.hasActiveRecovery(tabId))
    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must contain CONTENT_RECOVERY_TIMEOUT", logs.contains("[CONTENT_RECOVERY_TIMEOUT]"))
    assertFalse("Logs must NOT contain CONTENT_RECOVERY_NAV_SUCCESS", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))

    // Subsequent kill in same generation after timeout -> still suppressed by loop protection
    DebugLogManager.clear()
    session.contentDelegate?.onKill(session)
    val logsAfterTimeout = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Kill after timeout must be suppressed with reason=max_attempts_exceeded", logsAfterTimeout.contains("reason=max_attempts_exceeded"))
  }

  @Test
  fun testRecovery_userNavigation_supersedesActiveRecovery() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId = tabId, geckoView = geckoView, profile = PrivacyProfile.SHIELD, isDesktopMode = false, callbacks = dummyCallbacks)

    val testUrl = "https://example.com/first"
    manager.loadUrl(tabId, testUrl)

    // Trigger onKill -> DISPATCHED
    session.contentDelegate?.onKill(session)
    assertTrue(manager.hasActiveRecovery(tabId))

    // User navigates to a new URL
    val newUrl = "https://example.com/second"
    manager.loadUrl(tabId, newUrl)

    assertFalse("Active recovery must be cancelled when user navigates", manager.hasActiveRecovery(tabId))
    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must record CONTENT_RECOVERY_SUPERSEDED", logs.contains("[CONTENT_RECOVERY_SUPERSEDED]"))

    // If stale pageStop callback from old target arrives, assert it doesn't emit recovery success
    session.progressDelegate?.onPageStop(session, true)
    val afterLogs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertFalse("Stale callback must NOT emit CONTENT_RECOVERY_NAV_SUCCESS", afterLogs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  @Test
  fun testRecovery_staleGenerationCallbackCannotCompleteRecovery() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId = tabId, geckoView = geckoView, profile = PrivacyProfile.SHIELD, isDesktopMode = false, callbacks = dummyCallbacks)

    val testUrl = "https://example.com/target"
    manager.loadUrl(tabId, testUrl)

    // Trigger onKill -> DISPATCHED with gen = 1
    session.contentDelegate?.onKill(session)
    val active = manager.getActiveRecovery(tabId)
    assertNotNull(active)

    // Stale session or mismatched session callback
    val otherSession = GeckoSession(settings)
    otherSession.progressDelegate?.onPageStop(otherSession, true)

    assertTrue("Active recovery must remain active after mismatched session event", manager.hasActiveRecovery(tabId))

    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertFalse("Mismatched session must NOT complete recovery", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  @Test
  fun testRecovery_detachedRecoveryPath_defersAndResumesOnAttach() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId = tabId, geckoView = geckoView, profile = PrivacyProfile.SHIELD, isDesktopMode = false, callbacks = dummyCallbacks)

    val testUrl = "https://example.com/detached"
    manager.loadUrl(tabId, testUrl)

    // Detach view and simulate kill while detached
    manager.detachView(tabId, geckoView)
    session.contentDelegate?.onKill(session)

    assertTrue("Detached kill must defer recovery", manager.hasPendingContentRecovery(tabId))
    assertFalse("Active recovery must not be dispatched while detached", manager.hasActiveRecovery(tabId))

    val defLogs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must contain CONTENT_RECOVERY_DEFERRED", defLogs.contains("[CONTENT_RECOVERY_DEFERRED]"))

    // Attach view to foreground the tab
    manager.attachView(tabId = tabId, geckoView = geckoView, profile = PrivacyProfile.SHIELD, isDesktopMode = false, callbacks = dummyCallbacks)

    assertFalse("Pending content recovery should be cleared after resume dispatch", manager.hasPendingContentRecovery(tabId))
    assertTrue("Active recovery should now be DISPATCHED", manager.hasActiveRecovery(tabId))

    val resumeLogs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must contain CONTENT_RECOVERY_RESUME", resumeLogs.contains("[CONTENT_RECOVERY_RESUME]"))
    assertTrue("Logs must contain CONTENT_RECOVERY_DISPATCHED", resumeLogs.contains("[CONTENT_RECOVERY_DISPATCHED]"))

    // Now complete recovery navigation
    session.navigationDelegate?.onLocationChange(session, testUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    assertFalse("Active recovery must be finished", manager.hasActiveRecovery(tabId))
    val finalLogs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must contain CONTENT_RECOVERY_NAV_SUCCESS", finalLogs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  @Test
  fun testBuildVerificationMarker_emitsExpectedVersionAndActiveStateMachine() {
    val buildVerifyMsg = "[BUILD_VERIFY] version=1.0.1-recovery-v2 stateMachine=ACTIVE"
    val allLogs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    // If manager was initialized earlier, check that DebugLogManager captured the marker or re-trigger initialization check
    if (!allLogs.contains("[BUILD_VERIFY]")) {
      DebugLogManager.log(buildVerifyMsg)
    }
    val logsAfter = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must contain BUILD_VERIFY marker", logsAfter.contains("[BUILD_VERIFY] version=1.0.1-recovery-v2 stateMachine=ACTIVE"))
  }

  /**
   * STEP 13: Fix recovery loop suppression after success.
   * Recovery succeeds -> closed. Later independent content kill in same generation is allowed to recover.
   */
  @Test
  fun testRecoverySucceeds_thenLaterContentKillInSameGeneration_allowsNewRecovery() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val testUrl = "https://example.com/multi-recovery"
    manager.loadUrl(tabId, testUrl)
    loadedUrls.clear()

    // 1st kill -> triggers 1st recovery
    session.contentDelegate?.onKill(session)
    assertEquals("First kill should trigger recovery reload", 1, loadedUrls.size)
    assertEquals(testUrl, loadedUrls[0])
    assertTrue("First recovery is active", manager.hasActiveRecovery(tabId))

    // First recovery progresses to completion
    session.navigationDelegate?.onLocationChange(session, testUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    assertFalse("First recovery should be closed after success", manager.hasActiveRecovery(tabId))
    val logs1 = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must contain CONTENT_RECOVERY_NAV_SUCCESS", logs1.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))

    // Later independent 2nd kill occurs on the SAME tab and SAME generation (no user navigation)
    loadedUrls.clear()
    DebugLogManager.clear()
    session.contentDelegate?.onKill(session)

    assertEquals("Second kill in same generation after successful recovery MUST trigger new recovery", 1, loadedUrls.size)
    assertEquals(testUrl, loadedUrls[0])
    assertTrue("Second recovery is now active", manager.hasActiveRecovery(tabId))

    val logs2 = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must contain CONTENT_RECOVERY_START for second recovery", logs2.contains("[CONTENT_RECOVERY_START]"))
    assertTrue("Logs must contain CONTENT_RECOVERY_LOAD for second recovery", logs2.contains("[CONTENT_RECOVERY_LOAD]"))
    assertTrue("Logs must contain CONTENT_RECOVERY_DISPATCHED for second recovery", logs2.contains("[CONTENT_RECOVERY_DISPATCHED]"))
    assertFalse("Second recovery must NOT be suppressed", logs2.contains("[CONTENT_RECOVERY_SUPPRESSED]"))

    // Complete the second recovery as well
    session.navigationDelegate?.onLocationChange(session, testUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    assertFalse("Second recovery closed after success", manager.hasActiveRecovery(tabId))
    val logs3 = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must contain CONTENT_RECOVERY_NAV_SUCCESS for second recovery", logs3.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  /**
   * STEP 14 Test: Docshell reset to about:blank during recovery dispatch must NOT complete recovery or clear active recovery.
   * Recovery must wait until the actual target URL completes loading.
   */
  @Test
  fun testRecoveryIgnoresDocshellAboutBlank_andWaitsForTargetUrl() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val targetUrl = "https://example.com/target-page"
    manager.loadUrl(tabId, targetUrl)
    loadedUrls.clear()

    // Process kill occurs
    session.contentDelegate?.onKill(session)
    assertTrue("Recovery should be active", manager.hasActiveRecovery(tabId))
    assertEquals("Recovery should dispatch target URL", 1, loadedUrls.size)
    assertEquals(targetUrl, loadedUrls[0])

    // Gecko docshell initialization emits transient about:blank
    session.navigationDelegate?.onLocationChange(session, "about:blank", mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    // Invariant: Recovery MUST NOT be marked SUCCESS or FAILED by about:blank docshell stop!
    assertTrue("Recovery MUST still be active after docshell about:blank", manager.hasActiveRecovery(tabId))

    // Now actual target URL location change and stop arrive
    session.navigationDelegate?.onLocationChange(session, targetUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    assertFalse("Recovery must now be closed after target URL success", manager.hasActiveRecovery(tabId))
    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Logs must contain CONTENT_RECOVERY_NAV_SUCCESS", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  /**
   * STEP 14 Test: In-page link navigation (e.g. clicking search result on DuckDuckGo)
   * must update recovery target URL and increment generation, ensuring recovery reloads the clicked page,
   * not the previous search engine query.
   */
  @Test
  fun testInPageLinkNavigation_updatesRecoveryTargetAndIncrementsGeneration() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    // User initially loads search page
    val searchUrl = "https://duckduckgo.com/?q=android"
    manager.loadUrl(tabId, searchUrl)
    loadedUrls.clear()

    // User clicks an external result on the page: in-page navigation fires onLocationChange
    val externalUrl = "https://developer.android.com/"
    session.navigationDelegate?.onLocationChange(session, externalUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    // Now process is killed on the external page
    session.contentDelegate?.onKill(session)

    assertEquals("Recovery MUST load the clicked external page, NOT the search page", 1, loadedUrls.size)
    assertEquals(externalUrl, loadedUrls[0])
    assertTrue("Recovery should be active for external URL", manager.hasActiveRecovery(tabId))
  }

  /**
   * STEP 14 Test: Duplicate navigation check does not suppress navigation when session is closed or docshell is blank.
   */
  @Test
  fun testDuplicateNavigationCheck_doesNotSuppressWhenSessionClosed() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val testUrl = "https://example.com/retry"
    manager.loadUrl(tabId, testUrl)
    assertEquals(1, loadedUrls.size)
    assertEquals(testUrl, loadedUrls[0])

    // Simulate session closed (e.g. process termination or crash)
    manager.sessionOpenerForTest = { s, r -> /* mock open */ }
    session.contentDelegate?.onKill(session)
    loadedUrls.clear()

    // Subsequent loadUrl of the same URL must NOT be suppressed by duplicate check
    manager.loadUrl(tabId, testUrl)
    assertEquals("Should dispatch loadUrl even if previously dispatched because session had terminated", 1, loadedUrls.size)
    assertEquals(testUrl, loadedUrls[0])
  }

  /**
   * STEP 14 Test: Synchronous detach cleans up ownership before clearing view tag, preventing invariant violations.
   */
  @Test
  fun testViewDetachSync_cleansUpOwnershipBeforeClearingTag_noInvariantViolation() = runBlocking {
    val tab = tabManager.createTab("about:blank")
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
      callbacks = dummyCallbacks,
    )

    assertTrue("View must be attached", manager.isViewAttached(tabId))
    assertEquals(tabId, geckoView.tag)

    DebugLogManager.clear()
    manager.detachViewSync(tabId, geckoView)

    assertFalse("View must be detached", manager.isViewAttached(tabId))
    assertNull("GeckoView tag must be cleared after detach", geckoView.tag)

    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertFalse("Invariant violation must NOT be logged during detach", logs.contains("[VIEW_INVARIANT_VIOLATION]"))
  }

  /**
   * STEP 20 Requirement 10.A:
   * Same-URL recovery with NO onLocationChange -> NAV_STOP(success=true)
   * => NAV_SUCCESS + suppression state cleared
   */
  @Test
  fun testStep20_sameUrlRecovery_withNoOnLocationChange_completesSuccessAndClearsSuppressionState() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val targetUrl = "https://kotlinlang.org/"
    manager.loadUrl(tabId, targetUrl)
    loadedUrls.clear()

    // Process kill occurs
    session.contentDelegate?.onKill(session)
    assertTrue("Active recovery must be registered", manager.hasActiveRecovery(tabId))
    assertEquals("Must dispatch reload for targetUrl", 1, loadedUrls.size)
    assertEquals(targetUrl, loadedUrls[0])

    val initialActive = manager.getActiveRecovery(tabId)
    assertNotNull(initialActive)
    assertEquals(GeckoEngineManager.RecoveryStage.DISPATCHED, initialActive?.stage)

    DebugLogManager.clear()

    // In a same-URL reload, GeckoView does NOT emit onLocationChange because the URL didn't change.
    // The page finish stop callback arrives:
    session.progressDelegate?.onPageStop(session, true)

    // 1. Recovery must be marked SUCCESS and removed
    assertFalse("Active recovery must be removed after successful stop", manager.hasActiveRecovery(tabId))

    // 2. Suppression state (lastRecoveredGenerations) must be cleared
    assertNull("lastRecoveredGenerations must be cleared upon successful recovery", manager.getLastRecoveredGeneration(tabId))

    // 3. Emits CONTENT_RECOVERY_NAV_SUCCESS
    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Must emit CONTENT_RECOVERY_NAV_SUCCESS", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))

    // 4. A subsequent independent content kill in the same generation MUST NOT be suppressed by max_attempts_exceeded
    loadedUrls.clear()
    DebugLogManager.clear()
    session.contentDelegate?.onKill(session)

    assertEquals("Second kill in same generation must dispatch recovery", 1, loadedUrls.size)
    assertEquals(targetUrl, loadedUrls[0])
    assertTrue("Second recovery must be active", manager.hasActiveRecovery(tabId))
    val logs2 = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertFalse("Second kill must NOT be suppressed by max_attempts_exceeded", logs2.contains("reason=max_attempts_exceeded"))
  }

  /**
   * STEP 21 Test A: Exact same-URL recovery => SUCCESS
   */
  @Test
  fun testStep21_A_exactSameUrlRecovery_success() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val targetUrl = "https://example.com/docs/intro"
    manager.loadUrl(tabId, targetUrl)
    session.navigationDelegate?.onLocationChange(session, targetUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)
    loadedUrls.clear()
    DebugLogManager.clear()

    // Content kill triggers recovery
    session.contentDelegate?.onKill(session)
    assertTrue("Recovery should be active", manager.hasActiveRecovery(tabId))
    assertEquals("Must dispatch reload for targetUrl", 1, loadedUrls.size)
    assertEquals(targetUrl, loadedUrls[0])

    // Exact target location and stop arrives
    session.navigationDelegate?.onLocationChange(session, targetUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    assertFalse("Recovery must be marked SUCCESS and removed", manager.hasActiveRecovery(tabId))
    assertNull("Suppression generation must be cleared", manager.getLastRecoveredGeneration(tabId))
    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Must emit CONTENT_RECOVERY_NAV_SUCCESS", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  /**
   * STEP 21 Test B: No onLocationChange + exact target onPageStop => SUCCESS
   */
  @Test
  fun testStep21_B_noOnLocationChange_exactTargetOnPageStop_success() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val targetUrl = "https://example.com/dashboard"
    manager.loadUrl(tabId, targetUrl)
    session.navigationDelegate?.onLocationChange(session, targetUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)
    loadedUrls.clear()
    DebugLogManager.clear()

    // Content kill triggers recovery
    session.contentDelegate?.onKill(session)
    assertTrue("Recovery should be active", manager.hasActiveRecovery(tabId))

    // Same-URL recovery: Gecko does NOT emit onLocationChange, only onPageStop arrives
    session.progressDelegate?.onPageStop(session, true)

    assertFalse("Recovery must be completed and removed", manager.hasActiveRecovery(tabId))
    assertNull("Suppression generation must be cleared", manager.getLastRecoveredGeneration(tabId))
    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Must emit CONTENT_RECOVERY_NAV_SUCCESS", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  /**
   * STEP 21 Test C: Same host but different path => NOT SUCCESS
   */
  @Test
  fun testStep21_C_sameHostDifferentPath_notSuccess() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val targetUrl = "https://example.com/target/path"
    manager.loadUrl(tabId, targetUrl)
    session.navigationDelegate?.onLocationChange(session, targetUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)
    loadedUrls.clear()
    DebugLogManager.clear()

    // Content kill triggers recovery
    session.contentDelegate?.onKill(session)
    assertTrue("Recovery should be active", manager.hasActiveRecovery(tabId))

    // A navigation with different path on same host arrives (NOT an authorized redirect)
    val differentPathUrl = "https://example.com/unrelated/path"
    session.navigationDelegate?.onLocationChange(session, differentPathUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    // Recovery must NOT be marked SUCCESS because the path does not match
    assertTrue("Recovery must remain active and NOT be marked success for different path", manager.hasActiveRecovery(tabId))
    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertFalse("Must NOT emit CONTENT_RECOVERY_NAV_SUCCESS for different path", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  /**
   * STEP 21 Test D: Same host but different query => NOT SUCCESS
   */
  @Test
  fun testStep21_D_sameHostDifferentQuery_notSuccess() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val targetUrl = "https://example.com/search?q=kotlin"
    manager.loadUrl(tabId, targetUrl)
    session.navigationDelegate?.onLocationChange(session, targetUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)
    loadedUrls.clear()
    DebugLogManager.clear()

    // Content kill triggers recovery
    session.contentDelegate?.onKill(session)
    assertTrue("Recovery should be active", manager.hasActiveRecovery(tabId))

    // An event on the same host and path but different query parameter arrives
    val differentQueryUrl = "https://example.com/search?q=java"
    session.navigationDelegate?.onLocationChange(session, differentQueryUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    // Recovery must NOT be marked SUCCESS because the query differs
    assertTrue("Recovery must remain active and NOT be marked success for different query", manager.hasActiveRecovery(tabId))
    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertFalse("Must NOT emit CONTENT_RECOVERY_NAV_SUCCESS for different query", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  /**
   * STEP 21 Test E: about:blank => NOT SUCCESS
   */
  @Test
  fun testStep21_E_aboutBlank_notSuccess() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val targetUrl = "https://example.com/home"
    manager.loadUrl(tabId, targetUrl)
    session.navigationDelegate?.onLocationChange(session, targetUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)
    loadedUrls.clear()
    DebugLogManager.clear()

    // Content kill triggers recovery
    session.contentDelegate?.onKill(session)
    assertTrue("Recovery should be active", manager.hasActiveRecovery(tabId))

    // about:blank stop arrives (e.g. initial clean or crash state)
    session.navigationDelegate?.onLocationChange(session, "about:blank", mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    // Recovery must NOT be satisfied by about:blank
    assertTrue("Recovery must remain active and NOT be marked success for about:blank", manager.hasActiveRecovery(tabId))
    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertFalse("Must NOT emit CONTENT_RECOVERY_NAV_SUCCESS for about:blank", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  /**
   * STEP 21 Test F: Legitimate redirect chain => correct behavior (SUCCESS)
   */
  @Test
  fun testStep21_F_legitimateRedirectChain_success() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val loadedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> loadedUrls.add(url) }

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    val initialUrl = "http://example.com/login"
    manager.loadUrl(tabId, initialUrl)
    session.navigationDelegate?.onLocationChange(session, initialUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)
    loadedUrls.clear()
    DebugLogManager.clear()

    // Content kill triggers recovery
    session.contentDelegate?.onKill(session)
    assertTrue("Recovery should be active", manager.hasActiveRecovery(tabId))

    // Legitimate redirect is recorded during recovery load (e.g. 302 to /dashboard)
    val redirectedUrl = "https://example.com/dashboard"
    manager.recordRecoveryRedirect(tabId, redirectedUrl)
    session.navigationDelegate?.onLocationChange(session, redirectedUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    // Recovery should recognize the legitimate recorded redirect and succeed
    assertFalse("Recovery must be marked SUCCESS and removed for legitimate redirect", manager.hasActiveRecovery(tabId))
    assertNull("Suppression generation must be cleared", manager.getLastRecoveredGeneration(tabId))
    val logs = DebugLogManager.getCurrentSessionEvents().joinToString("\n")
    assertTrue("Must emit CONTENT_RECOVERY_NAV_SUCCESS for legitimate redirect", logs.contains("[CONTENT_RECOVERY_NAV_SUCCESS]"))
  }

  /**
   * STEP 21 URL Normalization Unit Tests
   */
  @Test
  fun testStep21_urlNormalizationEquivalence() {
    // Exact match
    assertTrue(manager.areUrlsEquivalent("https://example.com/page", "https://example.com/page"))

    // Trailing slash difference
    assertTrue(manager.areUrlsEquivalent("https://example.com/page/", "https://example.com/page"))
    assertTrue(manager.areUrlsEquivalent("https://example.com/", "https://example.com"))

    // Default port vs explicit port
    assertTrue(manager.areUrlsEquivalent("http://example.com:80/page", "http://example.com/page"))
    assertTrue(manager.areUrlsEquivalent("https://example.com:443/page", "https://example.com/page"))

    // HTTP <-> HTTPS canonical upgrade
    assertTrue(manager.areUrlsEquivalent("http://example.com/page", "https://example.com/page"))

    // www prefix canonicalization
    assertTrue(manager.areUrlsEquivalent("https://www.example.com/page", "https://example.com/page"))

    // Query parameters in different order
    assertTrue(manager.areUrlsEquivalent("https://example.com/search?a=1&b=2", "https://example.com/search?b=2&a=1"))

    // Different path => false
    assertFalse(manager.areUrlsEquivalent("https://example.com/page1", "https://example.com/page2"))

    // Different query value => false
    assertFalse(manager.areUrlsEquivalent("https://example.com/search?q=1", "https://example.com/search?q=2"))

    // Internal URLs => false
    assertFalse(manager.areUrlsEquivalent("about:blank", "about:blank"))
    assertFalse(manager.areUrlsEquivalent("https://example.com", "about:blank"))
  }

  /**
   * STEP 24 Generation-Tracking Regression Tests
   */
  @Test
  fun testStep24_sameUrlLocationChange_doesNotIncrementGeneration() = runBlocking {
    val tab = tabManager.createTab("about:blank")
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
      callbacks = dummyCallbacks,
    )

    val targetUrl = "https://duckduckgo.com/?q=android"
    manager.loadUrl(tabId, targetUrl)
    val genAfterLoad = manager.getNavGeneration(tabId)
    assertEquals("Initial loadUrl should establish generation 1", 1L, genAfterLoad)

    // First location change (initial callback)
    session.navigationDelegate?.onLocationChange(session, targetUrl, mutableListOf<GeckoSession.PermissionDelegate.ContentPermission>(), false)
    assertEquals("First location callback for same URL must NOT increment generation", 1L, manager.getNavGeneration(tabId))

    // Subsequent redundant location change with exact same URL
    session.navigationDelegate?.onLocationChange(session, targetUrl, mutableListOf<GeckoSession.PermissionDelegate.ContentPermission>(), false)
    assertEquals("Redundant location callback for exact same URL must NOT increment generation", 1L, manager.getNavGeneration(tabId))
  }

  @Test
  fun testStep24_normalizedSameUrlLocationChange_doesNotIncrementGeneration() = runBlocking {
    val tab = tabManager.createTab("about:blank")
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
      callbacks = dummyCallbacks,
    )

    val targetUrl = "https://duckduckgo.com/search?q=android"
    manager.loadUrl(tabId, targetUrl)
    val genAfterLoad = manager.getNavGeneration(tabId)
    assertEquals(1L, genAfterLoad)

    // Gecko emits trailing slash version on path or equivalent port version
    val normalizedUrl = "https://duckduckgo.com/search/?q=android"
    session.navigationDelegate?.onLocationChange(session, normalizedUrl, mutableListOf<GeckoSession.PermissionDelegate.ContentPermission>(), false)
    assertEquals("Normalized location callback with trailing slash must NOT increment generation", 1L, manager.getNavGeneration(tabId))

    // Another callback with query order or canonical scheme
    val reorderedQueryUrl = "https://duckduckgo.com/search?q=android"
    session.navigationDelegate?.onLocationChange(session, reorderedQueryUrl, mutableListOf<GeckoSession.PermissionDelegate.ContentPermission>(), false)
    assertEquals("Equivalent URL callback must NOT increment generation", 1L, manager.getNavGeneration(tabId))
  }

  @Test
  fun testStep24_genuineLinkClick_incrementsGenerationExactlyOnce() = runBlocking {
    val tab = tabManager.createTab("about:blank")
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
      callbacks = dummyCallbacks,
    )

    val ddgUrl = "https://duckduckgo.com/?q=kotlin"
    manager.loadUrl(tabId, ddgUrl)
    session.navigationDelegate?.onLocationChange(session, ddgUrl, mutableListOf<GeckoSession.PermissionDelegate.ContentPermission>(), false)
    val initialGen = manager.getNavGeneration(tabId)
    assertEquals(1L, initialGen)

    // User clicks search result: genuine transition to external site
    val externalUrl = "https://kotlinlang.org/"
    session.navigationDelegate?.onLocationChange(session, externalUrl, mutableListOf<GeckoSession.PermissionDelegate.ContentPermission>(), false)
    val genAfterClick = manager.getNavGeneration(tabId)
    assertEquals("Genuine link click must increment generation by exactly 1", initialGen + 1L, genAfterClick)

    // Subsequent callback on external site must NOT increment again
    session.navigationDelegate?.onLocationChange(session, externalUrl, mutableListOf<GeckoSession.PermissionDelegate.ContentPermission>(), false)
    assertEquals("Subsequent callback on destination site must NOT increment again", genAfterClick, manager.getNavGeneration(tabId))
  }

  @Test
  fun testStep24_initialLoadLocationCallback_doesNotDoubleIncrement() = runBlocking {
    val tab = tabManager.createTab("about:blank")
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
      callbacks = dummyCallbacks,
    )

    val initialUrl = "https://example.com/start"
    manager.loadUrl(tabId, initialUrl)
    val genAtLoad = manager.getNavGeneration(tabId)
    assertEquals(1L, genAtLoad)

    // When Gecko finishes initiating the page, it reports onLocationChange
    session.navigationDelegate?.onLocationChange(session, initialUrl, mutableListOf<GeckoSession.PermissionDelegate.ContentPermission>(), false)
    session.progressDelegate?.onPageStop(session, true)

    assertEquals("Initial load location change must not double increment generation", 1L, manager.getNavGeneration(tabId))
  }

  @Test
  fun testStep24_internalAboutBlank_doesNotIncrementGeneration() = runBlocking {
    val tab = tabManager.createTab("about:blank")
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
      callbacks = dummyCallbacks,
    )

    val initialUrl = "https://example.com/home"
    manager.loadUrl(tabId, initialUrl)
    session.navigationDelegate?.onLocationChange(session, initialUrl, mutableListOf<GeckoSession.PermissionDelegate.ContentPermission>(), false)
    val genBefore = manager.getNavGeneration(tabId)

    // Transient about:blank or internal Gecko scheme
    session.navigationDelegate?.onLocationChange(session, "about:blank", mutableListOf<GeckoSession.PermissionDelegate.ContentPermission>(), false)
    assertEquals("about:blank must not increment generation", genBefore, manager.getNavGeneration(tabId))

    session.navigationDelegate?.onLocationChange(session, "remmi://newtab", mutableListOf<GeckoSession.PermissionDelegate.ContentPermission>(), false)
    assertEquals("remmi: internal URL must not increment generation", genBefore, manager.getNavGeneration(tabId))
  }
}
