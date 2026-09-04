package com.remmi.browser.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remmi.browser.security.ContainerType
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.SecurityLevel
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Step36TerminalRecoveryTest {

  private lateinit var manager: GeckoEngineManager
  private lateinit var tabManager: TabManager
  private lateinit var context: Application

  private val testCallbacks = object : GeckoTabCallbacks {
    
    
    
    
    
    
    
    
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
  fun testPostSuccessRecoveryAndAboutBlank() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    manager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    manager.attachView(tabId, geckoView, PrivacyProfile.SHIELD, false, SecurityLevel.STANDARD, ContainerType.NORMAL, testCallbacks)

    // 1. Initial successful navigation
    val url = "https://example.com/terminal-success"
    manager.loadUrl(tabId, url)
    session.navigationDelegate?.onLoadRequest(session, makeLoadRequest(url, false, true))
    session.navigationDelegate?.onLocationChange(session, url, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    val events1 = DebugLogManager.getCurrentSessionEvents()
    assertTrue(events1.any { it.contains("[NAV_STOP]") && it.contains("success=true") })
    assertTrue(events1.none { it.contains("POST_NAV_FAILURE_CONFIRMED") })

    DebugLogManager.clear()
        
    // 2. Crash post-success
    session.contentDelegate?.onCrash(session)
    manager.checkPostNavFailure(tabId, "CONTENT_CRASH", url)
        
    val events2 = DebugLogManager.getCurrentSessionEvents()
        
    // Must NOT emit POST_NAV_FAILURE_CONFIRMED for the content crash
    if (!events2.none { it.contains("POST_NAV_FAILURE_CONFIRMED") }) { println("events2 output: $events2") }
    if (!events2.none { it.contains("POST_NAV_FAILURE_CONFIRMED") }) { println("events2 output: $events2") }
    assertTrue(events2.none { it.contains("POST_NAV_FAILURE_CONFIRMED") })
    // Must emit SUPPRESSED reason=content_kill_after_terminal_success
    assertTrue(events2.any { it.contains("POST_NAV_FAILURE_SUPPRESSED") && it.contains("content_kill_after_terminal_success") })
    // Must trigger RECOVERY_START
    assertTrue(events2.any { it.contains("CONTENT_RECOVERY_START") })
        
    DebugLogManager.clear()

    // 3. During recovery, emit about:blank
    session.navigationDelegate?.onLocationChange(session, "about:blank", mutableListOf(), false)
        
    val events3 = DebugLogManager.getCurrentSessionEvents()
    // Must NOT emit ABOUT_BLANK confirmed failure
    assertTrue(events3.none { it.contains("POST_NAV_FAILURE_CONFIRMED") })
    // Must emit SUPPRESSED for transient about:blank
    assertTrue(events3.any { it.contains("POST_NAV_FAILURE_SUPPRESSED") && it.contains("transient_recovery_blank") })
        
    DebugLogManager.clear()
        
    // 4. Recovery completes successfully
    session.navigationDelegate?.onLocationChange(session, url, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)
        
    val events4 = DebugLogManager.getCurrentSessionEvents()
    assertTrue(events4.any { it.contains("CONTENT_RECOVERY_NAV_SUCCESS") })
        
    DebugLogManager.clear()
        
    // 5. Normal view disposal after SUCCESS (should remain suppressed)
    manager.detachViewSync(tabId, geckoView)
    manager.checkPostNavFailure(tabId, "DETACH_VIEW", url)
        
    val events5 = DebugLogManager.getCurrentSessionEvents()
    assertTrue(events5.none { it.contains("POST_NAV_FAILURE_CONFIRMED") })
    assertTrue(events5.any { it.contains("POST_NAV_FAILURE_SUPPRESSED") && it.contains("view_disposed_after_terminal_success") })
  }
}
