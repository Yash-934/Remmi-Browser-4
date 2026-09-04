package com.remmi.browser.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.remmi.adblock.AdblockBridge
import com.remmi.adblock.BlockExtension
import com.remmi.browser.model.WebContextMenuData
import com.remmi.browser.security.AntiFingerprint
import com.remmi.browser.security.ContainerType
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.NetworkHardening
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.SecurityLevel
import com.remmi.browser.util.PdfPrintHelper
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import java.io.File
import kotlin.coroutines.resume

sealed class CloseResult {
  object Success : CloseResult()
  object NotFound : CloseResult()
  object AlreadyClosed : CloseResult()
  data class Failed(val error: Throwable) : CloseResult()
}

/**
 * Interface for tab event subscriptions.
 * Decouples Compose UI layers from raw GeckoView delegates.
 */
interface GeckoTabCallbacks {
  fun onUrlChange(url: String) {}
  fun onTitleChange(title: String) {}
  fun onProgressChange(progress: Int) {}
  fun onLoadingChange(isLoading: Boolean) {}
  fun onSecurityChange(isSecure: Boolean) {}
  fun onNavStateChange(canGoBack: Boolean, canGoForward: Boolean) {}
  fun onTrackerBlocked(url: String, type: String) {}
  fun onExternalResponse(response: WebResponse) {}
  fun onContextMenu(data: WebContextMenuData) {}
  fun onScrollChanged(scrollX: Int, scrollY: Int, isScrollingDown: Boolean) {}
}

/**
 * GeckoEngineManager & GeckoSessionController
 * The EXCLUSIVE SINGLE OWNER of:
 * - GeckoRuntime lifecycle
 * - GeckoSession creation, registry, and destruction (NEVER exposed to UI callers)
 * - Delegate registration and thread enforcement
 * - View attachment/detachment for GeckoView
 * - Navigation commands (loadUrl, goBack, goForward, reload, stopLoading, findInPage, evaluateJs, print, exportPdf)
 *
 * Enforces strictly that all GeckoSession interactions execute on the Android Main thread.
 */
class GeckoEngineManager private constructor(private val context: Context) {

  var runtime: GeckoRuntime? = null
    private set

  val blockExtension = BlockExtension.getInstance()
  init {
    val buildVerifyMsg = "[BUILD_VERIFY] version=1.0.1-recovery-v2 stateMachine=ACTIVE"
    Log.i("REM_BUILD", buildVerifyMsg)
    com.remmi.browser.util.DebugLogManager.log(buildVerifyMsg)
    blockExtension.siteSecurityProvider = { host ->
      val policy = com.remmi.browser.security.SiteSecurityPolicyManager.getInstance(context).getPolicyForHost(host)
      policy.shieldsDown
    }
    blockExtension.cosmeticPolicyProvider = { host ->
      val globalSettings = com.remmi.browser.storage.SettingsRepository.getInstance(context).settings.value
      val globalCosmetic = globalSettings.cosmeticFilteringEnabled
      val policy = com.remmi.browser.security.SiteSecurityPolicyManager.getInstance(context).getPolicyForHost(host)
      if (policy.shieldsDown) {
        false
      } else {
        when (policy.cosmeticPolicy) {
          "ENABLED" -> true
          "DISABLED" -> false
          else -> globalCosmetic
        }
      }
    }
  }
  enum class GeckoInitState {
    NOT_STARTED,
    INITIALIZING,
    READY,
    FAILED
  }

  private val _initState = MutableStateFlow(GeckoInitState.NOT_STARTED)
  val initState: StateFlow<GeckoInitState> = _initState.asStateFlow()

  @Volatile
  private var initDeferred: CompletableDeferred<GeckoRuntime>? = null

  @Volatile
  var currentProfile: PrivacyProfile = PrivacyProfile.SHIELD
  private val activeSessions = mutableMapOf<String, GeckoSession>()
  
  fun getSessionForTest(tabId: String): GeckoSession? = activeSessions[tabId]
  fun getAttachedViewForTest(tabId: String): org.mozilla.geckoview.GeckoView? = attachedViews[tabId]
  private val sessionCallbacks = mutableMapOf<String, GeckoTabCallbacks>()
  private val sessionNavStates = mutableMapOf<String, Pair<Boolean, Boolean>>()
  private val mainHandler = Handler(Looper.getMainLooper())

  data class PendingNavigation(
    val url: String,
    val generation: Long,
  )

  data class PendingContentRecovery(
    val session: GeckoSession,
    val url: String,
    val generation: Long,
    val terminationType: String,
  )

  enum class RecoveryStage {
    DISPATCHED,
    NAV_IN_FLIGHT,
    SUCCESS,
    FAILED,
  }

  data class ActiveRecovery(
    val tabId: String,
    val session: GeckoSession,
    val targetUrl: String,
    val generation: Long,
    val startTime: Long,
    var stage: RecoveryStage = RecoveryStage.DISPATCHED,
    var timeoutRunnable: Runnable? = null,
  )

  private val attachedViews = mutableMapOf<String, GeckoView>()
  private val _viewAttachmentStates = mutableMapOf<String, MutableStateFlow<Boolean>>()
  private val navGenerations = mutableMapOf<String, Long>()
  private val lastRecoveredGenerations = mutableMapOf<String, Long>()
  private val pendingNavigations = mutableMapOf<String, PendingNavigation>()
  private val pendingContentRecoveries = mutableMapOf<String, PendingContentRecovery>()
  private val activeRecoveries = mutableMapOf<String, ActiveRecovery>()
  private val lastDispatchedUrls = mutableMapOf<String, String>()
  private val lastObservedUrls = mutableMapOf<String, String>()
  private val dispatchedNavigationsHistory = mutableMapOf<String, MutableList<String>>()

  // Optional test hook for intercepting loadUri calls in JVM unit tests
  internal var uriLoaderForTest: ((tabId: String, session: GeckoSession, url: String) -> Unit)? = null

  // Optional test hook for intercepting session.open calls in JVM unit tests
  internal var sessionOpenerForTest: ((session: GeckoSession, runtime: GeckoRuntime?) -> Unit)? = null

  fun getViewAttachmentState(tabId: String): StateFlow<Boolean> {
    return _viewAttachmentStates.getOrPut(tabId) { MutableStateFlow(false) }.asStateFlow()
  }

  fun isViewAttached(tabId: String): Boolean {
    val view = attachedViews[tabId] ?: return false
    val session = activeSessions[tabId] ?: return false
    return view.session == session
  }

  fun getPendingNavigation(tabId: String): String? = pendingNavigations[tabId]?.url
  fun getLastDispatchedUrl(tabId: String): String? = lastDispatchedUrls[tabId]
  fun getNavGeneration(tabId: String): Long = navGenerations[tabId] ?: 0L
  fun getLastRecoveredGeneration(tabId: String): Long? = lastRecoveredGenerations[tabId]
  fun getDispatchedNavigations(tabId: String): List<String> = dispatchedNavigationsHistory[tabId]?.toList() ?: emptyList()
  fun hasPendingContentRecovery(tabId: String): Boolean = pendingContentRecoveries.containsKey(tabId)
  fun getPendingContentRecovery(tabId: String): PendingContentRecovery? = pendingContentRecoveries[tabId]
  fun hasActiveRecovery(tabId: String): Boolean = activeRecoveries.containsKey(tabId)
  fun getActiveRecovery(tabId: String): ActiveRecovery? = activeRecoveries[tabId]

  @VisibleForTesting
  fun triggerRecoveryTimeoutForTest(tabId: String) {
    val active = activeRecoveries.remove(tabId) ?: return
    active.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    active.stage = RecoveryStage.FAILED
    val sessId = "0x" + Integer.toHexString(System.identityHashCode(active.session))
    val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val toMsg = "[FORENSIC][CONTENT_RECOVERY_TIMEOUT] tabId=$tabId session=$sessId view=$viewId url=${active.targetUrl} gen=${active.generation} stage=DISPATCHED elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
    Log.w(TAG, toMsg)
    com.remmi.browser.util.DebugLogManager.log(toMsg)
  }

  @VisibleForTesting
  internal fun setRuntimeForTesting(runtime: GeckoRuntime?) {
    this.runtime = runtime
  }

  @VisibleForTesting
  internal fun setInitStateForTesting(state: GeckoInitState) {
    _initState.value = state
  }

  @VisibleForTesting
  internal fun setSessionForTesting(tabId: String, session: GeckoSession) {
    activeSessions[tabId] = session
    wireDelegates(tabId, session)
  }

  private fun assertMainThread(operation: String) {
    val isMain = Looper.myLooper() == Looper.getMainLooper()
    Log.d(TAG, "[GECKO] operation=$operation thread=${if (isMain) "main" else "ILLEGAL_${Thread.currentThread().name}"}")
    check(isMain) { "Gecko operation $operation MUST be called on the Main thread! (Current: ${Thread.currentThread().name})" }
  }

  fun initializeRuntimeAsync() {
    if (_initState.value != GeckoInitState.NOT_STARTED && _initState.value != GeckoInitState.FAILED) {
      return
    }

    _initState.value = GeckoInitState.INITIALIZING
    Log.i(TAG, "STATE_LOG: GECKO_INIT_START (time=${android.os.SystemClock.elapsedRealtime()})")

    CoroutineScope(Dispatchers.Main.immediate).launch {
      try {
        initializeRuntimeInternal()
        _initState.value = GeckoInitState.READY
        Log.i(TAG, "STATE_LOG: GECKO_INIT_READY (time=${android.os.SystemClock.elapsedRealtime()})")
      } catch (t: Throwable) {
        _initState.value = GeckoInitState.FAILED
        Log.e(TAG, "STATE_LOG: GECKO_INIT_FAILED (time=${android.os.SystemClock.elapsedRealtime()}) error=${t.message}")
      }
    }
  }

  private suspend fun initializeRuntimeInternal() {
    assertMainThread("INITIALIZE_RUNTIME_INTERNAL")
    if (runtime != null) {
      _initState.value = GeckoInitState.READY
      return
    }

    val watchdog = com.remmi.browser.util.HangWatchdog.startGeckoInitWatchdog()
    val startTime = android.os.SystemClock.elapsedRealtime()
    Log.i(TAG, "Initializing GeckoRuntime with Process Isolation & WebRender...")

    // Clean up any stale config files
    try {
      val oldConfig = java.io.File(context.filesDir, "gv-config.yaml")
      if (oldConfig.exists()) {
        oldConfig.delete()
      }
    } catch (_: Exception) {}

    val settings = GeckoRuntimeSettings.Builder()
      .aboutConfigEnabled(com.remmi.browser.BuildConfig.DEBUG)
      .consoleOutput(com.remmi.browser.BuildConfig.DEBUG)
      .build()
      
    try {
        settings.setLnaEnabled(false)
    } catch (e: Exception) {
        Log.w(TAG, "LNA API not available on this GeckoView version", e)
    }

    val rt = GeckoRuntime.create(context, settings)

    // Register WebExtension for native ad/tracker blocking and secondary proxy synchronization
    try {
      val extensionUri = "resource://android/assets/extensions/remmi_engine_extension/"
      
      val installPromptHandler = { ext: WebExtension? ->
        if (ext != null) {
          rt.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
          ext.setMessageDelegate(blockExtension, "remmi_engine_extension")
          blockExtension.setExtensionRegistered()
          Log.i(TAG, "Remmi WebExtension registered successfully: ${ext.id}")
          com.remmi.browser.util.DebugLogManager.log("[WEBEXT] Registered (ID: ${ext.id})")
        } else {
          blockExtension.setExtensionFailed("WebExtension controller returned null")
          com.remmi.browser.util.DebugLogManager.log("[WEBEXT] WARNING: WebExtension controller returned null")
        }
      }

      val failureHandler = { throwable: Throwable? ->
        Log.w(TAG, "ensureBuiltIn notice, trying fallback install", throwable)
        try {
          rt.webExtensionController
            .installBuiltIn(extensionUri)
            .accept(
              { ext -> installPromptHandler(ext) },
              { fallbackErr ->
                val reason = fallbackErr?.message ?: "Unknown fallback error"
                blockExtension.setExtensionFailed(reason)
                com.remmi.browser.util.DebugLogManager.log("[WEBEXT] Fallback install failed: $reason")
              }
            )
        } catch (fbEx: Exception) {
          val reason = fbEx.message ?: "Exception"
          blockExtension.setExtensionFailed(reason)
          com.remmi.browser.util.DebugLogManager.log("[WEBEXT] Fallback exception: $reason")
        }
      }

      suspendCancellableCoroutine<Unit> { cont ->
        rt.webExtensionController
          .ensureBuiltIn(extensionUri, "extension@remmi.browser")
          .accept(
            { ext: WebExtension? -> installPromptHandler(ext); cont.resume(Unit) },
            { throwable: Throwable? -> failureHandler(throwable); cont.resume(Unit) }
          )
      }
    } catch (e: Exception) {
      Log.w(TAG, "WebExtension installation skipped: ${e.message}")
      blockExtension.setExtensionFailed(e.message ?: "Skipped")
      com.remmi.browser.util.DebugLogManager.log("[WEBEXT] Installation exception: ${e.message}")
    }

    runtime = rt
    applyPrivacyProfile(PrivacyProfile.SHIELD)
    val duration = android.os.SystemClock.elapsedRealtime() - startTime
    watchdog.stop()
    Log.i(TAG, "GeckoRuntime initialization completed in ${duration}ms (READY)")
  }

  suspend fun applyPrivacyProfile(
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    socksPort: Int? = CurrentTorRoute.currentSocksPort,
    generation: Long = CurrentTorRoute.currentGeneration,
    settings: com.remmi.browser.storage.BrowserSettings? = null,
  ): Boolean {
    currentProfile = profile
    val rt = runtime ?: return false
    val browserSettings = settings ?: com.remmi.browser.storage.SettingsRepository.getInstance(context).settings.value

    return when (profile) {
      PrivacyProfile.GHOST -> {
        val port = socksPort ?: return false
        NetworkHardening.applyTorNetworkSettings(rt, port, generation, browserSettings)
      }
      PrivacyProfile.SHIELD -> {
        NetworkHardening.applyShieldNetworkSettings(rt, generation, browserSettings)
      }
      PrivacyProfile.INCOGNITO -> {
        NetworkHardening.applyShieldNetworkSettings(rt, generation, browserSettings)
      }
    }
  }

  fun updateGlobalPreferences(settings: com.remmi.browser.storage.BrowserSettings) {
    val rt = runtime ?: return
    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
      if (CurrentTorRoute.isGhostActive) {
        val port = CurrentTorRoute.currentSocksPort ?: return@launch
        NetworkHardening.applyTorNetworkSettings(rt, port, CurrentTorRoute.currentGeneration, settings)
      } else {
        NetworkHardening.applyShieldNetworkSettings(rt, CurrentTorRoute.currentGeneration, settings)
      }
    }
    
    // Ensure site-specific overrides have precedence (P1-2)
    val currentTabs = TabManager.getInstance().tabs.value
    for (tab in currentTabs) {
      if (tab.url.isNotBlank() && tab.url != "about:blank") {
        try {
          val host = java.net.URI(if (tab.url.contains("://")) tab.url else "https://${tab.url}").host
          if (!host.isNullOrBlank()) {
            applySiteSecurityPolicy(tab.id, host)
          }
        } catch (_: Exception) {}
      }
    }
  }

  fun applySiteSecurityPolicy(tabId: String, host: String) {
    if (host.isBlank()) return
    val policy = com.remmi.browser.security.SiteSecurityPolicyManager.getInstance(context).getPolicyForHost(host)
    val tab = TabManager.getInstance().getTab(tabId)
    val profile = tab?.profile ?: currentProfile
    val securityLevel = policy.customSecurityLevel ?: tab?.securityLevel ?: SecurityLevel.STANDARD

    onMainSession(tabId, "APPLY_SITE_SECURITY_POLICY") { session ->
      session.settings.apply {
        allowJavascript = policy.javascriptEnabled ?: securityLevel.javascriptEnabled
        useTrackingProtection = (policy.cookiePolicy != "ALLOW")
        suspendMediaWhenInactive = !policy.autoplayAllowed
      }
      AntiFingerprint.configureGeckoSession(session, profile, securityLevel)
      Log.d(TAG, "Applied site security policy for host '$host' to tab $tabId (js=${session.settings.allowJavascript}, tp=${session.settings.useTrackingProtection}, autoplay=${policy.autoplayAllowed})")
    }
  }

  fun applySiteSecurityPolicyToMatchingTabs(host: String) {
    val cleanHost = host.lowercase().trim()
    val currentTabs = TabManager.getInstance().tabs.value
    for (tab in currentTabs) {
      val tabHost = try {
        java.net.URI(if (tab.url.contains("://")) tab.url else "https://${tab.url}").host?.lowercase()?.trim()
      } catch (_: Exception) { null }
      if (tabHost == cleanHost) {
        applySiteSecurityPolicy(tab.id, cleanHost)
      }
    }
  }

  suspend fun applyPrivacyProfile(
    profile: PrivacyProfile,
    socksPort: Int?,
    generation: Long,
  ): Boolean {
    return applyPrivacyProfile(profile, SecurityLevel.STANDARD, socksPort, generation)
  }

  fun setTabGhostMode(tabId: String, isGhost: Boolean) {
    // Native Gecko controls tab isolation via Private Browsing session settings
  }

  // --- Internal Session Factory & Wire-up (Strict Main Thread) ---

  private fun createSessionInternal(
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    containerType: ContainerType = ContainerType.fromProfile(profile),
    isDesktopMode: Boolean = false
  ): GeckoSession {
    assertMainThread("CREATE_SESSION")
    android.util.Log.i(TAG, "STATE_LOG: SESSION_CREATE_START (time=${android.os.SystemClock.elapsedRealtime()})")
    val rt = runtime ?: throw IllegalStateException("GeckoRuntime is not ready yet (state=${_initState.value}). Sessions must be created only after runtime readiness.")
    
    val isPrivateContainer = containerType != ContainerType.NORMAL || profile == PrivacyProfile.INCOGNITO || profile == PrivacyProfile.GHOST
    val settings = GeckoSessionSettings.Builder()
      .usePrivateMode(isPrivateContainer)
      .useTrackingProtection(true)
      .suspendMediaWhenInactive(true)
      .build()
      
    val session = GeckoSession(settings)

    session.settings.apply {
      userAgentMode = if (isDesktopMode || profile == PrivacyProfile.GHOST) {
        GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
      } else {
        GeckoSessionSettings.USER_AGENT_MODE_MOBILE
      }
      viewportMode = if (isDesktopMode) {
        GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
      } else {
        GeckoSessionSettings.VIEWPORT_MODE_MOBILE
      }
      allowJavascript = securityLevel.javascriptEnabled
    }

    AntiFingerprint.configureGeckoSession(session, profile, securityLevel)
    android.util.Log.i(TAG, "STATE_LOG: SESSION_OPEN (time=${android.os.SystemClock.elapsedRealtime()})")
    session.open(rt)
    return session
  }

  private fun getOrCreateSessionInternal(
    tabId: String,
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    containerType: ContainerType = ContainerType.fromProfile(profile),
    isDesktopMode: Boolean = false,
  ): GeckoSession {
    assertMainThread("GET_OR_CREATE_INTERNAL id=$tabId")
    val existing = activeSessions[tabId]
    if (existing != null) {
      Log.i(TAG, "[FORENSIC] GECKO_SESSION REUSED id=$tabId (isOpen=${existing.isOpen})")
      if (!existing.isOpen) {
        val testOpener = sessionOpenerForTest
        if (testOpener != null) {
          testOpener(existing, runtime)
        } else {
          runtime?.let { rt ->
            try {
              existing.open(rt)
            } catch (e: Exception) {
              Log.w(TAG, "[GECKO] Reopening existing session failed on tabId=$tabId: ${e.message}")
            }
          }
        }
      }
      return existing
    } else {
      Log.i(TAG, "[FORENSIC] GECKO_SESSION CREATED id=$tabId (first time)")
    }

    val newSession = createSessionInternal(profile, securityLevel, containerType, isDesktopMode)
    activeSessions[tabId] = newSession
    sessionNavStates[tabId] = Pair(false, false)
    wireDelegates(tabId, newSession)
    return newSession
  }

  private fun wireDelegates(tabId: String, session: GeckoSession) {
    // Wire Navigation delegate
    session.navigationDelegate = object : GeckoSession.NavigationDelegate {
      override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
        val url = request.uri ?: ""
        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val gen = navGenerations[tabId] ?: 0L
        val now = android.os.SystemClock.elapsedRealtime()
        val navStartMsg = "[FORENSIC] [NAV_START] tabId=$tabId session=$sessId view=$viewId url=$url gen=$gen elapsedRealtime=$now"
        Log.i(TAG, navStartMsg)
        com.remmi.browser.util.DebugLogManager.log(navStartMsg)

        val tab = TabManager.getInstance().getTab(tabId)
        val isGhost = (tab?.profile == PrivacyProfile.GHOST) || (currentProfile == PrivacyProfile.GHOST)
        
        val check = com.remmi.browser.security.NavigationSecurityAuthority.validateAndSanitizeNavigation(url, isGhost)
        when (check.decision) {
            com.remmi.browser.security.NavigationDecision.BLOCK -> {
                val blockMsg = "[FORENSIC] [NAV_ERROR] tabId=$tabId session=$sessId view=$viewId url=$url error=security_blocked gen=$gen elapsedRealtime=$now"
                Log.e(TAG, blockMsg)
                com.remmi.browser.util.DebugLogManager.log(blockMsg)
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            com.remmi.browser.security.NavigationDecision.SANITIZE_AND_LOAD,
            com.remmi.browser.security.NavigationDecision.REDIRECT_SEARCH -> {
                if (check.sanitizedUrl != null && check.sanitizedUrl != url) {
                    session.loadUri(check.sanitizedUrl)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
            }
            com.remmi.browser.security.NavigationDecision.ALLOW -> {
                // proceed
            }
        }
        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
      }

      override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
        hasUserGesture: Boolean
      ) {
        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val gen = navGenerations[tabId] ?: 0L
        val now = android.os.SystemClock.elapsedRealtime()
        val navLocMsg = "[FORENSIC] [NAV_LOCATION] tabId=$tabId session=$sessId view=$viewId url=$url gen=$gen elapsedRealtime=$now"
        Log.i(TAG, navLocMsg)
        com.remmi.browser.util.DebugLogManager.log(navLocMsg)

        if (url != null && url.isNotBlank() && url != "about:blank") {
          lastObservedUrls[tabId] = url
        }

        // Recovery In-Flight Detection
        val activeRecovery = activeRecoveries[tabId]
        if (activeRecovery != null && 
            activeRecovery.session === session && 
            activeRecovery.generation == gen && 
            activeRecovery.stage == RecoveryStage.DISPATCHED) {
          if (url != null && url.isNotBlank() && url != "about:blank") {
            val isTargetMatch = (url == activeRecovery.targetUrl) || 
                                url.startsWith(activeRecovery.targetUrl) ||
                                try {
                                  val activeHost = java.net.URI(if (activeRecovery.targetUrl.contains("://")) activeRecovery.targetUrl else "https://${activeRecovery.targetUrl}").host
                                  val newHost = java.net.URI(if (url.contains("://")) url else "https://$url").host
                                  !activeHost.isNullOrBlank() && activeHost.equals(newHost, ignoreCase = true)
                                } catch (_: Exception) { false }

            if (isTargetMatch) {
              activeRecovery.stage = RecoveryStage.NAV_IN_FLIGHT
              val inFlightMsg = "[FORENSIC][CONTENT_RECOVERY_NAV_IN_FLIGHT] tabId=$tabId session=$sessId view=$viewId url=$url targetUrl=${activeRecovery.targetUrl} gen=$gen elapsedRealtime=$now"
              Log.i(TAG, inFlightMsg)
              com.remmi.browser.util.DebugLogManager.log(inFlightMsg)
            }
          }
        } else if (activeRecovery == null && url != null && url.isNotBlank() && url != "about:blank" && !url.startsWith("remmi://")) {
          // Normal user / in-page link navigation (e.g. clicking search result): advance generation and clear stale recovery suppression
          val prevUrl = lastDispatchedUrls[tabId]
          if (prevUrl != url) {
            lastDispatchedUrls[tabId] = url
            val newGen = (navGenerations[tabId] ?: 0L) + 1L
            navGenerations[tabId] = newGen
            lastRecoveredGenerations.remove(tabId)
            val inPageMsg = "[FORENSIC][IN_PAGE_NAV] tabId=$tabId session=$sessId url=$url prevUrl=$prevUrl newGen=$newGen elapsedRealtime=$now"
            Log.i(TAG, inPageMsg)
            com.remmi.browser.util.DebugLogManager.log(inPageMsg)
          }
        }

        url?.let {
          if (it.isNotBlank() && it != "about:blank") {
            try {
              val host = java.net.URI(if (it.contains("://")) it else "https://$it").host
              if (!host.isNullOrBlank()) {
                applySiteSecurityPolicy(tabId, host)
              }
            } catch (_: Exception) {}
            sessionCallbacks[tabId]?.onUrlChange(it)
          }
        }
      }

      override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val gen = navGenerations[tabId] ?: 0L
        val now = android.os.SystemClock.elapsedRealtime()
        val navBackMsg = "[FORENSIC] [NAV_CAN_GO_BACK] tabId=$tabId session=$sessId view=$viewId canGoBack=$canGoBack gen=$gen elapsedRealtime=$now"
        Log.i(TAG, navBackMsg)
        com.remmi.browser.util.DebugLogManager.log(navBackMsg)

        val current = sessionNavStates[tabId] ?: Pair(false, false)
        val updated = current.copy(first = canGoBack)
        sessionNavStates[tabId] = updated
        sessionCallbacks[tabId]?.onNavStateChange(updated.first, updated.second)
      }

      override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
        val current = sessionNavStates[tabId] ?: Pair(false, false)
        val updated = current.copy(second = canGoForward)
        sessionNavStates[tabId] = updated
        sessionCallbacks[tabId]?.onNavStateChange(updated.first, updated.second)
      }
    }

    // Wire Progress delegate
    session.progressDelegate = object : GeckoSession.ProgressDelegate {
      override fun onPageStart(session: GeckoSession, url: String) {
        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val gen = navGenerations[tabId] ?: 0L
        val now = android.os.SystemClock.elapsedRealtime()
        val progMsg = "[FORENSIC] [NAV_PROGRESS] tabId=$tabId session=$sessId view=$viewId url=$url progress=10 gen=$gen state=start elapsedRealtime=$now"
        Log.i(TAG, progMsg)
        com.remmi.browser.util.DebugLogManager.log(progMsg)

        sessionCallbacks[tabId]?.onLoadingChange(true)
        sessionCallbacks[tabId]?.onProgressChange(10)
      }

      override fun onPageStop(session: GeckoSession, success: Boolean) {
        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val currUrl = lastObservedUrls[tabId] ?: lastDispatchedUrls[tabId] ?: "unknown"
        val gen = navGenerations[tabId] ?: 0L
        val now = android.os.SystemClock.elapsedRealtime()
        val stopMsg = "[FORENSIC] [NAV_STOP] tabId=$tabId session=$sessId view=$viewId url=$currUrl success=$success gen=$gen elapsedRealtime=$now"
        Log.i(TAG, stopMsg)
        com.remmi.browser.util.DebugLogManager.log(stopMsg)

        val activeRecovery = activeRecoveries[tabId]
        if (activeRecovery != null && 
            activeRecovery.session === session && 
            activeRecovery.generation == gen) {
          val isAboutBlank = (currUrl == "about:blank")
          
          val targetMatches = (currUrl == activeRecovery.targetUrl) || 
                              currUrl.startsWith(activeRecovery.targetUrl) ||
                              try {
                                val activeHost = java.net.URI(if (activeRecovery.targetUrl.contains("://")) activeRecovery.targetUrl else "https://${activeRecovery.targetUrl}").host
                                val currHost = java.net.URI(if (currUrl.contains("://")) currUrl else "https://$currUrl").host
                                !activeHost.isNullOrBlank() && activeHost.equals(currHost, ignoreCase = true)
                              } catch (_: Exception) { false }

          if (success && activeRecovery.stage == RecoveryStage.NAV_IN_FLIGHT && targetMatches && !isAboutBlank) {
            activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            activeRecovery.stage = RecoveryStage.SUCCESS
            activeRecoveries.remove(tabId)
            lastRecoveredGenerations.remove(tabId)
            val succMsg = "[FORENSIC][CONTENT_RECOVERY_NAV_SUCCESS] tabId=$tabId session=$sessId view=$viewId url=$currUrl targetUrl=${activeRecovery.targetUrl} gen=$gen elapsedRealtime=$now"
            Log.i(TAG, succMsg)
            com.remmi.browser.util.DebugLogManager.log(succMsg)
          } else if (!success && (activeRecovery.stage == RecoveryStage.NAV_IN_FLIGHT || targetMatches)) {
            activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            activeRecovery.stage = RecoveryStage.FAILED
            activeRecoveries.remove(tabId)
            val failMsg = "[FORENSIC][CONTENT_RECOVERY_NAV_FAILED] tabId=$tabId session=$sessId view=$viewId url=$currUrl targetUrl=${activeRecovery.targetUrl} gen=$gen success=$success elapsedRealtime=$now"
            Log.w(TAG, failMsg)
            com.remmi.browser.util.DebugLogManager.log(failMsg)
          }
        }

        val activeRec = activeRecoveries[tabId]
        val isTransientAboutBlank = (currUrl == "about:blank" && activeRec != null && activeRec.stage == RecoveryStage.DISPATCHED)
        if (!isTransientAboutBlank) {
          sessionCallbacks[tabId]?.onLoadingChange(false)
          sessionCallbacks[tabId]?.onProgressChange(100)
        }
      }

      override fun onProgressChange(session: GeckoSession, progress: Int) {
        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val currUrl = lastDispatchedUrls[tabId] ?: "unknown"
        val gen = navGenerations[tabId] ?: 0L
        val now = android.os.SystemClock.elapsedRealtime()
        val progMsg = "[FORENSIC] [NAV_PROGRESS] tabId=$tabId session=$sessId view=$viewId url=$currUrl progress=$progress gen=$gen state=update elapsedRealtime=$now"
        Log.i(TAG, progMsg)

        sessionCallbacks[tabId]?.onProgressChange(progress)
      }

      override fun onSecurityChange(
        session: GeckoSession,
        securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
      ) {
        sessionCallbacks[tabId]?.onSecurityChange(securityInfo.isSecure)
      }
    }

    // Wire Content delegate
    session.contentDelegate = object : GeckoSession.ContentDelegate {
      override fun onCrash(session: GeckoSession) {
        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val view = attachedViews[tabId]
        val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val currUrl = lastDispatchedUrls[tabId] ?: "unknown"
        val gen = navGenerations[tabId] ?: 0L
        val isOpen = session.isOpen
        val threadName = Thread.currentThread().name
        val now = android.os.SystemClock.elapsedRealtime()
        val crashMsg = "[FORENSIC][CONTENT_CRASH] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen isOpen=$isOpen thread=$threadName elapsedRealtime=$now"
        Log.e(TAG, crashMsg)
        com.remmi.browser.util.DebugLogManager.log(crashMsg)

        handleContentProcessTermination(tabId, session, "CRASH")
      }

      override fun onKill(session: GeckoSession) {
        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val view = attachedViews[tabId]
        val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val currUrl = lastDispatchedUrls[tabId] ?: "unknown"
        val gen = navGenerations[tabId] ?: 0L
        val isOpen = session.isOpen
        val threadName = Thread.currentThread().name
        val now = android.os.SystemClock.elapsedRealtime()
        val killMsg = "[FORENSIC][CONTENT_KILL] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen isOpen=$isOpen thread=$threadName elapsedRealtime=$now"
        Log.e(TAG, killMsg)
        com.remmi.browser.util.DebugLogManager.log(killMsg)

        handleContentProcessTermination(tabId, session, "KILL")
      }

      override fun onTitleChange(session: GeckoSession, title: String?) {
        title?.let {
          if (it.isNotBlank() && it != "about:blank") {
            sessionCallbacks[tabId]?.onTitleChange(it)
          }
        }
      }

      override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        sessionCallbacks[tabId]?.onExternalResponse(response)
      }

      override fun onContextMenu(
        session: GeckoSession,
        screenX: Int,
        screenY: Int,
        element: GeckoSession.ContentDelegate.ContextElement
      ) {
        val hasLink = !element.linkUri.isNullOrBlank()
        val hasImage = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE || !element.srcUri.isNullOrBlank()
        if (hasLink || hasImage) {
          sessionCallbacks[tabId]?.onContextMenu(
            WebContextMenuData(
              linkUri = element.linkUri,
              srcUri = element.srcUri,
              altText = element.altText,
              title = element.title,
              type = element.type,
            )
          )
        }
      }
    }

    // Wire Scroll delegate
    session.scrollDelegate = object : GeckoSession.ScrollDelegate {
      private var lastScrollY = 0
      override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
        val isScrollingDown = scrollY > lastScrollY && scrollY > 20
        lastScrollY = scrollY
        sessionCallbacks[tabId]?.onScrollChanged(scrollX, scrollY, isScrollingDown)
      }
    }

    // Set WebExtension threat tracker callback
    blockExtension.onThreatNeutralized = { threatUrl, threatType ->
      sessionCallbacks[tabId]?.onTrackerBlocked(threatUrl, threatType)
    }
  }

  /**
   * Internal session execution runner confined strictly to the Android Main thread.
   * Completely encapsulates activeSessions and isolates GeckoSession manipulation.
   */
  private suspend fun <T> withSession(
    tabId: String,
    operation: String = "OPERATION",
    action: (GeckoSession) -> T,
  ): T? = withContext(Dispatchers.Main.immediate) {
    assertMainThread("WITH_SESSION op=$operation id=$tabId")
    val session = activeSessions[tabId] ?: return@withContext null
    try {
      action(session)
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] withSession op=$operation failed on tabId=$tabId: ${e.message}")
      null
    }
  }

  /**
   * Synchronous gateway for fire-and-forget UI calls, automatically dispatches to Main thread.
   */
  private fun onMainSession(
    tabId: String,
    operation: String,
    action: (GeckoSession) -> Unit,
  ) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { onMainSession(tabId, operation, action) }
      return
    }
    assertMainThread("MAIN_SESSION op=$operation id=$tabId")
    val session = activeSessions[tabId] ?: return
    try {
      action(session)
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] onMainSession op=$operation error on tabId=$tabId: ${e.message}")
    }
  }

  // --- View Attachment & Lifecycle Control ---

  private fun dispatchPendingNavigationIfReady(tabId: String) {
    assertMainThread("DISPATCH_PENDING id=$tabId")
    val pending = pendingNavigations.remove(tabId) ?: return
    val session = activeSessions[tabId] ?: return
    val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
    val threadName = Thread.currentThread().name
    
    val isRecoveryActive = activeRecoveries.containsKey(tabId) || pendingContentRecoveries.containsKey(tabId)
    val isActualDuplicate = (lastDispatchedUrls[tabId] == pending.url) && !isRecoveryActive

    if (isActualDuplicate) {
      val skipMsg = "[FORENSIC] [GECKO_NAV_SKIPPED_DUPLICATE] tabId=$tabId session=$sessId gen=${pending.generation} url=${pending.url} thread=$threadName"
      Log.i(TAG, skipMsg)
      com.remmi.browser.util.DebugLogManager.log(skipMsg)
      return
    }
    
    lastDispatchedUrls[tabId] = pending.url
    dispatchedNavigationsHistory.getOrPut(tabId) { mutableListOf() }.add(pending.url)
    val dispatchMsg = "[FORENSIC] [GECKO_NAV_DISPATCH] tabId=$tabId session=$sessId gen=${pending.generation} url=${pending.url} thread=$threadName"
    Log.i(TAG, dispatchMsg)
    com.remmi.browser.util.DebugLogManager.log(dispatchMsg)
    
    try {
      if (!session.isOpen) {
        val testOpener = sessionOpenerForTest
        if (testOpener != null) {
          testOpener(session, runtime)
        } else {
          runtime?.let { rt ->
            try {
              session.open(rt)
            } catch (e: Exception) {
              Log.w(TAG, "[GECKO] Failed to reopen session on tabId=$tabId: ${e.message}")
            }
          }
        }
      }

      val testLoader = uriLoaderForTest
      if (testLoader != null) {
        testLoader(tabId, session, pending.url)
      } else {
        session.loadUri(pending.url)
      }
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] dispatchPendingNavigation error on tabId=$tabId: ${e.message}")
    }
  }

  private fun handleContentProcessTermination(
    tabId: String,
    session: GeckoSession,
    terminationType: String // "CRASH" or "KILL"
  ) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { handleContentProcessTermination(tabId, session, terminationType) }
      return
    }

    val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
    val view = attachedViews[tabId]
    val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val currUrl = lastObservedUrls[tabId]?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("remmi://") }
      ?: TabManager.getInstance().getTab(tabId)?.url?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("remmi://") }
      ?: lastDispatchedUrls[tabId]?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("remmi://") }
      ?: ""
    val gen = navGenerations[tabId] ?: 0L
    val now = android.os.SystemClock.elapsedRealtime()

    if (currUrl.isNotBlank()) {
      lastDispatchedUrls[tabId] = currUrl
    }

    val inFlightRecovery = activeRecoveries.remove(tabId)
    if (inFlightRecovery != null) {
      inFlightRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
      val crashInFlightMsg = "[FORENSIC][CONTENT_RECOVERY_CRASH_IN_FLIGHT] tabId=$tabId session=$sessId view=$viewId url=${inFlightRecovery.targetUrl} gen=${inFlightRecovery.generation} stage=${inFlightRecovery.stage} termination=$terminationType elapsedRealtime=$now"
      Log.e(TAG, crashInFlightMsg)
      com.remmi.browser.util.DebugLogManager.log(crashInFlightMsg)
      lastRecoveredGenerations[tabId] = gen
    }

    // 1. Verify active session ownership (only recover if this session is the active session for the tab)
    val currentActive = activeSessions[tabId]
    if (currentActive == null || currentActive !== session) {
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen reason=stale_or_inactive_session elapsedRealtime=$now"
      Log.w(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    // 2. Validate URL (do not reload about:blank, remmi://newtab, or empty URL)
    if (currUrl.isBlank() || currUrl == "about:blank" || currUrl.startsWith("remmi://")) {
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen reason=invalid_or_blank_url elapsedRealtime=$now"
      Log.i(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    // 2. BACKGROUND / DETACHED TAB SAFETY
    // Current code can receive onKill/onCrash while attachedViews[tabId] == null.
    // Do NOT consume the single per-generation recovery attempt for a detached/background tab.
    if (!isViewAttached(tabId)) {
      pendingContentRecoveries[tabId] = PendingContentRecovery(
        session = session,
        url = currUrl,
        generation = gen,
        terminationType = terminationType,
      )
      val defMsg = "[FORENSIC][CONTENT_RECOVERY_DEFERRED] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen termination=$terminationType elapsedRealtime=$now"
      Log.i(TAG, defMsg)
      com.remmi.browser.util.DebugLogManager.log(defMsg)
      return
    }

    // 3. FOREGROUND ACTIVE TAB
    // Prevent recovery loops: maximum one automatic recovery attempt per navigation generation
    val lastRecoveredGen = lastRecoveredGenerations[tabId]
    if (lastRecoveredGen != null && lastRecoveredGen == gen) {
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen reason=max_attempts_exceeded elapsedRealtime=$now"
      Log.w(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    // 4. Mark recovery start
    lastRecoveredGenerations[tabId] = gen
    val startMsg = "[FORENSIC][CONTENT_RECOVERY_START] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen termination=$terminationType elapsedRealtime=$now"
    Log.i(TAG, startMsg)
    com.remmi.browser.util.DebugLogManager.log(startMsg)

    // 1. SESSION REOPEN HARDENING
    // Before recovery load, use the same lifecycle pattern already present in loadUrl():
    // if (!session.isOpen) session.open(runtime)
    if (!session.isOpen) {
      val testOpener = sessionOpenerForTest
      if (testOpener != null) {
        testOpener(session, runtime)
      } else {
        runtime?.let { rt ->
          try {
            session.open(rt)
          } catch (e: Exception) {
            Log.w(TAG, "[GECKO] Failed to reopen session on tabId=$tabId: ${e.message}")
          }
        }
      }
    }

    // 5. Issue recovery load
    val loadMsg = "[FORENSIC][CONTENT_RECOVERY_LOAD] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen elapsedRealtime=$now"
    Log.i(TAG, loadMsg)
    com.remmi.browser.util.DebugLogManager.log(loadMsg)

    try {
      val testLoader = uriLoaderForTest
      if (testLoader != null) {
        testLoader(tabId, session, currUrl)
      } else {
        session.loadUri(currUrl)
      }

      val recovery = ActiveRecovery(
        tabId = tabId,
        session = session,
        targetUrl = currUrl,
        generation = gen,
        startTime = now,
        stage = RecoveryStage.DISPATCHED,
      )
      val timeoutRunnable = Runnable {
        val current = activeRecoveries[tabId]
        if (current != null && current.generation == gen && current.stage != RecoveryStage.SUCCESS && current.stage != RecoveryStage.FAILED) {
          activeRecoveries.remove(tabId)
          val toMsg = "[FORENSIC][CONTENT_RECOVERY_TIMEOUT] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen stage=${current.stage} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
          Log.w(TAG, toMsg)
          com.remmi.browser.util.DebugLogManager.log(toMsg)
        }
      }
      recovery.timeoutRunnable = timeoutRunnable
      mainHandler.postDelayed(timeoutRunnable, 15000L)
      activeRecoveries[tabId] = recovery

      val dispMsg = "[FORENSIC][CONTENT_RECOVERY_DISPATCHED] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, dispMsg)
      com.remmi.browser.util.DebugLogManager.log(dispMsg)
    } catch (e: Exception) {
      val failMsg = "[FORENSIC][CONTENT_RECOVERY_FAILED] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$gen error=${e.message} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.e(TAG, failMsg, e)
      com.remmi.browser.util.DebugLogManager.log(failMsg)
    }
  }

  private fun resumePendingContentRecoveryIfAny(tabId: String) {
    val pendingRecovery = pendingContentRecoveries[tabId] ?: return
    val session = activeSessions[tabId]
    val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val view = attachedViews[tabId]
    val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val currentGen = navGenerations[tabId] ?: 0L
    val now = android.os.SystemClock.elapsedRealtime()

    // 4. RACE PROTECTION
    // Verify active session ownership
    if (session == null || session !== pendingRecovery.session) {
      pendingContentRecoveries.remove(tabId)
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=${pendingRecovery.url} gen=${pendingRecovery.generation} reason=stale_or_inactive_session elapsedRealtime=$now"
      Log.w(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    if (!isViewAttached(tabId)) {
      return
    }

    // Preserve generation checks so stale recovery cannot override a newer user navigation
    val hasPendingNav = pendingNavigations.containsKey(tabId)
    if (hasPendingNav || currentGen > pendingRecovery.generation) {
      pendingContentRecoveries.remove(tabId)
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=${pendingRecovery.url} gen=${pendingRecovery.generation} currentGen=$currentGen reason=superseded_by_newer_navigation elapsedRealtime=$now"
      Log.i(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    // Verify URL validity
    val targetUrl = pendingRecovery.url.ifBlank {
      lastObservedUrls[tabId]?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("remmi://") }
        ?: TabManager.getInstance().getTab(tabId)?.url?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("remmi://") }
        ?: lastDispatchedUrls[tabId]?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("remmi://") }
        ?: ""
    }
    if (targetUrl.isBlank() || targetUrl == "about:blank" || targetUrl.startsWith("remmi://")) {
      pendingContentRecoveries.remove(tabId)
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=$targetUrl gen=${pendingRecovery.generation} reason=invalid_or_blank_url elapsedRealtime=$now"
      Log.i(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    // Check generation loop suppression
    val lastRecoveredGen = lastRecoveredGenerations[tabId]
    if (lastRecoveredGen != null && lastRecoveredGen == pendingRecovery.generation) {
      pendingContentRecoveries.remove(tabId)
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=$targetUrl gen=${pendingRecovery.generation} reason=max_attempts_exceeded elapsedRealtime=$now"
      Log.w(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    lastDispatchedUrls[tabId] = targetUrl
    val resumeMsg = "[FORENSIC][CONTENT_RECOVERY_RESUME] tabId=$tabId session=$sessId view=$viewId url=$targetUrl gen=${pendingRecovery.generation} termination=${pendingRecovery.terminationType} elapsedRealtime=$now"
    Log.i(TAG, resumeMsg)
    com.remmi.browser.util.DebugLogManager.log(resumeMsg)

    lastRecoveredGenerations[tabId] = pendingRecovery.generation

    val startMsg = "[FORENSIC][CONTENT_RECOVERY_START] tabId=$tabId session=$sessId view=$viewId url=$targetUrl gen=${pendingRecovery.generation} termination=${pendingRecovery.terminationType} elapsedRealtime=$now"
    Log.i(TAG, startMsg)
    com.remmi.browser.util.DebugLogManager.log(startMsg)

    // Safely reopen session if needed
    if (!session.isOpen) {
      val testOpener = sessionOpenerForTest
      if (testOpener != null) {
        testOpener(session, runtime)
      } else {
        runtime?.let { rt ->
          try {
            session.open(rt)
          } catch (e: Exception) {
            Log.w(TAG, "[GECKO] Failed to reopen session on tabId=$tabId: ${e.message}")
          }
        }
      }
    }

    val loadMsg = "[FORENSIC][CONTENT_RECOVERY_LOAD] tabId=$tabId session=$sessId view=$viewId url=$targetUrl gen=${pendingRecovery.generation} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
    Log.i(TAG, loadMsg)
    com.remmi.browser.util.DebugLogManager.log(loadMsg)

    try {
      val testLoader = uriLoaderForTest
      if (testLoader != null) {
        testLoader(tabId, session, targetUrl)
      } else {
        session.loadUri(targetUrl)
      }
      // clear the pending recovery state only after dispatch is accepted
      pendingContentRecoveries.remove(tabId)

      val recovery = ActiveRecovery(
        tabId = tabId,
        session = session,
        targetUrl = targetUrl,
        generation = pendingRecovery.generation,
        startTime = now,
        stage = RecoveryStage.DISPATCHED,
      )
      val timeoutRunnable = Runnable {
        val current = activeRecoveries[tabId]
        if (current != null && current.generation == pendingRecovery.generation && current.stage != RecoveryStage.SUCCESS && current.stage != RecoveryStage.FAILED) {
          activeRecoveries.remove(tabId)
          val toMsg = "[FORENSIC][CONTENT_RECOVERY_TIMEOUT] tabId=$tabId session=$sessId view=$viewId url=$targetUrl gen=${pendingRecovery.generation} stage=${current.stage} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
          Log.w(TAG, toMsg)
          com.remmi.browser.util.DebugLogManager.log(toMsg)
        }
      }
      recovery.timeoutRunnable = timeoutRunnable
      mainHandler.postDelayed(timeoutRunnable, 15000L)
      activeRecoveries[tabId] = recovery

      val dispMsg = "[FORENSIC][CONTENT_RECOVERY_DISPATCHED] tabId=$tabId session=$sessId view=$viewId url=$targetUrl gen=${pendingRecovery.generation} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, dispMsg)
      com.remmi.browser.util.DebugLogManager.log(dispMsg)
    } catch (e: Exception) {
      val failMsg = "[FORENSIC][CONTENT_RECOVERY_FAILED] tabId=$tabId session=$sessId view=$viewId url=$targetUrl gen=${pendingRecovery.generation} error=${e.message} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.e(TAG, failMsg, e)
      com.remmi.browser.util.DebugLogManager.log(failMsg)
    }
  }

  fun checkViewInvariants(targetTabId: String? = null, reason: String = "") {
    val allTabIds = (activeSessions.keys + attachedViews.keys).distinct()
    for (tId in allTabIds) {
      val session = activeSessions[tId]
      val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
      val view = attachedViews[tId]
      val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
      val tag = view?.tag?.toString() ?: "null"
      val url = lastDispatchedUrls[tId] ?: "none"
      
      val duplicateViewTabs = attachedViews.filter { it.value === view && view != null }.keys.toList()
      val isMultiMapped = duplicateViewTabs.size > 1
      
      val invMsg = "[FORENSIC][VIEW_INVARIANT] tabId=$tId view=$viewId session=$sessId attachedOwner=$tId tag=$tag reason=$reason url=$url isMultiMapped=$isMultiMapped multiTabs=$duplicateViewTabs"
      Log.i(TAG, invMsg)
      com.remmi.browser.util.DebugLogManager.log(invMsg)
      
      if (isMultiMapped) {
        val warnMsg = "[FORENSIC][VIEW_INVARIANT_VIOLATION] DUPLICATE_VIEW_MAPPING view=$viewId mappedTo=$duplicateViewTabs"
        Log.e(TAG, warnMsg)
        com.remmi.browser.util.DebugLogManager.log(warnMsg)
      }
      if (view != null && tag != tId) {
        val isUntagged = (view.tag == null)
        val warnMsg = "[FORENSIC][VIEW_INVARIANT_VIOLATION] TAG_MISMATCH tabId=$tId view=$viewId tag=$tag isUntagged=$isUntagged"
        Log.w(TAG, warnMsg)
        com.remmi.browser.util.DebugLogManager.log(warnMsg)
      }
    }
  }

  suspend fun attachView(
    tabId: String,
    geckoView: GeckoView,
    profile: PrivacyProfile,
    isDesktopMode: Boolean,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    containerType: ContainerType = ContainerType.fromProfile(profile),
    callbacks: GeckoTabCallbacks,
  ) = withContext(Dispatchers.Main.immediate) {
    assertMainThread("ATTACH_VIEW id=$tabId")
    sessionCallbacks[tabId] = callbacks
    
    val existingSession = activeSessions[tabId]
    val existingSessId = existingSession?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val gvId = "0x" + Integer.toHexString(System.identityHashCode(geckoView))
    val gen = navGenerations[tabId] ?: 0L
    val threadName = Thread.currentThread().name
    val now = android.os.SystemClock.elapsedRealtime()
    val currUrl = lastDispatchedUrls[tabId] ?: "none"
    val startMsg = "[FORENSIC] [GECKO_VIEW_ATTACH_START] tabId=$tabId session=$existingSessId view=$gvId gen=$gen url=$currUrl thread=$threadName elapsedRealtime=$now"
    Log.i(TAG, startMsg)
    com.remmi.browser.util.DebugLogManager.log(startMsg)
    
    checkViewInvariants(tabId, "ATTACH_START")
    
    if (_initState.value == GeckoInitState.NOT_STARTED) {
      Log.d(TAG, "[GECKO] attachView initializing runtime for tabId=$tabId")
      initializeRuntimeAsync()
    }

    if (_initState.value != GeckoInitState.READY) {
      Log.d(TAG, "[GECKO] attachView suspending for runtime readiness on tabId=$tabId")
      _initState.first { it == GeckoInitState.READY || it == GeckoInitState.FAILED }
    }
    
    if (_initState.value == GeckoInitState.FAILED || (runtime == null && uriLoaderForTest == null)) {
      Log.e(TAG, "[GECKO] attachView failed: runtime is not ready")
      return@withContext
    }

    // Clean up any stale mapping for this geckoView from other tabs to ensure no duplicate View mapping
    attachedViews.entries.filter { it.key != tabId && it.value === geckoView }.forEach { entry ->
      attachedViews.remove(entry.key)
      _viewAttachmentStates.getOrPut(entry.key) { MutableStateFlow(false) }.value = false
    }

    val currentAttachedView = attachedViews[tabId]
    if (existingSession != null && existingSession.isOpen && currentAttachedView === geckoView && geckoView.session === existingSession) {
      val viewId = "0x" + Integer.toHexString(System.identityHashCode(geckoView))
      val skipMsg = "[FORENSIC] [GECKO_VIEW_ATTACH_SKIP_ALREADY_ATTACHED] tabId=$tabId session=$existingSessId view=$viewId gen=$gen reason=idempotency_match elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, skipMsg)
      com.remmi.browser.util.DebugLogManager.log(skipMsg)
      
      existingSession.setActive(true)
      _viewAttachmentStates.getOrPut(tabId) { MutableStateFlow(false) }.value = true
      dispatchPendingNavigationIfReady(tabId)
      resumePendingContentRecoveryIfAny(tabId)
      checkViewInvariants(tabId, "ATTACH_SKIP_IDEMPOTENT")
      return@withContext
    }

    val session = getOrCreateSessionInternal(tabId, profile, securityLevel, containerType, isDesktopMode)
    val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
    try {
      if (geckoView.session != session) {
        geckoView.setSession(session)
      }
      session.setActive(true)
      attachedViews[tabId] = geckoView
      _viewAttachmentStates.getOrPut(tabId) { MutableStateFlow(false) }.value = true

      val doneMsg = "[FORENSIC] [GECKO_VIEW_ATTACH_DONE] tabId=$tabId session=$sessId view=$gvId gen=$gen thread=$threadName elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, doneMsg)
      com.remmi.browser.util.DebugLogManager.log(doneMsg)

      checkViewInvariants(tabId, "ATTACH_DONE")
      dispatchPendingNavigationIfReady(tabId)
      resumePendingContentRecoveryIfAny(tabId)
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] attachView error on tabId=$tabId: ${e.message}")
    }
  }

  fun detachViewSync(
    tabId: String,
    geckoView: GeckoView? = null,
  ) {
    assertMainThread("DETACH_VIEW_SYNC id=$tabId")
    val session = activeSessions[tabId]
    val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val gvId = geckoView?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val gen = navGenerations[tabId] ?: 0L
    val threadName = Thread.currentThread().name
    val now = android.os.SystemClock.elapsedRealtime()
    val currUrl = lastDispatchedUrls[tabId] ?: "none"
    val detachMsg = "[FORENSIC] [GECKO_VIEW_DETACH] tabId=$tabId session=$sessId view=$gvId gen=$gen url=$currUrl thread=$threadName elapsedRealtime=$now"
    Log.i(TAG, detachMsg)
    com.remmi.browser.util.DebugLogManager.log(detachMsg)

    attachedViews.remove(tabId)
    _viewAttachmentStates.getOrPut(tabId) { MutableStateFlow(false) }.value = false

    try {
      geckoView?.releaseSession()
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] releaseSession error: ${e.message}")
    }
    // Tag is only cleared after ownership removal to prevent invariant violations
    geckoView?.tag = null

    onMainSession(tabId, "DETACH_SET_INACTIVE") { sessionObj ->
      try {
        sessionObj.setActive(false)
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] setActive(false) notice: ${e.message}")
      }
    }
    checkViewInvariants(tabId, "DETACH_DONE")
  }

  suspend fun detachView(
    tabId: String,
    geckoView: GeckoView? = null,
  ) = withContext(Dispatchers.Main.immediate) {
    detachViewSync(tabId, geckoView)
  }

  suspend fun setTabActive(tabId: String, active: Boolean) = withContext(Dispatchers.Main.immediate) {
    assertMainThread("SET_TAB_ACTIVE id=$tabId active=$active")
    withSession(tabId, "SET_TAB_ACTIVE") { session ->
      try {
        session.setActive(active)
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] setActive($active) notice: ${e.message}")
      }
    }
  }

  suspend fun updateTabSettings(
    tabId: String,
    isDesktopMode: Boolean,
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
  ) = withContext(Dispatchers.Main.immediate) {
    assertMainThread("UPDATE_TAB_SETTINGS id=$tabId")
    withSession(tabId, "UPDATE_TAB_SETTINGS") { session ->
      try {
        session.settings.userAgentMode = if (isDesktopMode || profile == PrivacyProfile.GHOST) {
          GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
          GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        session.settings.viewportMode = if (isDesktopMode) {
          GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        } else {
          GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
        session.settings.allowJavascript = securityLevel.javascriptEnabled
        AntiFingerprint.configureGeckoSession(session, profile, securityLevel)
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] updateTabSettings notice: ${e.message}")
      }
    }
  }

  // --- High-Level Navigation & Session Commands ---

  fun loadUrl(tabId: String, url: String) {
    if (url.isBlank()) return
    val tab = TabManager.getInstance().getTab(tabId)
    val isGhost = (tab?.profile == PrivacyProfile.GHOST) || (currentProfile == PrivacyProfile.GHOST)
    val check = com.remmi.browser.security.NavigationSecurityAuthority.validateAndSanitizeNavigation(url, isGhost)
    if (check.decision == com.remmi.browser.security.NavigationDecision.BLOCK) {
      Log.w(TAG, "Blocked navigation to '$url' reason: ${check.reason}")
      return
    }
    val targetUrl = check.sanitizedUrl ?: url
    val host = try { java.net.URI(if (targetUrl.contains("://")) targetUrl else "https://$targetUrl").host ?: "" } catch (_: Exception) { "" }
    if (host.isNotBlank()) {
      applySiteSecurityPolicy(tabId, host)
    }
    
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { loadUrl(tabId, targetUrl) }
      return
    }
    assertMainThread("LOAD_URL id=$tabId")
    android.util.Log.i(TAG, "STATE_LOG: FIRST_PAGE_START (time=${android.os.SystemClock.elapsedRealtime()})")
    
    val gen = (navGenerations[tabId] ?: 0L) + 1L
    navGenerations[tabId] = gen
    val session = activeSessions[tabId]
    val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val threadName = Thread.currentThread().name

    val activeRecovery = activeRecoveries.remove(tabId)
    if (activeRecovery != null) {
      activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
      val superMsg = "[FORENSIC][CONTENT_RECOVERY_SUPERSEDED] tabId=$tabId session=$sessId url=${activeRecovery.targetUrl} newUrl=$targetUrl gen=${activeRecovery.generation} newGen=$gen elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, superMsg)
      com.remmi.browser.util.DebugLogManager.log(superMsg)
    }
    lastRecoveredGenerations.remove(tabId)

    if (_initState.value == GeckoInitState.NOT_STARTED) {
      Log.d(TAG, "[GECKO] loadUrl requesting init on tabId=$tabId")
      initializeRuntimeAsync()
    }

    val isRuntimeReady = (_initState.value == GeckoInitState.READY && (runtime != null || uriLoaderForTest != null))
    val isAttached = isViewAttached(tabId)

    if (!isRuntimeReady || !isAttached) {
      val queueMsg = "[FORENSIC] [GECKO_NAV_QUEUE] tabId=$tabId session=$sessId gen=$gen url=$targetUrl thread=$threadName"
      Log.i(TAG, queueMsg)
      com.remmi.browser.util.DebugLogManager.log(queueMsg)

      // Store latest navigation only (replaces any previous pending navigation for this tab)
      pendingNavigations[tabId] = PendingNavigation(targetUrl, gen)
      return
    }

    // View is attached and runtime is ready!
    val currentSession = activeSessions[tabId] ?: run {
      val profile = tab?.profile ?: currentProfile
      val secLevel = tab?.securityLevel ?: SecurityLevel.STANDARD
      val container = tab?.containerType ?: ContainerType.fromProfile(profile)
      val isDesktop = tab?.isDesktopMode ?: false
      getOrCreateSessionInternal(tabId, profile, secLevel, container, isDesktop)
    }
    val currentSessId = "0x" + Integer.toHexString(System.identityHashCode(currentSession))

    val isRecoveryActive = activeRecovery != null || pendingContentRecoveries.containsKey(tabId)
    val isActualDuplicate = (lastDispatchedUrls[tabId] == targetUrl) && !isRecoveryActive

    if (isActualDuplicate) {
      val skipMsg = "[FORENSIC] [GECKO_NAV_SKIPPED_DUPLICATE] tabId=$tabId session=$currentSessId gen=$gen url=$targetUrl thread=$threadName"
      Log.i(TAG, skipMsg)
      com.remmi.browser.util.DebugLogManager.log(skipMsg)
      return
    }

    lastDispatchedUrls[tabId] = targetUrl
    pendingNavigations.remove(tabId)
    dispatchedNavigationsHistory.getOrPut(tabId) { mutableListOf() }.add(targetUrl)
    val dispatchMsg = "[FORENSIC] [GECKO_NAV_DISPATCH] tabId=$tabId session=$currentSessId gen=$gen url=$targetUrl thread=$threadName"
    Log.i(TAG, dispatchMsg)
    com.remmi.browser.util.DebugLogManager.log(dispatchMsg)

    try {
      if (!currentSession.isOpen) {
        val testOpener = sessionOpenerForTest
        if (testOpener != null) {
          testOpener(currentSession, runtime)
        } else {
          runtime?.let { rt ->
            try {
              currentSession.open(rt)
            } catch (e: Exception) {
              Log.w(TAG, "[GECKO] Failed to reopen session on tabId=$tabId: ${e.message}")
            }
          }
        }
      }

      val testLoader = uriLoaderForTest
      if (testLoader != null) {
        testLoader(tabId, currentSession, targetUrl)
      } else {
        currentSession.loadUri(targetUrl)
      }
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] loadUrl error on tabId=$tabId: ${e.message}")
    }
  }

  fun reload(tabId: String) {
    onMainSession(tabId, "RELOAD") { session ->
      lastDispatchedUrls.remove(tabId)
      lastRecoveredGenerations.remove(tabId)
      pendingContentRecoveries.remove(tabId)
      val activeRecovery = activeRecoveries.remove(tabId)
      if (activeRecovery != null) {
        activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val superMsg = "[FORENSIC][CONTENT_RECOVERY_SUPERSEDED] tabId=$tabId url=${activeRecovery.targetUrl} reason=reload elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
        Log.i(TAG, superMsg)
        com.remmi.browser.util.DebugLogManager.log(superMsg)
      }
      session.reload()
    }
  }

  fun resetToNewTab(tabId: String) {
    onMainSession(tabId, "RESET_TO_NEW_TAB") { session ->
      Log.i(TAG, "[FORENSIC] NAV_NEW_TAB_TRANSITION tabId=$tabId")
      pendingContentRecoveries.remove(tabId)
      val activeRecovery = activeRecoveries.remove(tabId)
      if (activeRecovery != null) {
        activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val superMsg = "[FORENSIC][CONTENT_RECOVERY_SUPERSEDED] tabId=$tabId url=${activeRecovery.targetUrl} reason=reset_to_new_tab elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
        Log.i(TAG, superMsg)
        com.remmi.browser.util.DebugLogManager.log(superMsg)
      }
      session.load(GeckoSession.Loader().uri("about:blank").flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY))
    }
  }

  fun stopLoading(tabId: String) {
    onMainSession(tabId, "STOP_LOADING") { session ->
      session.stop()
    }
  }

  fun goBack(tabId: String) {
    onMainSession(tabId, "GO_BACK") { session ->
      Log.i(TAG, "[FORENSIC] NAV_BACK_GECKO tabId=$tabId sessionHash=${session.hashCode()} thread=${Thread.currentThread().name}")
      session.goBack()
    }
  }

  fun goForward(tabId: String) {
    onMainSession(tabId, "GO_FORWARD") { session ->
      Log.i(TAG, "[FORENSIC] NAV_FORWARD_GECKO tabId=$tabId sessionHash=${session.hashCode()} thread=${Thread.currentThread().name}")
      session.goForward()
    }
  }

  fun findInPage(tabId: String, query: String, backwards: Boolean = false) {
    onMainSession(tabId, "FIND_IN_PAGE") { session ->
      val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0
      session.finder.find(query, flags)
    }
  }

  fun clearFindInPage(tabId: String) {
    onMainSession(tabId, "CLEAR_FIND_IN_PAGE") { session ->
      session.finder.clear()
    }
  }

  fun executeScript(tabId: String, script: String) {
    com.remmi.adblock.BlockExtension.getInstance().executeScript(tabId, script)
  }

  fun printPage(activityContext: Context, tabId: String, pageTitle: String, onFinished: (() -> Unit)? = null) {
    onMainSession(tabId, "PRINT_PAGE") { session ->
      try {
        PdfPrintHelper.printPage(activityContext, { session.saveAsPdf() }, pageTitle, onFinished)
      } catch (e: Exception) {
        Log.e(TAG, "printPage error: ${e.message}", e)
        Toast.makeText(activityContext, "Print error: ${e.message}", Toast.LENGTH_SHORT).show()
        onFinished?.invoke()
      }
    }
  }

  fun exportPageAsPdf(
    tabId: String,
    pageTitle: String,
    onFinished: ((File?) -> Unit)? = null,
  ) {
    onMainSession(tabId, "EXPORT_PAGE_AS_PDF") { session ->
      try {
        PdfPrintHelper.exportPageAsPdf(context, { session.saveAsPdf() }, pageTitle, onFinished)
      } catch (e: Exception) {
        Log.e(TAG, "exportPageAsPdf error: ${e.message}", e)
        onFinished?.invoke(null)
      }
    }
  }

  suspend fun <T> executeOnSession(tabId: String, block: (GeckoSession) -> T): T? = withContext(Dispatchers.Main.immediate) {
    assertMainThread("EXECUTE_ON_SESSION id=$tabId")
    val session = activeSessions[tabId] ?: return@withContext null
    try {
      block(session)
    } catch (e: Exception) {
      Log.e(TAG, "executeOnSession error on tabId=$tabId: ${e.message}", e)
      null
    }
  }

  suspend fun closeSessionSafely(tabId: String): CloseResult = withContext(Dispatchers.Main.immediate) {
    assertMainThread("CLOSE_SESSION_SAFELY id=$tabId")
    sessionCallbacks.remove(tabId)
    sessionNavStates.remove(tabId)
    attachedViews.remove(tabId)
    _viewAttachmentStates.remove(tabId)
    pendingNavigations.remove(tabId)
    pendingContentRecoveries.remove(tabId)
    val activeRecovery = activeRecoveries.remove(tabId)
    activeRecovery?.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    lastDispatchedUrls.remove(tabId)
    lastObservedUrls.remove(tabId)
    dispatchedNavigationsHistory.remove(tabId)
    navGenerations.remove(tabId)
    lastRecoveredGenerations.remove(tabId)
    val session = activeSessions.remove(tabId)
    if (session == null) {
      Log.d(TAG, "[GECKO] operation=CLOSE_NOT_FOUND id=$tabId thread=main")
      return@withContext CloseResult.NotFound
    }
    try {
      // Null out delegates to eliminate trailing asynchronous callbacks
      session.navigationDelegate = null
      session.progressDelegate = null
      session.contentDelegate = null
      if (session.isOpen) {
        session.close()
      }
      Log.d(TAG, "[GECKO] operation=CLOSE_COMPLETED id=$tabId thread=main")
      CloseResult.Success
    } catch (t: Throwable) {
      Log.w(TAG, "[GECKO] operation=CLOSE_NOTICE id=$tabId thread=main error=${t.message}")
      CloseResult.Success // Soft-success since resources and map entry are detached
    }
  }

  suspend fun closeAllSessionsSafely() = withContext(Dispatchers.Main.immediate) {
    assertMainThread("CLOSE_ALL")
    sessionCallbacks.clear()
    sessionNavStates.clear()
    attachedViews.clear()
    _viewAttachmentStates.clear()
    pendingNavigations.clear()
    pendingContentRecoveries.clear()
    activeRecoveries.values.forEach { it.timeoutRunnable?.let { r -> mainHandler.removeCallbacks(r) } }
    activeRecoveries.clear()
    lastDispatchedUrls.clear()
    lastObservedUrls.clear()
    dispatchedNavigationsHistory.clear()
    navGenerations.clear()
    lastRecoveredGenerations.clear()
    val sessionsToClose = activeSessions.values.toList()
    activeSessions.clear()
    sessionsToClose.forEach { session ->
      try {
        session.navigationDelegate = null
        session.progressDelegate = null
        session.contentDelegate = null
        if (session.isOpen) {
          session.close()
        }
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] operation=CLOSE_ALL_NOTICE: ${e.message}")
      }
    }
    Log.d(TAG, "[GECKO] operation=CLOSE_ALL_COMPLETED count=${sessionsToClose.size} thread=main")
  }

  /**
   * Complete, atomic destruction of all browser tabs, sessions, delegates, and view bindings.
   * Single source of truth for halting browsing activity during Panic Wipe or full reset.
   */
  suspend fun destroyAllBrowserState(): Boolean = withContext(Dispatchers.Main.immediate) {
    assertMainThread("DESTROY_ALL_BROWSER_STATE")
    try {
      // 1. Clear callbacks and delegates
      sessionCallbacks.clear()
      sessionNavStates.clear()
      attachedViews.clear()
      _viewAttachmentStates.clear()
      pendingNavigations.clear()
      pendingContentRecoveries.clear()
      activeRecoveries.values.forEach { it.timeoutRunnable?.let { r -> mainHandler.removeCallbacks(r) } }
      activeRecoveries.clear()
      lastDispatchedUrls.clear()
      lastObservedUrls.clear()
      dispatchedNavigationsHistory.clear()
      navGenerations.clear()
      lastRecoveredGenerations.clear()

      // 2. Stop all running sessions and close
      val sessions = activeSessions.values.toList()
      activeSessions.clear()
      var allSessionsStopped = true
      var allSessionsClosed = true
      sessions.forEach { session ->
        try {
          session.stop()
        } catch (e: Exception) {
          Log.w(TAG, "[GECKO] destroyAllBrowserState session stop notice: ${e.message}")
          allSessionsStopped = false
        }
        try {
          session.navigationDelegate = null
          session.progressDelegate = null
          session.contentDelegate = null
          session.setActive(false)
          if (session.isOpen) {
            session.close()
          }
        } catch (e: Exception) {
          Log.w(TAG, "[GECKO] destroyAllBrowserState session close notice: ${e.message}")
          allSessionsClosed = false
        }
      }

      // 3. Notify TabManager to purge tab list
      var tabsCleared = true
      try {
        TabManager.getInstance().closeAllTabs()
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] destroyAllBrowserState tab close notice: ${e.message}")
        tabsCleared = false
      }

      val success = allSessionsStopped && allSessionsClosed && tabsCleared
      Log.i(TAG, "[GECKO] All browser state destroyed (success=$success, stopped=$allSessionsStopped, closed=$allSessionsClosed, tabs=$tabsCleared, ${sessions.size} sessions closed).")
      success
    } catch (e: Exception) {
      Log.e(TAG, "[GECKO] destroyAllBrowserState encountered error: ${e.message}", e)
      false
    }
  }

  suspend fun clearCookiesAndCacheSafely(): Boolean = withContext(Dispatchers.Main.immediate) {
    closeAllSessionsSafely()
    val rt = runtime ?: return@withContext true
    try {
      suspendCancellableCoroutine { continuation ->
        val geckoResult = rt.storageController.clearData(org.mozilla.geckoview.StorageController.ClearFlags.ALL)
        geckoResult.accept(
          { continuation.resume(true) },
          { err ->
            Log.w(TAG, "Gecko clearData returned error: ${err?.message}")
            continuation.resume(false)
          }
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error clearing Gecko storage data: ${e.message}")
      false
    }
  }

  companion object {
    private const val TAG = "GeckoEngineManager"

    @Volatile
    private var INSTANCE: GeckoEngineManager? = null

    fun peekInitState(): String? {
      return INSTANCE?._initState?.value?.name
    }

    fun getInstance(context: Context): GeckoEngineManager {
      return INSTANCE ?: synchronized(this) {
        if (INSTANCE != null) {
          INSTANCE!!
        } else {
          com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(context, com.remmi.browser.util.StartupPhase.GECKO_MANAGER_CONSTRUCT_START)
          val mgr = GeckoEngineManager(context.applicationContext).also { INSTANCE = it }
          com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(context, com.remmi.browser.util.StartupPhase.GECKO_MANAGER_CONSTRUCT_END)
          mgr
        }
      }
    }
  }
}
