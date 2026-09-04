package com.remmi.browser.engine

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.security.ContainerType
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.SecurityLevel
import kotlinx.coroutines.Dispatchers
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
class BrowserViewAttachmentRaceTest {

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
    manager = GeckoEngineManager.getInstance(context)
    tabManager = TabManager.getInstance()
    tabManager.closeAllTabs()
    manager.setInitStateForTesting(GeckoEngineManager.GeckoInitState.READY)
  }

  @After
  fun tearDown() = runBlocking {
    manager.uriLoaderForTest = null
    manager.closeAllSessionsSafely()
    tabManager.closeAllTabs()
  }

  /**
   * TEST A: BrowserViewAttachmentRaceTest
   * - create GeckoView
   * - request navigation immediately
   * - assert loadUri does NOT happen before setSession
   * - assert loadUri happens exactly once after attachment.
   */
  @Test
  fun testAttachmentRace_loadUriNeverBeforeSetSession_andDispatchedOnceAfter() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val operations = mutableListOf<String>()
    manager.uriLoaderForTest = { tId, sess, url ->
      operations.add("loadUri:$url")
    }

    val geckoView = GeckoView(context)
    assertFalse("View should not be attached yet", manager.isViewAttached(tabId))

    // 1. Request navigation immediately before attachView completes
    val targetUrl = "https://example.com"
    manager.loadUrl(tabId, targetUrl)

    // 2. Assert loadUri did NOT happen before setSession
    assertTrue("loadUri MUST NOT be called before attachment", operations.isEmpty())
    assertEquals(targetUrl, manager.getPendingNavigation(tabId))

    // 3. Complete view attachment
    operations.add("pre_attach")
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      securityLevel = SecurityLevel.STANDARD,
      containerType = ContainerType.NORMAL,
      callbacks = dummyCallbacks,
    )
    operations.add("post_attach")

    // 4. Assert setSession occurred and loadUri happened exactly once after attachment
    assertTrue("View must be marked attached", manager.isViewAttached(tabId))
    assertEquals(session, geckoView.session)
    assertEquals("Only one loadUri should have occurred", 1, operations.count { it.startsWith("loadUri:") })
    assertEquals("loadUri:https://example.com", operations.find { it.startsWith("loadUri:") })
    assertNull("Pending navigation should be cleared after dispatch", manager.getPendingNavigation(tabId))
  }

  /**
   * TEST B: DuplicateNavigationTest
   * - send same URL repeatedly through recomposition/state updates
   * - assert only one loadUri.
   */
  @Test
  fun testDuplicateNavigation_repeatedUrlCalls_dispatchesExactlyOnce() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val dispatchedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url ->
      dispatchedUrls.add(url)
    }

    val geckoView = GeckoView(context)
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    // Send same URL 5 times (simulating Compose recomposition / state updates)
    val testUrl = "https://example.com/test"
    repeat(5) {
      manager.loadUrl(tabId, testUrl)
    }

    assertEquals("Duplicate navigation must only dispatch once", 1, dispatchedUrls.size)
    assertEquals(testUrl, dispatchedUrls[0])
    assertEquals(testUrl, manager.getLastDispatchedUrl(tabId))
  }

  /**
   * TEST C: LatestNavigationWinsTest
   * - queue URL A
   * - before attachment queue URL B
   * - assert only URL B is dispatched.
   */
  @Test
  fun testLatestNavigationWins_supersededUrlNeverDispatched() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val dispatchedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url ->
      dispatchedUrls.add(url)
    }

    // Queue URL A, then URL B before view is attached
    manager.loadUrl(tabId, "https://url-a.com")
    manager.loadUrl(tabId, "https://url-b.com")

    assertEquals("https://url-b.com", manager.getPendingNavigation(tabId))
    assertTrue("No URLs should be dispatched while unattached", dispatchedUrls.isEmpty())

    // Now attach view
    val geckoView = GeckoView(context)
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    assertEquals("Exactly one URL should be dispatched", 1, dispatchedUrls.size)
    assertEquals("https://url-b.com", dispatchedUrls[0])
    assertFalse("URL A must never be dispatched", dispatchedUrls.contains("https://url-a.com"))
  }

  /**
   * TEST D: RecreatedViewSameSessionTest
   * - destroy/recreate GeckoView for same tab
   * - assert same GeckoSession is reused
   * - assert navigation still works.
   */
  @Test
  fun testRecreatedViewSameSession_reusesExistingGeckoSessionAndNavigates() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val dispatchedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url ->
      dispatchedUrls.add(url)
    }

    // View 1 attaches
    val geckoView1 = GeckoView(context)
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView1,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )
    assertSame("GeckoView1 has initial session", session, geckoView1.session)

    manager.loadUrl(tabId, "https://first-view.com")
    assertEquals(1, dispatchedUrls.size)
    assertEquals("https://first-view.com", dispatchedUrls.last())

    // View 1 detaches (e.g. user goes to new tab or screen rotation)
    manager.detachView(tabId, geckoView1)
    assertFalse("Tab should be detached", manager.isViewAttached(tabId))

    // View 2 attaches for the SAME tab
    val geckoView2 = GeckoView(context)
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView2,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    // Invariant: MUST reuse the exact same GeckoSession instance
    assertSame("GeckoView2 must reuse the same session identity", session, geckoView2.session)
    assertTrue("Tab is attached with new view", manager.isViewAttached(tabId))

    // Navigation on recreated view still works cleanly
    manager.loadUrl(tabId, "https://second-view.com")
    assertEquals(2, dispatchedUrls.size)
    assertEquals("https://second-view.com", dispatchedUrls.last())
  }

  /**
   * TEST E: WarmSessionNavigationTest
   * - already attached session
   * - navigate to URL
   * - assert no extra attachment/recreation is required.
   */
  @Test
  fun testWarmSessionNavigation_alreadyAttachedSessionNavigatesDirectly() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val dispatchedUrls = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url ->
      dispatchedUrls.add(url)
    }

    val geckoView = GeckoView(context)
    manager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks,
    )

    assertTrue("Session must be warm/attached", manager.isViewAttached(tabId))
    val sessionIdentityBefore = System.identityHashCode(geckoView.session)

    // Direct warm navigation
    manager.loadUrl(tabId, "https://warm-navigation.org")

    assertEquals(1, dispatchedUrls.size)
    assertEquals("https://warm-navigation.org", dispatchedUrls[0])
    assertEquals("Session identity must not change during warm navigation", sessionIdentityBefore, System.identityHashCode(geckoView.session))
    assertNull("No pending navigation left queued", manager.getPendingNavigation(tabId))
  }
}
