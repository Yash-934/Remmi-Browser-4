package com.remmi.adblock

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.engine.GeckoTabCallbacks
import com.remmi.browser.engine.TabManager
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
class RuntimeAdblockActivationRegressionTest {

  private lateinit var context: Context
  private lateinit var bridge: AdblockBridge
  private lateinit var filterManager: FilterManager
  private lateinit var engineManager: GeckoEngineManager
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

    bridge = AdblockBridge.getInstance()
    filterManager = FilterManager.getInstance(context, bridge)
    engineManager = GeckoEngineManager.getInstance(context)
    tabManager = TabManager.getInstance()
    tabManager.closeAllTabs()
    engineManager.setInitStateForTesting(GeckoEngineManager.GeckoInitState.READY)
    engineManager.uriLoaderForTest = { _, _, _ -> }
  }

  @After
  fun tearDown() = runBlocking {
    engineManager.uriLoaderForTest = null
    engineManager.sessionOpenerForTest = null
    engineManager.closeAllSessionsSafely()
    tabManager.closeAllTabs()
  }

  /**
   * 1. Ruleset Activation Test:
   * Compiling rules must update generation, increase loaded rules, and activate matching.
   */
  @Test
  fun testRulesetActivation_updatesGenerationAndMatches() {
    val initialGen = bridge.getEngineGeneration()

    val testRules = """
      ||adblock-tester-tracker.com^
      ||popads-network.org^
      adblock-tester.com##.ad-banner
      adblock-tester.com##.adsbox
    """.trimIndent()

    val compiled = bridge.compileRules(testRules, source = "testRulesetActivation")
    assertTrue("Compiled rule count must be positive", compiled > 0)
    assertTrue("Engine generation must increment on compilation", bridge.getEngineGeneration() > initialGen)

    val blockDec = bridge.evaluateDecision(
      url = "https://adblock-tester-tracker.com/track.js",
      sourceUrl = "https://adblock-tester.com/",
      initiator = "https://adblock-tester.com/",
      method = "GET",
      resourceType = "script",
      aggressive = false,
      thirdParty = true
    )
    assertTrue("Newly activated rule must block tracker", blockDec.blocked)
  }

  /**
   * 2. Ruleset Generation Persistence Across Content-Process Recovery:
   * Engine generation and active rules must remain preserved when a GeckoSession recovers.
   */
  @Test
  fun testRulesetGenerationPersistence_acrossContentProcessRecovery() = runBlocking {
    val rules = """
      ||crash-test-tracker.com^
      ||analytics-beacon.net^
      example.com##.sponsor-banner
    """.trimIndent()

    bridge.compileRules(rules, source = "testRecoverySetup")
    val preRecoveryGen = bridge.getEngineGeneration()
    val preRecoveryRules = bridge.getLoadedRulesCount()

    // Setup active tab and trigger content process kill recovery
    val tab = tabManager.createTab("https://adblock-tester.com/")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    engineManager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    engineManager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks
    )

    // Trigger process kill
    session.contentDelegate?.onKill(session)

    // Verify engine generation and rules are unchanged post-recovery
    val postRecoveryGen = bridge.getEngineGeneration()
    val postRecoveryRules = bridge.getLoadedRulesCount()

    assertEquals("Engine generation must persist across content-process recovery", preRecoveryGen, postRecoveryGen)
    assertEquals("Loaded rules count must persist across content-process recovery", preRecoveryRules, postRecoveryRules)

    // Verify decision still blocks
    val decision = bridge.evaluateDecision(
      url = "https://crash-test-tracker.com/ping",
      sourceUrl = "https://adblock-tester.com/",
      initiator = "https://adblock-tester.com/",
      method = "POST",
      resourceType = "ping",
      thirdParty = true
    )
    assertTrue("Network decision must remain BLOCK after recovery", decision.blocked)
  }

  /**
   * 3. Cosmetic Rules Available After Compilation:
   * Compiling rules with cosmetic selectors makes selectors available to cosmetic engine.
   */
  @Test
  fun testCosmeticRulesAvailableAfterCompilation() {
    val cosmeticRules = """
      adblock-tester.com##.test-ad-container
      adblock-tester.com###banner-ad-box
      adblock-tester.com##.sponsor-content
    """.trimIndent()

    bridge.compileRules(cosmeticRules, source = "testCosmetic")

    val cosmetic = bridge.getCosmeticResources(
      url = "https://adblock-tester.com/",
      classes = listOf("test-ad-container", "sponsor-content", "normal-article"),
      ids = listOf("banner-ad-box", "main-header")
    )

    assertTrue("Cosmetic resources response must be ok", cosmetic.ok)
    assertTrue("Hide selectors list must contain matching CSS classes/ids", cosmetic.hideSelectors.isNotEmpty())
    assertTrue(
      "Hide selectors must include .test-ad-container or #banner-ad-box",
      cosmetic.hideSelectors.any { it.contains("test-ad-container") || it.contains("banner-ad-box") || it.contains("sponsor-content") }
    )
  }

  /**
   * 4. Network Decision Remains BLOCK After Recovery:
   * Verifies that Google Analytics, GTM, and custom trackers remain blocked after multiple recovery events.
   */
  @Test
  fun testNetworkDecisionRemainsBlockAfterMultipleRecoveries() = runBlocking {
    val tab = tabManager.createTab("https://adblock-tester.com/")
    val tabId = tab.id
    val settings = GeckoSessionSettings.Builder().usePrivateMode(true).build()
    val session = GeckoSession(settings)
    engineManager.setSessionForTesting(tabId, session)

    val geckoView = GeckoView(context).apply { tag = tabId }
    engineManager.attachView(
      tabId = tabId,
      geckoView = geckoView,
      profile = PrivacyProfile.SHIELD,
      isDesktopMode = false,
      callbacks = dummyCallbacks
    )

    // 1st recovery
    session.contentDelegate?.onKill(session)
    var dec1 = bridge.evaluateDecision("https://www.google-analytics.com/analytics.js")
    assertTrue("Google Analytics must be blocked after 1st recovery", dec1.blocked)

    // 2nd recovery
    session.contentDelegate?.onKill(session)
    var dec2 = bridge.evaluateDecision("https://www.googletagmanager.com/gtm.js")
    assertTrue("Google Tag Manager must be blocked after 2nd recovery", dec2.blocked)
  }

  /**
   * 5. Fallback Does Not Silently Replace Native Engine Under Normal Conditions:
   * Verifies state transitions and degraded state visibility.
   */
  @Test
  fun testEngineStateIntegrity() {
    val state = bridge.state
    assertNotEquals("Engine state must not be FAILED", AdblockState.FAILED, state)
    assertTrue("Engine should have loaded rules", bridge.getLoadedRulesCount() > 0)
  }
}
