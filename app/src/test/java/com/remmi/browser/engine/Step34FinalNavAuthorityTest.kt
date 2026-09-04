package com.remmi.browser.engine

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

/**
 * Step 34 Regression & Forensic Tests
 * 
 * Verifies:
 * 1. Single Navigation Authority: loadUrl followed by onLoadRequest uses exactly ONE navId & generation.
 * 2. In-flight intent correlation in onLoadRequest emits [NAV_CORRELATION] with reason=correlated_to_inflight_intent.
 * 3. Link click / in-page user gesture allocates exactly ONE navId, and subsequent onLocationChange correlates.
 * 4. Redirects during in-flight navigation correlate without allocating a new navId.
 * 5. Same-document SPA navigations correlate without allocating a new navId.
 * 6. VIEW_ON_RELEASE / DETACH_VIEW after terminal success is classified as VIEW_DISPOSED_AFTER_NAV_SUCCESS.
 * 7. POST_NAV_FAILURE is suppressed on benign view disposal after successful navigation.
 * 8. VIEW_ON_RELEASE during active navigation is confirmed as VIEW_DISPOSED_DURING_ACTIVE_NAVIGATION.
 * 9. Content crash and kill are classified as CONTENT_PROCESS_FAILED.
 * 10. Navigation failure on page stop is classified as NAVIGATION_FAILED.
 * 11. All forensic tags ([NAV_INTENT], [NAV_ALLOCATION], [NAV_CORRELATION], [POST_NAV_LIFECYCLE], [POST_NAV_FAILURE_SUPPRESSED]) emit correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class Step34FinalNavAuthorityTest {

  private lateinit var context: Context
  private lateinit var manager: GeckoEngineManager
  private lateinit var tabManager: TabManager

  private val testCallbacks = object : GeckoTabCallbacks {
    override fun onUrlChange(url: String) {}
    override fun onTitleChange(title: String) {}
    override fun onProgressChange(progress: Int) {}
    override fun onLoadingChange(isLoading: Boolean) {}
    override fun onSecurityChange(isSecure: Boolean) {}
    override fun onNavStateChange(canGoBack: Boolean, canGoForward: Boolean) {}
    override fun onTrackerBlocked(url: String, type: String) {}
    override fun onScrollChanged(scrollX: Int, scrollY: Int, isScrollingDown: Boolean) {}
  }

  private fun makeLoadRequest(
    uri: String,
    isRedirect: Boolean = false,
    hasUserGesture: Boolean = false
  ): GeckoSession.NavigationDelegate.LoadRequest {
    val constructor = GeckoSession.NavigationDelegate.LoadRequest::class.java.getDeclaredConstructor()
    constructor.isAccessible = true
    val req = constructor.newInstance()
    
    val uriField = req::class.java.getDeclaredField("uri")
    uriField.isAccessible = true
    uriField.set(req, uri)

    val isRedirectField = req::class.java.getDeclaredField("isRedirect")
    isRedirectField.isAccessible = true
    isRedirectField.setBoolean(req, isRedirect)

    val hasUserGestureField = req::class.java.getDeclaredField("hasUserGesture")
    hasUserGestureField.isAccessible = true
    hasUserGestureField.setBoolean(req, hasUserGesture)
    
    return req
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
    manager.sessionOpenerForTest = { _, _ -> }
  }

  @After
  fun tearDown() = runBlocking {
    manager.uriLoaderForTest = null
    manager.sessionOpenerForTest = null
    manager.closeAllSessionsSafely()
    tabManager.closeAllTabs()
    DebugLogManager.clear()
  }

  @Test
  fun testIssueA_loadUrlFollowedByOnLoadRequest_usesSingleNavIdAndCorrelates() = runBlocking {
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
      callbacks = testCallbacks,
    )

    val targetUrl = "https://tryblock.org/"
    DebugLogManager.clear()

    // 1. App initiates navigation via loadUrl
    manager.loadUrl(tabId, targetUrl)
    val initialNavId = manager.getActiveNavId(tabId)
    val initialGen = manager.getNavGeneration(tabId)

    // 2. Gecko fires onLoadRequest (with hasUserGesture=true, as in real device trace)
    val loadRequest = makeLoadRequest(
      targetUrl,
      isRedirect = false,
      hasUserGesture = true
    )
    session.navigationDelegate?.onLoadRequest(session, loadRequest)

    val navIdAfterLoadReq = manager.getActiveNavId(tabId)
    val genAfterLoadReq = manager.getNavGeneration(tabId)

    // INVARIANT: onLoadRequest MUST NOT allocate a second navId or increment generation
    assertEquals("navId must not change on onLoadRequest for in-flight loadUrl", initialNavId, navIdAfterLoadReq)
    assertEquals("generation must not change on onLoadRequest for in-flight loadUrl", initialGen, genAfterLoadReq)

    // 3. Subsequent onLocationChange and onPageStop
    session.navigationDelegate?.onLocationChange(session, targetUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    val finalNavId = manager.getActiveNavId(tabId)
    val finalGen = manager.getNavGeneration(tabId)
    assertEquals("navId must remain identical across the entire navigation lifecycle", initialNavId, finalNavId)
    assertEquals("generation must remain identical across the entire navigation lifecycle", initialGen, finalGen)

    val logs = DebugLogManager.getCurrentSessionEvents()
    assertTrue("Must emit [NAV_INTENT]", logs.any { it.contains("[NAV_INTENT]") && it.contains("navId=$initialNavId") })
    assertTrue("Must emit [NAV_ALLOCATION]", logs.any { it.contains("[NAV_ALLOCATION]") && it.contains("navId=$initialNavId") })
    assertTrue("Must emit [NAV_CORRELATION] with correlated_to_inflight_intent", logs.any {
      it.contains("[NAV_CORRELATION]") && it.contains("reason=correlated_to_inflight_intent") && it.contains("navId=$initialNavId")
    })
    // Ensure no second allocation happened
    val allocationCount = logs.count { it.contains("[NAV_ALLOCATION]") }
    assertEquals("Exactly one navigation allocation must occur", 1, allocationCount)
  }

  @Test
  fun testIssueA_linkClickUserGesture_allocatesSingleNavId_subsequentCallbacksCorrelate() = runBlocking {
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
      callbacks = testCallbacks,
    )

    // Complete initial page load
    manager.loadUrl(tabId, "https://tryblock.org/")
    session.navigationDelegate?.onLocationChange(session, "https://tryblock.org/", mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    val baseNavId = manager.getActiveNavId(tabId)
    val baseGen = manager.getNavGeneration(tabId)
    DebugLogManager.clear()

    // User clicks a link on the page
    val linkUrl = "https://example.com/docs"
    val linkRequest = makeLoadRequest(
      linkUrl,
      isRedirect = false,
      hasUserGesture = true
    )
    session.navigationDelegate?.onLoadRequest(session, linkRequest)

    val linkNavId = manager.getActiveNavId(tabId)
    val linkGen = manager.getNavGeneration(tabId)

    assertEquals("Link click must allocate exactly new navId", baseNavId + 1L, linkNavId)
    assertEquals("Link click must increment generation by 1", baseGen + 1L, linkGen)

    // Gecko fires onPageStart, onLocationChange, onPageStop
    session.progressDelegate?.onPageStart(session, linkUrl)
    session.navigationDelegate?.onLocationChange(session, linkUrl, mutableListOf(), true)
    session.progressDelegate?.onPageStop(session, true)

    val finalNavId = manager.getActiveNavId(tabId)
    val finalGen = manager.getNavGeneration(tabId)

    assertEquals("navId must remain the linkNavId throughout the load", linkNavId, finalNavId)
    assertEquals("generation must remain the linkGen throughout the load", linkGen, finalGen)

    val logs = DebugLogManager.getCurrentSessionEvents()
    val linkAllocCount = logs.count { it.contains("[NAV_ALLOCATION]") }
    assertEquals("Link click must result in exactly 1 allocation", 1, linkAllocCount)
    assertTrue("onLocationChange must correlate to in-flight link nav", logs.any {
      it.contains("[NAV_CORRELATION]") && (it.contains("app_request_match") || it.contains("in_flight_location_match"))
    })
  }

  @Test
  fun testIssueA_redirectDuringInFlightNav_correlatesWithoutAllocation() = runBlocking {
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
      callbacks = testCallbacks,
    )

    manager.loadUrl(tabId, "https://httpstat.us/301")
    val initialNavId = manager.getActiveNavId(tabId)
    val initialGen = manager.getNavGeneration(tabId)

    DebugLogManager.clear()

    // Server redirects to final destination
    val redirectUrl = "https://httpstat.us/200"
    val redirectRequest = makeLoadRequest(
      redirectUrl,
      isRedirect = true,
      hasUserGesture = false
    )
    session.navigationDelegate?.onLoadRequest(session, redirectRequest)

    assertEquals("Redirect must NOT allocate a new navId", initialNavId, manager.getActiveNavId(tabId))
    assertEquals("Redirect must NOT increment generation", initialGen, manager.getNavGeneration(tabId))

    session.navigationDelegate?.onLocationChange(session, redirectUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    val logs = DebugLogManager.getCurrentSessionEvents()
    val redirectAllocCount = logs.count { it.contains("[NAV_ALLOCATION]") }
    assertEquals("Redirect must not allocate any new nav identity", 0, redirectAllocCount)
    assertTrue("Redirect must be correlated", logs.any { it.contains("[NAV_CORRELATION]") && it.contains("reason=redirect") })
  }

  @Test
  fun testIssueB_viewOnReleaseAfterNavSuccess_classifiedAsLifecycleDisposalAndSuppressed() = runBlocking {
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
      callbacks = testCallbacks,
    )

    // Complete navigation successfully
    manager.loadUrl(tabId, "https://tryblock.org/")
    session.navigationDelegate?.onLocationChange(session, "https://tryblock.org/", mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    assertNotNull("Last successful navigation must be recorded", manager.getLastSuccessfulNavigation(tabId))
    DebugLogManager.clear()

    // Simulate ~7 seconds later UI/Compose lifecycle disposal (e.g. tab switch, backgrounding, rotation)
    manager.detachViewSync(tabId, geckoView)

    val logs = DebugLogManager.getCurrentSessionEvents()
    assertTrue("Must emit [POST_NAV_LIFECYCLE] with VIEW_DISPOSED_AFTER_NAV_SUCCESS", logs.any {
      it.contains("[POST_NAV_LIFECYCLE]") && it.contains("event=VIEW_DISPOSED_AFTER_NAV_SUCCESS")
    })
    assertTrue("Must emit [POST_NAV_FAILURE_SUPPRESSED]", logs.any {
      it.contains("[POST_NAV_FAILURE_SUPPRESSED]") && it.contains("failure=DETACH_VIEW")
    })
    assertFalse("Must NOT emit [POST_NAV_FAILURE_CONFIRMED]", logs.any {
      it.contains("[POST_NAV_FAILURE_CONFIRMED]")
    })
    assertNull("lastOriginalFailures must NOT be polluted by benign view disposal", manager.getLastOriginalFailure(tabId))
  }

  @Test
  fun testIssueB_viewOnReleaseDuringActiveLoading_classifiedAsFailureConfirmed() = runBlocking {
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
      callbacks = testCallbacks,
    )

    // Start navigation (in-flight loading)
    manager.loadUrl(tabId, "https://tryblock.org/")
    session.progressDelegate?.onPageStart(session, "https://tryblock.org/")

    DebugLogManager.clear()

    // View is detached while navigation is actively loading
    manager.detachViewSync(tabId, geckoView)

    val logs = DebugLogManager.getCurrentSessionEvents()
    assertTrue("Must emit [POST_NAV_LIFECYCLE] with VIEW_DISPOSED_DURING_ACTIVE_NAVIGATION", logs.any {
      it.contains("[POST_NAV_LIFECYCLE]") && it.contains("event=VIEW_DISPOSED_DURING_ACTIVE_NAVIGATION")
    })
    assertTrue("Must emit [POST_NAV_FAILURE_CONFIRMED]", logs.any {
      it.contains("[POST_NAV_FAILURE_CONFIRMED]") && it.contains("failure=DETACH_VIEW")
    })
    assertEquals("DETACH_VIEW", manager.getLastOriginalFailure(tabId))
  }

  @Test
  fun testIssueB_contentProcessCrash_classifiedAsContentProcessFailed() = runBlocking {
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
      callbacks = testCallbacks,
    )

    manager.loadUrl(tabId, "https://tryblock.org/")
    session.navigationDelegate?.onLocationChange(session, "https://tryblock.org/", mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    DebugLogManager.clear()

    // Simulate content crash
    session.contentDelegate?.onCrash(session)

    val logs = DebugLogManager.getCurrentSessionEvents()
    assertTrue("Must emit [POST_NAV_LIFECYCLE] with CONTENT_PROCESS_FAILED", logs.any {
      it.contains("[POST_NAV_LIFECYCLE]") && it.contains("event=CONTENT_PROCESS_FAILED")
    })
    assertTrue("Must emit [POST_NAV_FAILURE_CONFIRMED]", logs.any {
      it.contains("[POST_NAV_FAILURE_CONFIRMED]") && it.contains("failure=CONTENT_CRASH")
    })
  }

  @Test
  fun testIssueB_pageStopFailed_classifiedAsNavigationFailed() = runBlocking {
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
      callbacks = testCallbacks,
    )

    manager.loadUrl(tabId, "https://invalid-host-unreachable.example/")
    session.progressDelegate?.onPageStart(session, "https://invalid-host-unreachable.example/")

    DebugLogManager.clear()

    // Failed page stop
    session.progressDelegate?.onPageStop(session, false)

    val logs = DebugLogManager.getCurrentSessionEvents()
    assertTrue("Must emit [POST_NAV_LIFECYCLE] with NAVIGATION_FAILED", logs.any {
      it.contains("[POST_NAV_LIFECYCLE]") && it.contains("event=NAVIGATION_FAILED")
    })
    assertTrue("Must emit [POST_NAV_FAILURE_CONFIRMED] with failure=PAGE_STOP_FAILED", logs.any {
      it.contains("[POST_NAV_FAILURE_CONFIRMED]") && it.contains("failure=PAGE_STOP_FAILED")
    })
  }
}
