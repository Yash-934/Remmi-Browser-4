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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class Step15ForensicValidationTest {

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
   * TEST A: Normal DuckDuckGo search.
   * Expected: search URL loads successfully.
   */
  @Test
  fun testA_normalDuckDuckGoSearch_loadsSuccessfully() = runBlocking {
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

    val searchUrl = "https://html.duckduckgo.com/html/?q=kotlin"
    manager.loadUrl(tabId, searchUrl)

    assertEquals("Expected exactly 1 URL loaded for search", 1, loadedUrls.size)
    assertEquals(searchUrl, loadedUrls[0])

    val logs = DebugLogManager.getCurrentSessionEvents()
    assertTrue("Logs must contain GECKO_NAV_DISPATCH for search URL", logs.any { it.contains("[GECKO_NAV_DISPATCH]") && it.contains("duckduckgo.com") })
  }

  /**
   * TEST B: Tap one external result.
   * Expected: NAV_LOCATION changes to external destination.
   * Generation must correctly represent the user navigation.
   * No restoration to old DuckDuckGo URL.
   */
  @Test
  fun testB_tapExternalResult_updatesLocationAndGeneration_noDuckDuckGoRestoration() = runBlocking {
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

    val searchUrl = "https://html.duckduckgo.com/html/?q=kotlin"
    manager.loadUrl(tabId, searchUrl)
    session.navigationDelegate?.onLocationChange(session, searchUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)
    loadedUrls.clear()

    // User taps external search result
    val destinationUrl = "https://kotlinlang.org/"
    session.navigationDelegate?.onLocationChange(session, destinationUrl, mutableListOf(), true)
    session.progressDelegate?.onPageStop(session, true)

    val logs = DebugLogManager.getCurrentSessionEvents()
    assertTrue("Logs must contain IN_PAGE_NAV for external destination", logs.any { it.contains("[IN_PAGE_NAV]") && it.contains(destinationUrl) })
    assertTrue("Logs must contain NAV_LOCATION for destination URL", logs.any { it.contains("[NAV_LOCATION]") && it.contains(destinationUrl) })
    assertEquals("No automated reload should have occurred on normal navigation", 0, loadedUrls.size)

    // Trigger kill to verify target is destinationUrl, NOT DuckDuckGo searchUrl
    session.contentDelegate?.onKill(session)
    assertEquals(1, loadedUrls.size)
    assertEquals("Recovery target must be the destination URL, NOT the old search URL", destinationUrl, loadedUrls[0])
  }

  /**
   * TEST C: While external destination is displayed, trigger/observe content-process termination.
   * Expected: recovery target == external destination URL.
   * about:blank must not be treated as recovery success.
   */
  @Test
  fun testC_terminationOnDestination_recoveryTargetIsDestination_aboutBlankNotSuccess() = runBlocking {
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

    // Search and navigate
    val searchUrl = "https://html.duckduckgo.com/html/?q=kotlin"
    manager.loadUrl(tabId, searchUrl)
    session.navigationDelegate?.onLocationChange(session, searchUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    val destinationUrl = "https://kotlinlang.org/"
    session.navigationDelegate?.onLocationChange(session, destinationUrl, mutableListOf(), true)
    session.progressDelegate?.onPageStop(session, true)
    loadedUrls.clear()

    // Trigger process termination
    session.contentDelegate?.onKill(session)

    assertEquals("Recovery MUST dispatch reload for destination URL", 1, loadedUrls.size)
    assertEquals("Recovery target must be destination URL, NOT search URL", destinationUrl, loadedUrls[0])
    assertTrue("Recovery must be active", manager.hasActiveRecovery(tabId))

    // Docshell re-initializes and emits about:blank
    session.navigationDelegate?.onLocationChange(session, "about:blank", mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    // Assert about:blank did NOT complete recovery
    assertTrue("Recovery MUST still be active after docshell about:blank", manager.hasActiveRecovery(tabId))
    val logs = DebugLogManager.getCurrentSessionEvents()
    assertFalse("Must NOT log CONTENT_RECOVERY_NAV_SUCCESS for about:blank", logs.any { it.contains("[CONTENT_RECOVERY_NAV_SUCCESS]") })
  }

  /**
   * TEST D: Successful recovery sequence.
   * Expected sequence:
   * CONTENT_KILL -> CONTENT_RECOVERY_START -> CONTENT_RECOVERY_DISPATCHED -> NAV_LOCATION target -> CONTENT_RECOVERY_NAV_IN_FLIGHT -> NAV_STOP target success=true -> CONTENT_RECOVERY_NAV_SUCCESS
   */
  @Test
  fun testD_fullRecoverySequence_completesSuccessfully() = runBlocking {
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

    val destinationUrl = "https://kotlinlang.org/"
    manager.loadUrl(tabId, destinationUrl)
    session.navigationDelegate?.onLocationChange(session, destinationUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    DebugLogManager.clear()

    // 1. CONTENT_KILL
    session.contentDelegate?.onKill(session)

    // 2. Docshell emits about:blank
    session.navigationDelegate?.onLocationChange(session, "about:blank", mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    // 3. Navigation reaches destination target
    session.navigationDelegate?.onLocationChange(session, destinationUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    assertFalse("Recovery must now be completed", manager.hasActiveRecovery(tabId))

    val logs = DebugLogManager.getCurrentSessionEvents()
    assertTrue("Logs must contain CONTENT_KILL", logs.any { it.contains("[CONTENT_KILL]") })
    assertTrue("Logs must contain CONTENT_RECOVERY_START", logs.any { it.contains("[CONTENT_RECOVERY_START]") })
    assertTrue("Logs must contain CONTENT_RECOVERY_DISPATCHED", logs.any { it.contains("[CONTENT_RECOVERY_DISPATCHED]") })
    assertTrue("Logs must contain CONTENT_RECOVERY_NAV_IN_FLIGHT", logs.any { it.contains("[CONTENT_RECOVERY_NAV_IN_FLIGHT]") })
    assertTrue("Logs must contain CONTENT_RECOVERY_NAV_SUCCESS", logs.any { it.contains("[CONTENT_RECOVERY_NAV_SUCCESS]") })
  }

  /**
   * TEST E: Second independent recovery in the same generation.
   * Expected: NEW recovery is allowed.
   * No max_attempts_exceeded suppression caused by previous successful recovery.
   */
  @Test
  fun testE_secondIndependentRecovery_allowedWithoutSuppression() = runBlocking {
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

    val destinationUrl = "https://kotlinlang.org/"
    manager.loadUrl(tabId, destinationUrl)
    session.navigationDelegate?.onLocationChange(session, destinationUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    // First kill and recovery
    session.contentDelegate?.onKill(session)
    session.navigationDelegate?.onLocationChange(session, destinationUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)
    assertFalse("First recovery complete", manager.hasActiveRecovery(tabId))

    loadedUrls.clear()
    DebugLogManager.clear()

    // Second independent kill in same generation
    session.contentDelegate?.onKill(session)

    assertEquals("Second recovery MUST be dispatched", 1, loadedUrls.size)
    assertEquals(destinationUrl, loadedUrls[0])
    assertTrue("Second recovery must be active", manager.hasActiveRecovery(tabId))

    val logs = DebugLogManager.getCurrentSessionEvents()
    assertFalse("Must NOT be suppressed by previous recovery", logs.any { it.contains("[CONTENT_RECOVERY_SUPPRESSED]") })
    assertTrue("Must log CONTENT_RECOVERY_START for second recovery", logs.any { it.contains("[CONTENT_RECOVERY_START]") })
  }

  /**
   * TEST F: Check view lifecycle during external navigation.
   * Invariant: one active View <-> one GeckoSession <-> one tab owner.
   * No TAG_MISMATCH, no tag=null + attachedOwner still active.
   */
  @Test
  fun testF_viewLifecycleDuringExternalNavigation_maintainsInvariants() = runBlocking {
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
    assertEquals("GeckoView tag must match tabId", tabId, geckoView.tag)

    // Navigate to external result
    val destinationUrl = "https://kotlinlang.org/"
    session.navigationDelegate?.onLocationChange(session, destinationUrl, mutableListOf(), true)
    session.progressDelegate?.onPageStop(session, true)

    // Verify invariants during navigation
    assertTrue("View must remain attached", manager.isViewAttached(tabId))
    assertEquals(tabId, geckoView.tag)

    // Detach via detachViewSync
    DebugLogManager.clear()
    manager.detachViewSync(tabId, geckoView)

    assertFalse("View must be detached", manager.isViewAttached(tabId))
    assertNull("View tag must be cleared", geckoView.tag)

    val logs = DebugLogManager.getCurrentSessionEvents()
    assertFalse("Must have zero invariant violations", logs.any { it.contains("[VIEW_INVARIANT_VIOLATION]") })
  }

  /**
   * TEST G: Check duplicate-navigation protection.
   * Proves whether GECKO_NAV_SKIPPED_DUPLICATE suppresses harmless recomposition duplicates
   * and does NOT suppress required recovery or navigation.
   */
  @Test
  fun testG_duplicateNavigationProtection_suppressesRecomposition_doesNotBlockRecovery() = runBlocking {
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

    val url = "https://example.com/page"
    manager.loadUrl(tabId, url)
    assertEquals(1, loadedUrls.size)

    // 1. Simulating harmless Compose recomposition with the same URL
    DebugLogManager.clear()
    manager.loadUrl(tabId, url)
    assertEquals("Harmless recomposition duplicate must be suppressed", 1, loadedUrls.size)
    val recomposeLogs = DebugLogManager.getCurrentSessionEvents()
    assertTrue("Should log GECKO_NAV_SKIPPED_DUPLICATE for recomposition", recomposeLogs.any { it.contains("[GECKO_NAV_SKIPPED_DUPLICATE]") })

    // 2. Kill occurs -> required recovery must NOT be suppressed
    DebugLogManager.clear()
    session.contentDelegate?.onKill(session)
    assertEquals("Recovery MUST dispatch even though targetUrl is the same", 2, loadedUrls.size)
    val recoveryLogs = DebugLogManager.getCurrentSessionEvents()
    assertFalse("Recovery must NOT be skipped as duplicate", recoveryLogs.any { it.contains("[GECKO_NAV_SKIPPED_DUPLICATE]") })
    assertTrue("Recovery should be dispatched", recoveryLogs.any { it.contains("[CONTENT_RECOVERY_DISPATCHED]") })
  }

  /**
   * TEST H: Check whether onLocationChange generation increments only for genuine new navigations
   * and not repeated callbacks or internal redirects for same URL.
   */
  @Test
  fun testH_onLocationChange_generationIncrementsOnlyOnGenuineNewNavigation() = runBlocking {
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

    val initialUrl = "https://example.com/initial"
    DebugLogManager.clear()
    manager.loadUrl(tabId, initialUrl)

    val logsAfterLoad = DebugLogManager.getCurrentSessionEvents()
    val initialDispatch = logsAfterLoad.find { it.contains("[GECKO_NAV_DISPATCH]") }
    assertNotNull("Must find initial dispatch log", initialDispatch)

    // 1. onLocationChange for the exact same initial URL (e.g. Docshell confirming loaded page)
    DebugLogManager.clear()
    session.navigationDelegate?.onLocationChange(session, initialUrl, mutableListOf(), false)
    val logsAfterSameUrl = DebugLogManager.getCurrentSessionEvents()
    assertFalse("Must NOT log IN_PAGE_NAV for same URL confirmation", logsAfterSameUrl.any { it.contains("[IN_PAGE_NAV]") })

    // 2. Genuine new user navigation to different URL
    val nextUrl = "https://example.com/destination"
    session.navigationDelegate?.onLocationChange(session, nextUrl, mutableListOf(), true)
    val logsAfterNewNav = DebugLogManager.getCurrentSessionEvents()
    assertTrue("Must log IN_PAGE_NAV for genuine new URL navigation", logsAfterNewNav.any { it.contains("[IN_PAGE_NAV]") && it.contains(nextUrl) })

    // 3. Repeated callback for destination URL
    DebugLogManager.clear()
    session.navigationDelegate?.onLocationChange(session, nextUrl, mutableListOf(), false)
    val logsAfterRepeat = DebugLogManager.getCurrentSessionEvents()
    assertFalse("Must NOT log IN_PAGE_NAV for duplicate callback on same URL", logsAfterRepeat.any { it.contains("[IN_PAGE_NAV]") })
  }
}
