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
 * Step 31 State-Machine Unit Tests
 * 
 * Verifies:
 * 1. Progress state is driven authoritatively by loading state.
 * 2. Stale progress events after onPageStop(loading=false) cannot resurrect progress to 100.
 * 3. about:blank during active content recovery is classified as TRANSIENT_ABOUT_BLANK and does not clear recovery.
 * 4. Normal about:blank outside recovery is classified as NORMAL_ABOUT_BLANK and behaves normally.
 * 5. Full recovery lifecycle ends cleanly with loading=false and progress=0.
 * 6. Recovery failure terminates loading=false and progress=0.
 * 7. Content process kill suppression is strictly maintained.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class Step31StateMachineFixTest {

  private lateinit var context: Context
  private lateinit var manager: GeckoEngineManager
  private lateinit var tabManager: TabManager

  private var capturedLoading: Boolean? = null
  private var capturedProgress: Int? = null
  private var capturedUrl: String? = null

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
   * TEST 1: onLoadingChange(true) -> progress > 0
   */
  @Test
  fun test1_LoadingChangeTrue_SetsInitialProgress() = runBlocking {
    val tab = tabManager.createTab("https://example.com/test1")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    manager.attachView(tab.id, GeckoView(context).apply { tag = tab.id }, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    session.progressDelegate?.onPageStart(session, "https://example.com/test1")
    assertEquals(true, capturedLoading)
    assertNotNull(capturedProgress)
    assertTrue("Progress must be > 0 when loading starts", (capturedProgress ?: 0) > 0)
  }

  /**
   * TEST 2: onLoadingChange(true) -> onProgressChange(90) -> onLoadingChange(false) -> progress == 0
   */
  @Test
  fun test2_LoadingLifecycle_EndsWithZeroProgress() = runBlocking {
    val tab = tabManager.createTab("https://example.com/test2")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    manager.attachView(tab.id, GeckoView(context).apply { tag = tab.id }, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    session.progressDelegate?.onPageStart(session, "https://example.com/test2")
    assertEquals(true, capturedLoading)

    session.progressDelegate?.onProgressChange(session, 90)
    assertEquals(90, capturedProgress)

    session.progressDelegate?.onPageStop(session, true)
    assertEquals(false, capturedLoading)
    assertEquals("Progress must be 0 after navigation completes", 0, capturedProgress)
  }

  /**
   * TEST 3: After loading=false, a stale onProgressChange(100) must NOT restore progress to 100.
   */
  @Test
  fun test3_StaleProgressAfterNavStop_IsIgnored() = runBlocking {
    val tab = tabManager.createTab("https://example.com/test3")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    manager.attachView(tab.id, GeckoView(context).apply { tag = tab.id }, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    session.progressDelegate?.onPageStart(session, "https://example.com/test3")
    session.progressDelegate?.onPageStop(session, true)
    assertEquals(false, capturedLoading)
    assertEquals(0, capturedProgress)

    // Fire stale progress event after stop
    session.progressDelegate?.onProgressChange(session, 100)
    assertEquals("Stale progress event must not restore progress when loading is false", 0, capturedProgress)
  }

  /**
   * TEST 4: Normal about:blank navigation outside recovery behaves normally.
   */
  @Test
  fun test4_NormalAboutBlank_BehavesNormally() = runBlocking {
    val tab = tabManager.createTab("about:blank")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    manager.attachView(tab.id, GeckoView(context).apply { tag = tab.id }, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    session.navigationDelegate?.onLocationChange(session, "about:blank", mutableListOf(), false)
    session.progressDelegate?.onPageStart(session, "about:blank")
    session.progressDelegate?.onPageStop(session, true)

    assertEquals(false, capturedLoading)
    assertEquals(0, capturedProgress)
    assertFalse("No recovery should be active for normal about:blank", manager.hasActiveRecovery(tab.id))

    val logs = DebugLogManager.logs.value
    assertTrue("Must log normal about:blank classification",
      logs.any { it.contains("[FORENSIC][RECOVERY_URL_STATE]") && it.contains("classification=NORMAL_ABOUT_BLANK") }
    )
  }

  /**
   * TEST 5: about:blank during active content recovery is classified as TRANSIENT and must NOT clear recovery.
   */
  @Test
  fun test5_TransientAboutBlankDuringRecovery_DoesNotClearRecovery() = runBlocking {
    val tab = tabManager.createTab("https://news.ycombinator.com/")
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    manager.attachView(tab.id, GeckoView(context).apply { tag = tab.id }, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    manager.loadUrl(tab.id, "https://news.ycombinator.com/")
    session.contentDelegate?.onKill(session)

    assertTrue("Recovery must be active after onKill", manager.hasActiveRecovery(tab.id))

    // Transient about:blank during recovery
    session.navigationDelegate?.onLocationChange(session, "about:blank", mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)

    assertTrue("Recovery MUST remain active during transient about:blank stop", manager.hasActiveRecovery(tab.id))
    
    val logs = DebugLogManager.logs.value
    assertTrue("Must classify as TRANSIENT_ABOUT_BLANK",
      logs.any { it.contains("[FORENSIC][RECOVERY_URL_STATE]") && it.contains("classification=TRANSIENT_ABOUT_BLANK") }
    )
  }

  /**
   * TEST 6: Recovery: KILL -> RECOVERY_START -> about:blank -> target URL -> success ends cleanly.
   */
  @Test
  fun test6_FullRecoverySequence_EndsCleanly() = runBlocking {
    val targetUrl = "https://news.ycombinator.com/item?id=123"
    val tab = tabManager.createTab(targetUrl)
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    manager.attachView(tab.id, GeckoView(context).apply { tag = tab.id }, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    manager.loadUrl(tab.id, targetUrl)
    session.contentDelegate?.onKill(session)
    assertTrue(manager.hasActiveRecovery(tab.id))

    // 1. Transient about:blank
    session.navigationDelegate?.onLocationChange(session, "about:blank", mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, true)
    assertTrue("Recovery stays active during transient about:blank", manager.hasActiveRecovery(tab.id))

    // 2. Target URL load
    session.navigationDelegate?.onLocationChange(session, targetUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStart(session, targetUrl)
    session.progressDelegate?.onPageStop(session, true)

    // Verification
    assertFalse("Recovery must be inactive after target success", manager.hasActiveRecovery(tab.id))
    assertEquals("Loading must be false", false, capturedLoading)
    assertEquals("Progress must be 0", 0, capturedProgress)
    assertEquals("Target URL must be preserved", targetUrl, capturedUrl)
  }

  /**
   * TEST 7: A failed recovery must terminate loading and progress cleanly.
   */
  @Test
  fun test7_FailedRecovery_TerminatesLoadingAndProgress() = runBlocking {
    val targetUrl = "https://invalid.broken.domain/fail"
    val tab = tabManager.createTab(targetUrl)
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    manager.attachView(tab.id, GeckoView(context).apply { tag = tab.id }, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    manager.loadUrl(tab.id, targetUrl)
    session.contentDelegate?.onKill(session)
    assertTrue(manager.hasActiveRecovery(tab.id))

    // Target URL fails to load
    session.navigationDelegate?.onLocationChange(session, targetUrl, mutableListOf(), false)
    session.progressDelegate?.onPageStop(session, false)

    assertFalse("Recovery must be inactive after failure", manager.hasActiveRecovery(tab.id))
    assertEquals("Loading must be terminated on failure", false, capturedLoading)
    assertEquals("Progress must be 0 on failure", 0, capturedProgress)
  }

  /**
   * TEST 8: Repeated Gecko kills in the same generation must still obey max-attempt suppression.
   */
  @Test
  fun test8_RepeatedKills_ObeysSuppression() = runBlocking {
    val targetUrl = "https://news.ycombinator.com/"
    val tab = tabManager.createTab(targetUrl)
    val session = GeckoSession(GeckoSessionSettings.Builder().build())
    manager.setSessionForTesting(tab.id, session)
    manager.attachView(tab.id, GeckoView(context).apply { tag = tab.id }, PrivacyProfile.SHIELD, false, callbacks = testCallbacks)

    val reloadCount = mutableListOf<String>()
    manager.uriLoaderForTest = { _, _, url -> reloadCount.add(url) }
    manager.loadUrl(tab.id, targetUrl)
    reloadCount.clear()

    // 1st Kill -> recovers
    session.contentDelegate?.onKill(session)
    assertEquals("First kill initiates recovery reload", 1, reloadCount.size)
    reloadCount.clear()

    // 2nd Kill in same generation -> suppressed
    session.contentDelegate?.onKill(session)
    assertEquals("Second kill in same generation must be suppressed", 0, reloadCount.size)

    val logs = DebugLogManager.logs.value
    assertTrue("Must log CONTENT_RECOVERY_SUPPRESSED for max_attempts_exceeded",
      logs.any { it.contains("[FORENSIC][CONTENT_RECOVERY_SUPPRESSED]") && it.contains("reason=max_attempts_exceeded") }
    )
  }
}
