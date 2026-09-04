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
  
  fun getSession(tabId: String): GeckoSession? = activeSessions[tabId]
  fun getSessionForTest(tabId: String): GeckoSession? = activeSessions[tabId]
  fun getAttachedViewForTest(tabId: String): org.mozilla.geckoview.GeckoView? = attachedViews[tabId]
  private val sessionCallbacks = mutableMapOf<String, GeckoTabCallbacks>()
  private val sessionNavStates = mutableMapOf<String, Pair<Boolean, Boolean>>()
  private val mainHandler = Handler(Looper.getMainLooper())

  data class PendingNavigation(
    val url: String,
    val generation: Long,
    val navId: Long,
  )

  data class PendingContentRecovery(
    val session: GeckoSession,
    val url: String,
    val generation: Long,
    val navId: Long,
    val terminationType: String,
  )

  enum class RecoveryStage {
    DISPATCHED,
    NAV_IN_FLIGHT,
    SUCCESS,
    FAILED,
  }

  enum class RecoveryState {
    NONE,
    PENDING_DETACHED,
    STARTING,
    IN_FLIGHT,
    SUPERSEDED,
    SUCCESS,
    FAILED,
  }

  data class ActiveRecovery(
    val tabId: String,
    val session: GeckoSession,
    val targetUrl: String,
    val generation: Long,
    val navId: Long,
    val startTime: Long,
    var stage: RecoveryStage = RecoveryStage.DISPATCHED,
    var timeoutRunnable: Runnable? = null,
    val redirectedUrls: MutableList<String> = mutableListOf(),
  )

  data class SuccessfulNavRecord(
    val navId: Long,
    val url: String,
    val gen: Long,
    val timestampElapsed: Long,
  )

  private val attachedViews = mutableMapOf<String, GeckoView>()
  private val _viewAttachmentStates = mutableMapOf<String, MutableStateFlow<Boolean>>()
  private val navGenerations = mutableMapOf<String, Long>()
  private val currentNavIds = mutableMapOf<String, Long>()
  private val navIdCounter = java.util.concurrent.atomic.AtomicLong(1000L)
  private val lastSuccessfulNavigations = mutableMapOf<String, SuccessfulNavRecord>()
  private val lastOriginalFailures = mutableMapOf<String, String>()
  private val sessionGenerations = mutableMapOf<String, Long>()
  private val viewGenerations = mutableMapOf<String, Long>()
  private val lastRedirectUrls = mutableMapOf<String, String>()
  private val lastRecoveredGenerations = mutableMapOf<String, Long>()
  private val pendingNavigations = mutableMapOf<String, PendingNavigation>()
  private val pendingContentRecoveries = mutableMapOf<String, PendingContentRecovery>()
  private val activeRecoveries = mutableMapOf<String, ActiveRecovery>()
  private val recoveryStates = mutableMapOf<String, RecoveryState>()
  private val inFlightNavigations = mutableMapOf<String, Long>()
  private val lastDispatchedUrls = mutableMapOf<String, String>()
  private val lastObservedUrls = mutableMapOf<String, String>()
  private val latestProgressUrls = mutableMapOf<String, String>()
  private val latestLocationUrls = mutableMapOf<String, String>()
  private val dispatchedNavigationsHistory = mutableMapOf<String, MutableList<String>>()
  private val navLoadingStates = mutableMapOf<String, Boolean>()
  private val navProgressStates = mutableMapOf<String, Int>()

  // Optional test hook for intercepting loadUri calls in JVM unit tests
  internal var uriLoaderForTest: ((tabId: String, session: GeckoSession, url: String) -> Unit)? = null

  // Optional test hook for intercepting session.open calls in JVM unit tests
  internal var sessionOpenerForTest: ((session: GeckoSession, runtime: GeckoRuntime?) -> Unit)? = null

  fun getRecoveryState(tabId: String): RecoveryState = recoveryStates[tabId] ?: RecoveryState.NONE

  fun transitionRecoveryState(
    tabId: String,
    newState: RecoveryState,
    navId: Long,
    generation: Long,
    reason: String
  ) {
    val oldState = recoveryStates[tabId] ?: RecoveryState.NONE
    recoveryStates[tabId] = newState
    val msg = "[FORENSIC][RECOVERY_STATE] tabId=$tabId oldState=$oldState newState=$newState navId=$navId generation=$generation reason=$reason"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logStaleCallbackRejected(
    tabId: String,
    session: GeckoSession?,
    view: GeckoView?,
    navId: Long,
    generation: Long,
    currentSession: GeckoSession?,
    currentView: GeckoView?,
    currentNavId: Long,
    currentGeneration: Long,
    callback: String,
    reason: String
  ) {
    val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val currSessId = currentSession?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val currViewId = currentView?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val msg = "[FORENSIC][STALE_CALLBACK_REJECTED] tabId=$tabId session=$sessId view=$viewId navId=$navId generation=$generation currentSession=$currSessId currentView=$currViewId currentNavId=$currentNavId currentGeneration=$currentGeneration callback=$callback reason=$reason"
    Log.w(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logNavIntent(
    tabId: String,
    navId: Long,
    generation: Long,
    trigger: String,
    url: String
  ) {
    val now = android.os.SystemClock.elapsedRealtime()
    val msg = "[FORENSIC][NAV_INTENT] tabId=$tabId navId=$navId generation=$generation trigger=$trigger url=$url elapsedRealtime=$now"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logNavAllocation(
    tabId: String,
    navId: Long,
    generation: Long,
    trigger: String,
    url: String,
    previousNavId: Long,
    previousGeneration: Long
  ) {
    val now = android.os.SystemClock.elapsedRealtime()
    val msg = "[FORENSIC][NAV_ALLOCATION] tabId=$tabId navId=$navId generation=$generation trigger=$trigger url=$url previousNavId=$previousNavId previousGeneration=$previousGeneration elapsedRealtime=$now"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logNavCorrelation(
    tabId: String,
    navId: Long,
    generation: Long,
    url: String,
    trigger: String,
    reason: String
  ) {
    val now = android.os.SystemClock.elapsedRealtime()
    val msg = "[FORENSIC][NAV_CORRELATION] tabId=$tabId navId=$navId generation=$generation url=$url trigger=$trigger reason=$reason elapsedRealtime=$now"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logNavAllocationRejected(
    tabId: String,
    navId: Long,
    generation: Long,
    url: String,
    trigger: String,
    reason: String
  ) {
    val now = android.os.SystemClock.elapsedRealtime()
    val msg = "[FORENSIC][NAV_ALLOCATION_REJECTED] tabId=$tabId navId=$navId generation=$generation url=$url trigger=$trigger reason=$reason elapsedRealtime=$now"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logNavDuplicateClassification(
    tabId: String,
    navId: Long,
    generation: Long,
    previousNavId: Long,
    previousGeneration: Long,
    classification: String,
    trigger: String,
    reason: String
  ) {
    val now = android.os.SystemClock.elapsedRealtime()
    val msg = "[FORENSIC][NAV_DUPLICATE_CLASSIFICATION] tabId=$tabId navId=$navId generation=$generation previousNavId=$previousNavId previousGeneration=$previousGeneration classification=$classification trigger=$trigger reason=$reason elapsedRealtime=$now"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logSessionViewBinding(
    tabId: String,
    session: GeckoSession?,
    view: GeckoView?,
    attached: Boolean,
    reason: String
  ) {
    val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val msg = "[FORENSIC][SESSION_VIEW_BINDING] tabId=$tabId session=$sessId view=$viewId attached=$attached reason=$reason"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun isCallbackAuthoritative(
    tabId: String,
    session: GeckoSession,
    callbackName: String,
    isViewBound: Boolean = false,
    callbackGen: Long? = null
  ): Boolean {
    val currentSession = activeSessions[tabId]
    val currentView = attachedViews[tabId]
    val currentNavId = getActiveNavId(tabId)
    val currentGen = navGenerations[tabId] ?: 0L

    if (currentSession == null || currentSession !== session) {
      logStaleCallbackRejected(
        tabId = tabId,
        session = session,
        view = currentView,
        navId = currentNavId,
        generation = callbackGen ?: currentGen,
        currentSession = currentSession,
        currentView = currentView,
        currentNavId = currentNavId,
        currentGeneration = currentGen,
        callback = callbackName,
        reason = if (currentSession == null) "session_closed_or_absent" else "session_mismatch"
      )
      return false
    }

    if (isViewBound && currentView == null) {
      logStaleCallbackRejected(
        tabId = tabId,
        session = session,
        view = null,
        navId = currentNavId,
        generation = callbackGen ?: currentGen,
        currentSession = currentSession,
        currentView = null,
        currentNavId = currentNavId,
        currentGeneration = currentGen,
        callback = callbackName,
        reason = "view_detached"
      )
      return false
    }

    if (callbackGen != null && callbackGen < currentGen) {
      logStaleCallbackRejected(
        tabId = tabId,
        session = session,
        view = currentView,
        navId = currentNavId,
        generation = callbackGen,
        currentSession = currentSession,
        currentView = currentView,
        currentNavId = currentNavId,
        currentGeneration = currentGen,
        callback = callbackName,
        reason = "stale_generation"
      )
      return false
    }

    return true
  }

  fun allocateNavigationGeneration(
    tabId: String,
    trigger: String,
    url: String
  ): Pair<Long, Long> {
    val previousNavId = currentNavIds[tabId] ?: 0L
    val previousGeneration = navGenerations[tabId] ?: 0L
    val newNavId = navIdCounter.incrementAndGet()
    val newGen = previousGeneration + 1L
    currentNavIds[tabId] = newNavId
    navGenerations[tabId] = newGen
    inFlightNavigations[tabId] = newNavId
    lastRecoveredGenerations.remove(tabId)

    logNavIntent(tabId, newNavId, newGen, trigger, url)
    logNavAllocation(
      tabId = tabId,
      navId = newNavId,
      generation = newGen,
      trigger = trigger,
      url = url,
      previousNavId = previousNavId,
      previousGeneration = previousGeneration,
    )
    return Pair(newNavId, newGen)
  }

  fun logProgressState(
    tabId: String,
    navId: Long,
    generation: Long,
    event: String,
    oldProgress: Int,
    newProgress: Int,
    isLoading: Boolean,
    accepted: Boolean,
    reason: String
  ) {
    val msg = "[FORENSIC][PROGRESS_STATE] navId=$navId generation=$generation event=$event oldProgress=$oldProgress newProgress=$newProgress isLoading=$isLoading accepted=$accepted reason=$reason"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logRecoveryUrlState(
    tabId: String,
    navId: Long,
    generation: Long,
    url: String?,
    activeRecovery: Boolean,
    targetUrl: String?,
    classification: String,
    action: String
  ) {
    val msg = "[FORENSIC][RECOVERY_URL_STATE] tabId=$tabId navId=$navId generation=$generation url=$url activeRecovery=$activeRecovery targetUrl=${targetUrl ?: "none"} classification=$classification action=$action"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun getActiveNavId(tabId: String): Long {
    return currentNavIds[tabId] ?: 0L
  }

  fun allocateNavId(tabId: String): Long {
    val id = navIdCounter.incrementAndGet()
    currentNavIds[tabId] = id
    return id
  }

  fun getMemoryForensicSnapshot(trigger: String): String {
    val snap = try { com.remmi.browser.util.ProcessMemoryTelemetry.captureSnapshot() } catch (_: Throwable) { null }
    val rssMb = (snap?.rssBytes ?: 0L) / (1024 * 1024)
    val pssMb = (snap?.pssBytes ?: 0L) / (1024 * 1024)
    val javaUsedMb = (snap?.javaHeapUsedBytes ?: 0L) / (1024 * 1024)
    val javaMaxMb = (snap?.javaHeapMaxBytes ?: 0L) / (1024 * 1024)
    val nativeMb = (snap?.nativeHeapAllocatedBytes ?: 0L) / (1024 * 1024)
    val availBytes = com.remmi.browser.util.HangWatchdog.getAvailableMemBytes(context)
    val availStr = if (availBytes != null) " availMem=${availBytes / (1024 * 1024)}MB" else ""
    val memMsg = "[FORENSIC][MEMORY_SNAPSHOT] trigger=$trigger rss=${rssMb}MB pss=${pssMb}MB javaHeap=${javaUsedMb}/${javaMaxMb}MB nativeHeap=${nativeMb}MB$availStr"
    Log.i(TAG, memMsg)
    com.remmi.browser.util.DebugLogManager.log(memMsg)
    return memMsg
  }

  private fun getCallerTrace(depth: Int = 4): String {
    val trace = Thread.currentThread().stackTrace
    return trace.drop(3).take(depth).joinToString(" -> ") {
      "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
    }
  }

  fun logDestructiveOp(
    operation: String,
    tabId: String,
    session: GeckoSession? = null,
    view: GeckoView? = null,
    url: String? = null,
    reason: String = "",
  ) {
    val targetSession = session ?: activeSessions[tabId]
    val sessId = targetSession?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val targetView = view ?: attachedViews[tabId]
    val viewId = targetView?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val navId = getActiveNavId(tabId)
    val gen = navGenerations[tabId] ?: 0L
    val targetUrl = url ?: lastDispatchedUrls[tabId] ?: "unknown"
    val caller = getCallerTrace(4)
    val threadName = Thread.currentThread().name
    val isOpen = targetSession?.isOpen ?: false
    val attachedOwner = attachedViews.entries.find { it.value === targetView && targetView != null }?.key ?: "none"

    val msg = "[FORENSIC][DESTRUCTIVE_OP] operation=$operation tabId=$tabId session=$sessId view=$viewId navId=$navId url=$targetUrl gen=$gen reason=$reason caller=$caller thread=$threadName isOpen=$isOpen attachedOwner=$attachedOwner"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun logContentProcessEvent(
    event: String,
    tabId: String,
    session: GeckoSession? = null,
    url: String? = null,
    reason: String = "",
  ) {
    val targetSession = session ?: activeSessions[tabId]
    val sessId = targetSession?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val targetView = attachedViews[tabId]
    val viewId = targetView?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val navId = getActiveNavId(tabId)
    val gen = navGenerations[tabId] ?: 0L
    val targetUrl = url ?: lastDispatchedUrls[tabId] ?: "unknown"
    val pid = android.os.Process.myPid()
    val isAttached = isViewAttached(tabId)
    val hasActiveRec = hasActiveRecovery(tabId)
    val hasPendingRec = hasPendingContentRecovery(tabId)
    val bridge = com.remmi.adblock.AdblockBridge.getInstance()
    val adblockGen = bridge.getEngineGeneration()
    val adblockRules = bridge.getLoadedRulesCount()
    val isNative = bridge.isNativeAvailable()
    val inflightNet = com.remmi.adblock.BlockExtension.getInflightDecisionCount()
    val inflightCosmetic = com.remmi.adblock.BlockExtension.getInflightCosmeticCount()
    val now = android.os.SystemClock.elapsedRealtime()

    val msg = "[FORENSIC][CONTENT_PROCESS_EVENT] event=$event pid=$pid tabId=$tabId session=$sessId view=$viewId navId=$navId url=$targetUrl gen=$gen reason=$reason attached=$isAttached activeRec=$hasActiveRec pendingRec=$hasPendingRec adblockGen=$adblockGen adblockRules=$adblockRules isNative=$isNative inflightNet=$inflightNet inflightCosmetic=$inflightCosmetic elapsedRealtime=$now"
    Log.i(TAG, msg)
    com.remmi.browser.util.DebugLogManager.log(msg)
  }

  fun checkPostNavFailure(tabId: String, failureType: String, currentUrl: String? = null) {
    val record = lastSuccessfulNavigations[tabId]
    val now = android.os.SystemClock.elapsedRealtime()
    val curr = currentUrl ?: lastObservedUrls[tabId] ?: lastDispatchedUrls[tabId] ?: "unknown"
    val navId = record?.navId ?: getActiveNavId(tabId)
    val gen = record?.gen ?: (navGenerations[tabId] ?: 0L)
    val elapsed = if (record != null) now - record.timestampElapsed else -1L

    val isNavActive = (navLoadingStates[tabId] == true) || inFlightNavigations.containsKey(tabId)
    val recState = recoveryStates[tabId]
    val isRecoveryInFlight = recState == RecoveryState.STARTING || recState == RecoveryState.IN_FLIGHT

    when (failureType) {
      "VIEW_ON_RELEASE", "DETACH_VIEW", "TAG_MISMATCH_DETACH" -> {
        if (isNavActive || isRecoveryInFlight) {
          val reason = if (isRecoveryInFlight) "view_disposed_during_recovery" else "view_disposed_during_active_nav"
          val lifecycleMsg = "[FORENSIC][POST_NAV_LIFECYCLE] tabId=$tabId navId=$navId gen=$gen url=$curr event=VIEW_DISPOSED_DURING_ACTIVE_NAVIGATION failure=$failureType reason=$reason elapsedSinceNavStopMs=$elapsed"
          Log.w(TAG, lifecycleMsg)
          com.remmi.browser.util.DebugLogManager.log(lifecycleMsg)

          val failMsg = "[FORENSIC][POST_NAV_FAILURE_CONFIRMED] tabId=$tabId navId=$navId successfulUrl=${record?.url ?: "none"} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=$reason"
          Log.e(TAG, failMsg)
          com.remmi.browser.util.DebugLogManager.log(failMsg)
          lastOriginalFailures[tabId] = failureType
        } else {
          val reason = "view_disposed_after_terminal_success"
          val lifecycleMsg = "[FORENSIC][POST_NAV_LIFECYCLE] tabId=$tabId navId=$navId gen=$gen url=$curr event=VIEW_DISPOSED_AFTER_NAV_SUCCESS reason=$reason elapsedSinceNavStopMs=$elapsed"
          Log.i(TAG, lifecycleMsg)
          com.remmi.browser.util.DebugLogManager.log(lifecycleMsg)

          val suppMsg = "[FORENSIC][POST_NAV_FAILURE_SUPPRESSED] tabId=$tabId navId=$navId successfulUrl=${record?.url ?: "none"} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=$reason"
          Log.i(TAG, suppMsg)
          com.remmi.browser.util.DebugLogManager.log(suppMsg)
        }
      }
      "CONTENT_CRASH", "CONTENT_KILL" -> {
        val reason = "content_process_terminated"
        val lifecycleMsg = "[FORENSIC][POST_NAV_LIFECYCLE] tabId=$tabId navId=$navId gen=$gen url=$curr event=CONTENT_PROCESS_FAILED failure=$failureType reason=$reason elapsedSinceNavStopMs=$elapsed"
        Log.e(TAG, lifecycleMsg)
        com.remmi.browser.util.DebugLogManager.log(lifecycleMsg)

        if (!isNavActive && record != null) {
          val suppMsg = "[FORENSIC][POST_NAV_FAILURE_SUPPRESSED] tabId=$tabId navId=$navId successfulUrl=${record.url} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=content_kill_after_terminal_success"
          Log.i(TAG, suppMsg)
          com.remmi.browser.util.DebugLogManager.log(suppMsg)
        } else if (record != null && elapsed in 0..15000L) {
          val failMsg = "[FORENSIC][POST_NAV_FAILURE_CONFIRMED] tabId=$tabId navId=$navId successfulUrl=${record.url} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=$reason"
          Log.e(TAG, failMsg)
          com.remmi.browser.util.DebugLogManager.log(failMsg)
          lastOriginalFailures[tabId] = failureType
        }
      }
      "PAGE_STOP_FAILED", "NAV_ERROR" -> {
        val reason = "navigation_terminal_error"
        val lifecycleMsg = "[FORENSIC][POST_NAV_LIFECYCLE] tabId=$tabId navId=$navId gen=$gen url=$curr event=NAVIGATION_FAILED failure=$failureType reason=$reason elapsedSinceNavStopMs=$elapsed"
        Log.e(TAG, lifecycleMsg)
        com.remmi.browser.util.DebugLogManager.log(lifecycleMsg)

        val failMsg = "[FORENSIC][POST_NAV_FAILURE_CONFIRMED] tabId=$tabId navId=$navId successfulUrl=${record?.url ?: "none"} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=$reason"
        Log.e(TAG, failMsg)
        com.remmi.browser.util.DebugLogManager.log(failMsg)
        lastOriginalFailures[tabId] = failureType
      }
      "ABOUT_BLANK" -> {
        if (isRecoveryInFlight) {
          val suppMsg = "[FORENSIC][POST_NAV_FAILURE_SUPPRESSED] tabId=$tabId navId=$navId successfulUrl=${record?.url ?: "none"} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=ABOUT_BLANK reason=transient_recovery_blank"
          Log.i(TAG, suppMsg)
          com.remmi.browser.util.DebugLogManager.log(suppMsg)
        } else if (record != null && elapsed in 0..15000L && record.url != "about:blank") {
          val reason = "unexpected_post_nav_blank"
          val failMsg = "[FORENSIC][POST_NAV_FAILURE_CONFIRMED] tabId=$tabId navId=$navId successfulUrl=${record.url} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=ABOUT_BLANK reason=$reason"
          Log.e(TAG, failMsg)
          com.remmi.browser.util.DebugLogManager.log(failMsg)
          lastOriginalFailures[tabId] = "ABOUT_BLANK"
        }
      }
      else -> {
        if (!isNavActive && record != null) {
          val suppMsg = "[FORENSIC][POST_NAV_FAILURE_SUPPRESSED] tabId=$tabId navId=$navId successfulUrl=${record.url} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=content_kill_after_terminal_success"
          Log.i(TAG, suppMsg)
          com.remmi.browser.util.DebugLogManager.log(suppMsg)
        } else if (record != null && elapsed in 0..15000L) {
          val failMsg = "[FORENSIC][POST_NAV_FAILURE_CONFIRMED] tabId=$tabId navId=$navId successfulUrl=${record.url} currentUrl=$curr gen=$gen elapsedSinceNavStopMs=$elapsed failure=$failureType reason=other"
          Log.e(TAG, failMsg)
          com.remmi.browser.util.DebugLogManager.log(failMsg)
          lastOriginalFailures[tabId] = failureType
        }
      }
    }
  }

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
  fun getLastOriginalFailure(tabId: String): String? = lastOriginalFailures[tabId]
  fun getLastSuccessfulNavigation(tabId: String): SuccessfulNavRecord? = lastSuccessfulNavigations[tabId]

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

  fun isInternalOrIgnoredUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return true
    val trimmed = url.trim()
    if (trimmed.equals("about:blank", ignoreCase = true)) return true
    if (trimmed.startsWith("about:", ignoreCase = true)) return true
    if (trimmed.startsWith("remmi:", ignoreCase = true)) return true
    if (trimmed.startsWith("chrome:", ignoreCase = true)) return true
    if (trimmed.startsWith("resource:", ignoreCase = true)) return true
    if (trimmed.startsWith("moz-extension:", ignoreCase = true)) return true
    if (trimmed.equals("unknown", ignoreCase = true)) return true
    return false
  }

  private fun parseUri(rawUrl: String): android.net.Uri? {
    val clean = rawUrl.trim()
    val withScheme = if (!clean.contains("://") && !clean.startsWith("about:") && !clean.startsWith("remmi:")) {
      "https://$clean"
    } else {
      clean
    }
    return try {
      android.net.Uri.parse(withScheme)
    } catch (_: Exception) {
      null
    }
  }

  @VisibleForTesting
  internal fun areUrlsEquivalent(url1: String?, url2: String?): Boolean {
    if (url1.isNullOrBlank() || url2.isNullOrBlank()) return false
    if (isInternalOrIgnoredUrl(url1) || isInternalOrIgnoredUrl(url2)) return false
    if (url1 == url2) return true

    val uri1 = parseUri(url1) ?: return false
    val uri2 = parseUri(url2) ?: return false

    val scheme1 = uri1.scheme?.lowercase() ?: ""
    val scheme2 = uri2.scheme?.lowercase() ?: ""
    val schemesCompatible = (scheme1 == scheme2) || 
      ((scheme1 == "http" || scheme1 == "https") && (scheme2 == "http" || scheme2 == "https"))
    if (!schemesCompatible) return false

    val host1 = uri1.host?.lowercase() ?: ""
    val host2 = uri2.host?.lowercase() ?: ""
    if (host1.isEmpty() || host2.isEmpty()) return false
    val hostsCompatible = (host1 == host2) || 
      (host1 == host2.removePrefix("www.")) || 
      (host2 == host1.removePrefix("www."))
    if (!hostsCompatible) return false

    val defaultPort1 = if (scheme1 == "http") 80 else if (scheme1 == "https") 443 else -1
    val defaultPort2 = if (scheme2 == "http") 80 else if (scheme2 == "https") 443 else -1
    val isDefaultPort1 = (uri1.port == -1 || uri1.port == defaultPort1)
    val isDefaultPort2 = (uri2.port == -1 || uri2.port == defaultPort2)
    if (isDefaultPort1 && isDefaultPort2) {
      // Both use default ports for their respective schemes
    } else if (uri1.port != uri2.port) {
      return false
    }

    val path1 = (uri1.path ?: "").trimEnd('/')
    val path2 = (uri2.path ?: "").trimEnd('/')
    if (path1 != path2) return false

    val query1 = uri1.query
    val query2 = uri2.query
    if (query1 != query2) {
      if (query1 == null || query2 == null) return false
      val names1 = try { uri1.queryParameterNames } catch (_: Exception) { null }
      val names2 = try { uri2.queryParameterNames } catch (_: Exception) { null }
      if (names1 == null || names2 == null || names1 != names2) return false
      for (name in names1) {
        val vals1 = uri1.getQueryParameters(name)
        val vals2 = uri2.getQueryParameters(name)
        if (vals1 != vals2) return false
      }
    }

    return true
  }

  @VisibleForTesting
  internal fun isDifferentHost(url1: String?, url2: String?): Boolean {
    if (url1.isNullOrBlank() || url2.isNullOrBlank()) return true
    if (isInternalOrIgnoredUrl(url1) || isInternalOrIgnoredUrl(url2)) return true
    val uri1 = parseUri(url1) ?: return true
    val uri2 = parseUri(url2) ?: return true
    val host1 = uri1.host?.lowercase()?.removePrefix("www.") ?: return true
    val host2 = uri2.host?.lowercase()?.removePrefix("www.") ?: return true
    return host1 != host2
  }

  private fun isRecoveryTargetOrRedirect(url: String?, recovery: ActiveRecovery): Boolean {
    if (url.isNullOrBlank() || isInternalOrIgnoredUrl(url)) return false
    if (areUrlsEquivalent(url, recovery.targetUrl)) return true
    for (redirectUrl in recovery.redirectedUrls) {
      if (areUrlsEquivalent(url, redirectUrl)) return true
    }
    return false
  }

  @VisibleForTesting
  internal fun recordRecoveryRedirect(tabId: String, redirectedUrl: String) {
    val active = activeRecoveries[tabId] ?: return
    if (!isInternalOrIgnoredUrl(redirectedUrl) && active.redirectedUrls.size < 10) {
      if (!active.redirectedUrls.any { areUrlsEquivalent(it, redirectedUrl) }) {
        active.redirectedUrls.add(redirectedUrl)
        active.stage = RecoveryStage.NAV_IN_FLIGHT
        lastObservedUrls[tabId] = redirectedUrl
        val sessId = "0x" + Integer.toHexString(System.identityHashCode(active.session))
        val redMsg = "[FORENSIC][CONTENT_RECOVERY_REDIRECT] tabId=$tabId session=$sessId url=$redirectedUrl targetUrl=${active.targetUrl} gen=${active.generation} redirectCount=${active.redirectedUrls.size} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
        Log.i(TAG, redMsg)
        com.remmi.browser.util.DebugLogManager.log(redMsg)
      }
    }
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
        if (!isCallbackAuthoritative(tabId, session, "onLoadRequest")) {
          return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        val url = request.uri ?: ""
        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val gen = navGenerations[tabId] ?: 0L
        val navId = getActiveNavId(tabId)
        val now = android.os.SystemClock.elapsedRealtime()
        val navStartMsg = "[FORENSIC] [NAV_START] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$url gen=$gen elapsedRealtime=$now"
        Log.i(TAG, navStartMsg)
        com.remmi.browser.util.DebugLogManager.log(navStartMsg)

        val navLoadReqMsg = "[FORENSIC] [NAV_LOAD_REQUEST] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$url isRedirect=${request.isRedirect} hasUserGesture=${request.hasUserGesture} gen=$gen elapsedRealtime=$now"
        Log.i(TAG, navLoadReqMsg)
        com.remmi.browser.util.DebugLogManager.log(navLoadReqMsg)

        if (request.isRedirect && url.isNotBlank()) {
          lastRedirectUrls[tabId] = url
        }

        val tab = TabManager.getInstance().getTab(tabId)
        val isGhost = (tab?.profile == PrivacyProfile.GHOST) || (currentProfile == PrivacyProfile.GHOST)
        
        val check = com.remmi.browser.security.NavigationSecurityAuthority.validateAndSanitizeNavigation(url, isGhost)
        when (check.decision) {
            com.remmi.browser.security.NavigationDecision.BLOCK -> {
                val blockMsg = "[FORENSIC] [NAV_ERROR] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$url error=security_blocked gen=$gen elapsedRealtime=$now"
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

        val activeRecovery = activeRecoveries[tabId]
        if (activeRecovery != null && 
            activeRecovery.session === session && 
            activeRecovery.generation == gen) {
          if (request.isRedirect && url.isNotBlank() && !isInternalOrIgnoredUrl(url)) {
            recordRecoveryRedirect(tabId, url)
          }
          logNavCorrelation(tabId, navId, gen, url, "onLoadRequest", "recovery_inflight")
        } else if (url.isBlank() || isInternalOrIgnoredUrl(url)) {
          logNavAllocationRejected(tabId, navId, gen, url, "onLoadRequest", "internal_or_ignored")
        } else {
          val isInFlight = inFlightNavigations.containsKey(tabId)
          val prevDispatched = lastDispatchedUrls[tabId]
          val prevObserved = lastObservedUrls[tabId]
          val isEquivalentToDispatched = areUrlsEquivalent(prevDispatched, url)
          val isEquivalentToObserved = areUrlsEquivalent(prevObserved, url)
          val isSameUrl = isEquivalentToDispatched || isEquivalentToObserved

          if (request.isRedirect) {
            logNavCorrelation(tabId, navId, gen, url, "onLoadRequest", "redirect")
          } else if (isInFlight && (isSameUrl || isEquivalentToDispatched)) {
            // Belongs to the existing in-flight app navigation intent (e.g. loadUrl, reload, etc.)
            logNavCorrelation(tabId, navId, gen, url, "onLoadRequest", "correlated_to_inflight_intent")
          } else if (isSameUrl && !request.hasUserGesture) {
            logNavCorrelation(tabId, navId, gen, url, "onLoadRequest", "duplicate_or_same_url")
          } else if (request.hasUserGesture && !isSameUrl) {
            // Genuine user-gesture link click from inside the page!
            val prevUrl = prevObserved ?: prevDispatched
            lastDispatchedUrls[tabId] = url
            val (newNavId, newGen) = allocateNavigationGeneration(tabId, "USER_GESTURE", url)
            val inPageMsg = "[FORENSIC][IN_PAGE_NAV] tabId=$tabId session=$sessId view=$viewId navId=$newNavId url=$url prevUrl=$prevUrl newGen=$newGen trigger=onLoadRequest hasUserGesture=${request.hasUserGesture} elapsedRealtime=$now"
            Log.i(TAG, inPageMsg)
            com.remmi.browser.util.DebugLogManager.log(inPageMsg)

            val navReqMsg = "[FORENSIC] [NAV_REQUESTED] tabId=$tabId session=$sessId view=$viewId navId=$newNavId url=$url gen=$newGen trigger=USER_GESTURE elapsedRealtime=$now"
            Log.i(TAG, navReqMsg)
            com.remmi.browser.util.DebugLogManager.log(navReqMsg)
          } else {
            logNavCorrelation(tabId, navId, gen, url, "onLoadRequest", if (isInFlight) "inflight_active" else "unchanged")
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
        if (!isCallbackAuthoritative(tabId, session, "onLocationChange")) {
          return
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val genBefore = navGenerations[tabId] ?: 0L
        val activeNavId = getActiveNavId(tabId)
        val now = android.os.SystemClock.elapsedRealtime()
        val navLocMsg = "[FORENSIC] [NAV_LOCATION] tabId=$tabId session=$sessId view=$viewId navId=$activeNavId url=$url gen=$genBefore hasUserGesture=$hasUserGesture elapsedRealtime=$now"
        Log.i(TAG, navLocMsg)
        com.remmi.browser.util.DebugLogManager.log(navLocMsg)

        if (url == "about:blank") {
          checkPostNavFailure(tabId, "ABOUT_BLANK", "about:blank")
        }

        val prevObserved = lastObservedUrls[tabId]
        val prevDispatched = lastDispatchedUrls[tabId]

        if (url != null) {
          latestLocationUrls[tabId] = url
        }

        // Recovery In-Flight Detection
        val activeRecovery = activeRecoveries[tabId]
        val isRecoveryActive = activeRecovery != null && 
                               activeRecovery.session === session && 
                               activeRecovery.generation == genBefore &&
                               activeRecovery.stage != RecoveryStage.SUCCESS &&
                               activeRecovery.stage != RecoveryStage.FAILED

        if (isRecoveryActive) {
          val isAboutBlank = (url == "about:blank" || isInternalOrIgnoredUrl(url))
          if (isAboutBlank) {
            logRecoveryUrlState(tabId, activeNavId, genBefore, url, true, activeRecovery?.targetUrl, "TRANSIENT_ABOUT_BLANK", "SUPPRESS_LOCATION_UPDATE")
            return
          }

          if (url != null) {
            val isTargetMatch = isRecoveryTargetOrRedirect(url, activeRecovery!!)
            if (isTargetMatch) {
              activeRecovery.stage = RecoveryStage.NAV_IN_FLIGHT
              val inFlightMsg = "[FORENSIC][CONTENT_RECOVERY_NAV_IN_FLIGHT] tabId=$tabId session=$sessId view=$viewId navId=${activeRecovery.navId} url=$url targetUrl=${activeRecovery.targetUrl} gen=$genBefore elapsedRealtime=$now"
              Log.i(TAG, inFlightMsg)
              com.remmi.browser.util.DebugLogManager.log(inFlightMsg)
              logRecoveryUrlState(tabId, activeNavId, genBefore, url, true, activeRecovery.targetUrl, "RECOVERY_TARGET", "ADVANCE_STAGE_NAV_IN_FLIGHT")
              lastObservedUrls[tabId] = url
              lastDispatchedUrls[tabId] = url
              sessionCallbacks[tabId]?.onUrlChange(url)
              return
            }
          }
          logRecoveryUrlState(tabId, activeNavId, genBefore, url, true, activeRecovery?.targetUrl, "NORMAL", "PROCESS_NORMAL")
        } else {
          val blankClassification = if (url == "about:blank") "NORMAL_ABOUT_BLANK" else "NORMAL"
          logRecoveryUrlState(tabId, activeNavId, genBefore, url, false, null, blankClassification, "PROCESS_NORMAL")
        }

        if (url != null && !isInternalOrIgnoredUrl(url)) {
          lastObservedUrls[tabId] = url
        }

        if (activeRecovery == null && url != null && !isInternalOrIgnoredUrl(url)) {
          val isSameAsDispatched = areUrlsEquivalent(prevDispatched, url)
          val isSameAsObserved = areUrlsEquivalent(prevObserved, url)
          val hostChanged = isDifferentHost(prevObserved ?: prevDispatched, url)
          val isInFlight = inFlightNavigations.containsKey(tabId)

          val classification: String
          var genAfter: Long = genBefore

          if (isSameAsDispatched) {
            classification = "APP_REQUEST_MATCH"
            lastDispatchedUrls[tabId] = url
            inFlightNavigations.remove(tabId)
            logNavCorrelation(tabId, activeNavId, genBefore, url, "onLocationChange", "app_request_match")
          } else if (isSameAsObserved) {
            classification = "DUPLICATE_OBSERVATION"
            lastDispatchedUrls[tabId] = url
            logNavCorrelation(tabId, activeNavId, genBefore, url, "onLocationChange", "duplicate_observation")
          } else if (lastRedirectUrls[tabId] != null && areUrlsEquivalent(lastRedirectUrls[tabId], url)) {
            classification = "REDIRECT"
            lastDispatchedUrls[tabId] = url
            logNavCorrelation(tabId, activeNavId, genBefore, url, "onLocationChange", "redirect")
          } else if (isInFlight) {
            // Continuation / redirect / location resolution for existing in-flight navigation (prevent duplicate navId)
            classification = "IN_FLIGHT_LOCATION_MATCH"
            lastDispatchedUrls[tabId] = url
            logNavCorrelation(tabId, activeNavId, genBefore, url, "onLocationChange", "in_flight_location_match")
          } else if (!hostChanged && !hasUserGesture) {
            // Same-host SPA / script history change (e.g. DuckDuckGo replaceState / pushState)
            classification = "SAME_DOCUMENT_SPA"
            lastDispatchedUrls[tabId] = url
            logNavCorrelation(tabId, activeNavId, genBefore, url, "onLocationChange", "same_document_spa")
            val spaMsg = "[FORENSIC][NAV_SAME_DOC_SPA] tabId=$tabId session=$sessId view=$viewId navId=$activeNavId url=$url prevUrl=${prevObserved ?: prevDispatched} gen=$genBefore hasUserGesture=false elapsedRealtime=$now"
            Log.i(TAG, spaMsg)
            com.remmi.browser.util.DebugLogManager.log(spaMsg)
          } else {
            val prevUrl = prevObserved ?: prevDispatched
            lastDispatchedUrls[tabId] = url
            val (newNavId, newGen) = allocateNavigationGeneration(tabId, if (hasUserGesture) "USER_GESTURE" else "LOCATION_CHANGED", url)
            genAfter = newGen
            classification = if (hasUserGesture) "GENUINE_NEW_NAVIGATION" else "LOCATION_CHANGED"
            val inPageMsg = "[FORENSIC][IN_PAGE_NAV] tabId=$tabId session=$sessId view=$viewId navId=$newNavId url=$url prevUrl=$prevUrl newGen=$newGen trigger=onLocationChange hasUserGesture=$hasUserGesture elapsedRealtime=$now"
            Log.i(TAG, inPageMsg)
            com.remmi.browser.util.DebugLogManager.log(inPageMsg)
          }

          val evalMsg = "[FORENSIC][NAV_LOCATION_EVAL] tabId=$tabId prevObserved=$prevObserved prevDispatched=$prevDispatched canonicalCurrent=$url isSameAsObserved=$isSameAsObserved isSameAsDispatched=$isSameAsDispatched hostChanged=$hostChanged hasUserGesture=$hasUserGesture activeNavId=$activeNavId generationBefore=$genBefore generationAfter=$genAfter classification=$classification"
          Log.i(TAG, evalMsg)
          com.remmi.browser.util.DebugLogManager.log(evalMsg)
        }

        url?.let {
          if (it.isNotBlank()) {
            if (it != "about:blank") {
              try {
                val host = java.net.URI(if (it.contains("://")) it else "https://$it").host
                if (!host.isNullOrBlank()) {
                  applySiteSecurityPolicy(tabId, host)
                }
              } catch (_: Exception) {}
            }
            sessionCallbacks[tabId]?.onUrlChange(it)
          }
        }
      }

      override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        if (!isCallbackAuthoritative(tabId, session, "onCanGoBack")) {
          return
        }

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
        if (!isCallbackAuthoritative(tabId, session, "onCanGoForward")) {
          return
        }

        val current = sessionNavStates[tabId] ?: Pair(false, false)
        val updated = current.copy(second = canGoForward)
        sessionNavStates[tabId] = updated
        sessionCallbacks[tabId]?.onNavStateChange(updated.first, updated.second)
      }
    }

    // Wire Progress delegate
    session.progressDelegate = object : GeckoSession.ProgressDelegate {
      override fun onPageStart(session: GeckoSession, url: String) {
        if (!isCallbackAuthoritative(tabId, session, "onPageStart")) {
          return
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val gen = navGenerations[tabId] ?: 0L
        val navId = getActiveNavId(tabId)
        val now = android.os.SystemClock.elapsedRealtime()
        latestProgressUrls[tabId] = url
        val progMsg = "[FORENSIC] [NAV_PROGRESS] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$url progress=10 gen=$gen state=start elapsedRealtime=$now"
        Log.i(TAG, progMsg)
        com.remmi.browser.util.DebugLogManager.log(progMsg)

        val pageStartMsg = "[FORENSIC] [NAV_PAGE_START] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$url gen=$gen elapsedRealtime=$now"
        Log.i(TAG, pageStartMsg)
        com.remmi.browser.util.DebugLogManager.log(pageStartMsg)

        logContentProcessEvent(event = "READY", tabId = tabId, session = session, url = url, reason = "PAGE_START")
        getMemoryForensicSnapshot("NAV_START")

        val activeRecovery = activeRecoveries[tabId]
        if (activeRecovery != null && 
            activeRecovery.session === session && 
            activeRecovery.generation == gen && 
            activeRecovery.stage == RecoveryStage.DISPATCHED) {
          if (!isInternalOrIgnoredUrl(url)) {
            val isTargetMatch = isRecoveryTargetOrRedirect(url, activeRecovery)
            if (isTargetMatch) {
              activeRecovery.stage = RecoveryStage.NAV_IN_FLIGHT
              val inFlightMsg = "[FORENSIC][CONTENT_RECOVERY_NAV_IN_FLIGHT] tabId=$tabId session=$sessId view=$viewId navId=${activeRecovery.navId} url=$url targetUrl=${activeRecovery.targetUrl} gen=$gen elapsedRealtime=$now"
              Log.i(TAG, inFlightMsg)
              com.remmi.browser.util.DebugLogManager.log(inFlightMsg)
            }
          }
        }

        val oldProg = navProgressStates[tabId] ?: 0
        navLoadingStates[tabId] = true
        navProgressStates[tabId] = 10
        logProgressState(tabId, navId, gen, "NAV_START", oldProg, 10, true, true, "page_start")

        sessionCallbacks[tabId]?.onLoadingChange(true)
        sessionCallbacks[tabId]?.onProgressChange(10)
      }

      override fun onPageStop(session: GeckoSession, success: Boolean) {
        if (!isCallbackAuthoritative(tabId, session, "onPageStop")) {
          return
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val currUrl = lastObservedUrls[tabId] ?: lastDispatchedUrls[tabId] ?: "unknown"
        val gen = navGenerations[tabId] ?: 0L
        val navId = getActiveNavId(tabId)
        val now = android.os.SystemClock.elapsedRealtime()
        val stopMsg = "[FORENSIC] [NAV_STOP] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl success=$success gen=$gen elapsedRealtime=$now"
        Log.i(TAG, stopMsg)
        com.remmi.browser.util.DebugLogManager.log(stopMsg)

        inFlightNavigations.remove(tabId)
        getMemoryForensicSnapshot("NAV_STOP")

        if (success) {
          lastSuccessfulNavigations[tabId] = SuccessfulNavRecord(
            navId = navId,
            url = currUrl,
            gen = gen,
            timestampElapsed = now,
          )
        }

        val activeRecovery = activeRecoveries[tabId]
        val latestLoc = latestLocationUrls[tabId]
        val latestProg = latestProgressUrls[tabId]
        val isAboutBlank = (currUrl == "about:blank" || latestLoc == "about:blank" || latestProg == "about:blank" || isInternalOrIgnoredUrl(currUrl))
        if (latestLoc == "about:blank" || isInternalOrIgnoredUrl(latestLoc)) {
          latestLocationUrls.remove(tabId)
        }
        if (latestProg == "about:blank" || isInternalOrIgnoredUrl(latestProg)) {
          latestProgressUrls.remove(tabId)
        }

        val isRecoveryActive = activeRecovery != null && 
                               activeRecovery.session === session && 
                               activeRecovery.generation == gen &&
                               activeRecovery.stage != RecoveryStage.SUCCESS && 
                               activeRecovery.stage != RecoveryStage.FAILED

        if (isRecoveryActive) {
          val targetMatches = !isAboutBlank && isRecoveryTargetOrRedirect(currUrl, activeRecovery!!)

          if (isAboutBlank && !targetMatches) {
            // Transient about:blank stop during recovery - ignore and do not clear recovery or loading state
            val oldProg = navProgressStates[tabId] ?: 0
            logProgressState(tabId, navId, gen, "NAV_STOP", oldProg, oldProg, true, false, "transient_about_blank_in_recovery")
            logRecoveryUrlState(tabId, navId, gen, currUrl, true, activeRecovery.targetUrl, "TRANSIENT_RECOVERY_BLANK", "SUPPRESS_PAGE_STOP")
            return
          }

          if (success && targetMatches) {
            activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            activeRecovery.stage = RecoveryStage.SUCCESS
            transitionRecoveryState(tabId, RecoveryState.SUCCESS, activeRecovery.navId, gen, "recovery_success")
            activeRecoveries.remove(tabId)
            lastRecoveredGenerations.remove(tabId)
            val origFail = lastOriginalFailures[tabId] ?: "NONE"
            val succMsg = "[FORENSIC][CONTENT_RECOVERY_NAV_SUCCESS] tabId=$tabId session=$sessId view=$viewId navId=${activeRecovery.navId} url=${activeRecovery.targetUrl} gen=$gen originalFailure=$origFail elapsedRealtime=$now"
            Log.i(TAG, succMsg)
            com.remmi.browser.util.DebugLogManager.log(succMsg)
            logContentProcessEvent(event = "RECOVER", tabId = tabId, session = session, url = activeRecovery.targetUrl, reason = "RECOVERY_SUCCESS")
            getMemoryForensicSnapshot("RECOVERY_SUCCESS")

            val oldProg = navProgressStates[tabId] ?: 0
            navLoadingStates[tabId] = false
            navProgressStates[tabId] = 0
            logProgressState(tabId, navId, gen, "NAV_STOP", oldProg, 0, false, true, "recovery_success")
            logRecoveryUrlState(tabId, navId, gen, currUrl, false, activeRecovery.targetUrl, "RECOVERY_TARGET", "FINALIZE_SUCCESS")

            sessionCallbacks[tabId]?.onLoadingChange(false)
            sessionCallbacks[tabId]?.onProgressChange(0)
            return
          } else if (!success && targetMatches) {
            activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            activeRecovery.stage = RecoveryStage.FAILED
            transitionRecoveryState(tabId, RecoveryState.FAILED, activeRecovery.navId, gen, "recovery_failed")
            activeRecoveries.remove(tabId)
            lastRecoveredGenerations[tabId] = gen
            val failMsg = "[FORENSIC][CONTENT_RECOVERY_NAV_FAILED] tabId=$tabId session=$sessId view=$viewId navId=${activeRecovery.navId} url=$currUrl targetUrl=${activeRecovery.targetUrl} gen=$gen success=$success elapsedRealtime=$now"
            Log.w(TAG, failMsg)
            com.remmi.browser.util.DebugLogManager.log(failMsg)

            val oldProg = navProgressStates[tabId] ?: 0
            navLoadingStates[tabId] = false
            navProgressStates[tabId] = 0
            logProgressState(tabId, navId, gen, "NAV_STOP", oldProg, 0, false, true, "recovery_failed")
            logRecoveryUrlState(tabId, navId, gen, currUrl, false, activeRecovery.targetUrl, "RECOVERY_TARGET", "FINALIZE_FAILED")

            sessionCallbacks[tabId]?.onLoadingChange(false)
            sessionCallbacks[tabId]?.onProgressChange(0)
            return
          }
        }

        if (!success) {
          checkPostNavFailure(tabId, "PAGE_STOP_FAILED", currUrl)
        }

        val oldProg = navProgressStates[tabId] ?: 0
        navLoadingStates[tabId] = false
        navProgressStates[tabId] = 0
        logProgressState(tabId, navId, gen, "NAV_STOP", oldProg, 0, false, true, "navigation_complete")

        sessionCallbacks[tabId]?.onLoadingChange(false)
        sessionCallbacks[tabId]?.onProgressChange(0)
      }

      override fun onProgressChange(session: GeckoSession, progress: Int) {
        if (!isCallbackAuthoritative(tabId, session, "onProgressChange")) {
          return
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val currUrl = lastDispatchedUrls[tabId] ?: "unknown"
        val gen = navGenerations[tabId] ?: 0L
        val navId = getActiveNavId(tabId)
        val now = android.os.SystemClock.elapsedRealtime()

        val isLoading = navLoadingStates[tabId] ?: false
        val oldProg = navProgressStates[tabId] ?: 0

        if (!isLoading) {
          logProgressState(tabId, navId, gen, "NAV_PROGRESS", oldProg, progress, false, false, "stale_navigation_completed")
          return
        }

        val newProg = maxOf(oldProg, progress.coerceIn(0, 100))
        navProgressStates[tabId] = newProg
        logProgressState(tabId, navId, gen, "NAV_PROGRESS", oldProg, newProg, true, true, "progress_update")

        val progMsg = "[FORENSIC] [NAV_PROGRESS] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl progress=$newProg gen=$gen state=update elapsedRealtime=$now"
        Log.i(TAG, progMsg)

        sessionCallbacks[tabId]?.onProgressChange(newProg)
      }

      override fun onSecurityChange(
        session: GeckoSession,
        securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
      ) {
        if (!isCallbackAuthoritative(tabId, session, "onSecurityChange")) {
          return
        }
        sessionCallbacks[tabId]?.onSecurityChange(securityInfo.isSecure)
      }
    }

    // Wire Content delegate
    session.contentDelegate = object : GeckoSession.ContentDelegate {
      override fun onCrash(session: GeckoSession) {
        if (!isCallbackAuthoritative(tabId, session, "onCrash")) {
          return
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val view = attachedViews[tabId]
        val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val currUrl = lastDispatchedUrls[tabId] ?: "unknown"
        val gen = navGenerations[tabId] ?: 0L
        val navId = getActiveNavId(tabId)
        val isOpen = session.isOpen
        val threadName = Thread.currentThread().name
        val now = android.os.SystemClock.elapsedRealtime()
        val crashMsg = "[FORENSIC][CONTENT_CRASH] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen isOpen=$isOpen thread=$threadName elapsedRealtime=$now"
        Log.e(TAG, crashMsg)
        com.remmi.browser.util.DebugLogManager.log(crashMsg)

        checkPostNavFailure(tabId, "CONTENT_CRASH", currUrl)
        logContentProcessEvent(event = "CRASH", tabId = tabId, session = session, url = currUrl, reason = "GECKO_CRASH")
        getMemoryForensicSnapshot("CONTENT_KILL")
        handleContentProcessTermination(tabId, session, "CRASH")
      }

      override fun onKill(session: GeckoSession) {
        if (!isCallbackAuthoritative(tabId, session, "onKill")) {
          return
        }

        val sessId = "0x" + Integer.toHexString(System.identityHashCode(session))
        val view = attachedViews[tabId]
        val viewId = view?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
        val currUrl = lastDispatchedUrls[tabId] ?: "unknown"
        val gen = navGenerations[tabId] ?: 0L
        val navId = getActiveNavId(tabId)
        val isOpen = session.isOpen
        val threadName = Thread.currentThread().name
        val now = android.os.SystemClock.elapsedRealtime()
        val killMsg = "[FORENSIC][CONTENT_KILL] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen isOpen=$isOpen thread=$threadName elapsedRealtime=$now"
        Log.e(TAG, killMsg)
        com.remmi.browser.util.DebugLogManager.log(killMsg)

        checkPostNavFailure(tabId, "CONTENT_KILL", currUrl)
        logContentProcessEvent(event = "KILL", tabId = tabId, session = session, url = currUrl, reason = "GECKO_KILL")
        getMemoryForensicSnapshot("CONTENT_KILL")
        handleContentProcessTermination(tabId, session, "KILL")
      }

      override fun onTitleChange(session: GeckoSession, title: String?) {
        if (!isCallbackAuthoritative(tabId, session, "onTitleChange")) {
          return
        }

        title?.let {
          if (it.isNotBlank() && it != "about:blank") {
            sessionCallbacks[tabId]?.onTitleChange(it)
          }
        }
      }

      override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        if (!isCallbackAuthoritative(tabId, session, "onExternalResponse")) {
          return
        }
        sessionCallbacks[tabId]?.onExternalResponse(response)
      }

      override fun onContextMenu(
        session: GeckoSession,
        screenX: Int,
        screenY: Int,
        element: GeckoSession.ContentDelegate.ContextElement
      ) {
        if (!isCallbackAuthoritative(tabId, session, "onContextMenu", isViewBound = true)) {
          return
        }

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
        if (!isCallbackAuthoritative(tabId, session, "onScrollChanged", isViewBound = true)) {
          return
        }

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
    val viewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val threadName = Thread.currentThread().name
    val navId = pending.navId
    currentNavIds[tabId] = navId
    
    val isRecoveryActive = activeRecoveries.containsKey(tabId) || pendingContentRecoveries.containsKey(tabId)
    val isActualDuplicate = (lastDispatchedUrls[tabId] == pending.url) && !isRecoveryActive

    if (isActualDuplicate) {
      val skipMsg = "[FORENSIC] [GECKO_NAV_SKIPPED_DUPLICATE] tabId=$tabId session=$sessId view=$viewId navId=$navId gen=${pending.generation} url=${pending.url} thread=$threadName"
      Log.i(TAG, skipMsg)
      com.remmi.browser.util.DebugLogManager.log(skipMsg)
      return
    }
    
    lastDispatchedUrls[tabId] = pending.url
    dispatchedNavigationsHistory.getOrPut(tabId) { mutableListOf() }.add(pending.url)
    val dispatchMsg = "[FORENSIC] [GECKO_NAV_DISPATCH] tabId=$tabId session=$sessId view=$viewId navId=$navId gen=${pending.generation} url=${pending.url} thread=$threadName"
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
      val crashInFlightMsg = "[FORENSIC][CONTENT_RECOVERY_CRASH_IN_FLIGHT] tabId=$tabId session=$sessId view=$viewId navId=${inFlightRecovery.navId} url=${inFlightRecovery.targetUrl} gen=${inFlightRecovery.generation} stage=${inFlightRecovery.stage} termination=$terminationType elapsedRealtime=$now"
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
      val navId = getActiveNavId(tabId)
      pendingContentRecoveries[tabId] = PendingContentRecovery(
        session = session,
        url = currUrl,
        generation = gen,
        navId = navId,
        terminationType = terminationType,
      )
      transitionRecoveryState(tabId, RecoveryState.PENDING_DETACHED, navId, gen, "view_absent_deferred")
      val defMsg = "[FORENSIC][CONTENT_RECOVERY_DEFERRED] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen termination=$terminationType elapsedRealtime=$now"
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
    val navId = getActiveNavId(tabId)
    transitionRecoveryState(tabId, RecoveryState.STARTING, navId, gen, "foreground_$terminationType")
    val startMsg = "[FORENSIC][CONTENT_RECOVERY_START] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen termination=$terminationType elapsedRealtime=$now"
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
    val loadMsg = "[FORENSIC][CONTENT_RECOVERY_LOAD] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen elapsedRealtime=$now"
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
        navId = navId,
        startTime = now,
        stage = RecoveryStage.DISPATCHED,
      )
      val timeoutRunnable = Runnable {
        val current = activeRecoveries[tabId]
        if (current != null && current.generation == gen && current.stage != RecoveryStage.SUCCESS && current.stage != RecoveryStage.FAILED) {
          activeRecoveries.remove(tabId)
          lastRecoveredGenerations[tabId] = gen
          transitionRecoveryState(tabId, RecoveryState.FAILED, navId, gen, "recovery_timeout")
          val toMsg = "[FORENSIC][CONTENT_RECOVERY_TIMEOUT] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen stage=${current.stage} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
          Log.w(TAG, toMsg)
          com.remmi.browser.util.DebugLogManager.log(toMsg)

          val oldProg = navProgressStates[tabId] ?: 0
          navLoadingStates[tabId] = false
          navProgressStates[tabId] = 0
          logProgressState(tabId, navId, gen, "NAV_STOP", oldProg, 0, false, true, "recovery_timeout")
          logRecoveryUrlState(tabId, navId, gen, currUrl, false, current.targetUrl, "RECOVERY_TARGET", "FINALIZE_TIMEOUT")

          sessionCallbacks[tabId]?.onLoadingChange(false)
          sessionCallbacks[tabId]?.onProgressChange(0)
        }
      }
      recovery.timeoutRunnable = timeoutRunnable
      mainHandler.postDelayed(timeoutRunnable, 15000L)
      activeRecoveries[tabId] = recovery
      transitionRecoveryState(tabId, RecoveryState.IN_FLIGHT, navId, gen, "dispatched_load")

      val dispMsg = "[FORENSIC][CONTENT_RECOVERY_DISPATCHED] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, dispMsg)
      com.remmi.browser.util.DebugLogManager.log(dispMsg)
    } catch (e: Exception) {
      transitionRecoveryState(tabId, RecoveryState.FAILED, navId, gen, "dispatch_exception_${e.message}")
      val failMsg = "[FORENSIC][CONTENT_RECOVERY_FAILED] tabId=$tabId session=$sessId view=$viewId navId=$navId url=$currUrl gen=$gen error=${e.message} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
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
      transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, pendingRecovery.navId, pendingRecovery.generation, "stale_or_inactive_session")
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
      transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, pendingRecovery.navId, pendingRecovery.generation, "superseded_by_newer_navigation")
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=${pendingRecovery.url} gen=${pendingRecovery.generation} currentGen=$currentGen reason=superseded_by_newer_navigation elapsedRealtime=$now"
      Log.i(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    // Verify URL validity
    val targetUrl = pendingRecovery.url.ifBlank {
      lastObservedUrls[tabId]?.takeIf { !isInternalOrIgnoredUrl(it) }
        ?: TabManager.getInstance().getTab(tabId)?.url?.takeIf { !isInternalOrIgnoredUrl(it) }
        ?: lastDispatchedUrls[tabId]?.takeIf { !isInternalOrIgnoredUrl(it) }
        ?: ""
    }
    if (isInternalOrIgnoredUrl(targetUrl)) {
      pendingContentRecoveries.remove(tabId)
      transitionRecoveryState(tabId, RecoveryState.FAILED, pendingRecovery.navId, pendingRecovery.generation, "invalid_or_blank_url")
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=$targetUrl gen=${pendingRecovery.generation} reason=invalid_or_blank_url elapsedRealtime=$now"
      Log.i(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    // Check generation loop suppression
    val lastRecoveredGen = lastRecoveredGenerations[tabId]
    if (lastRecoveredGen != null && lastRecoveredGen == pendingRecovery.generation) {
      pendingContentRecoveries.remove(tabId)
      transitionRecoveryState(tabId, RecoveryState.FAILED, pendingRecovery.navId, pendingRecovery.generation, "max_attempts_exceeded")
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=$targetUrl gen=${pendingRecovery.generation} reason=max_attempts_exceeded elapsedRealtime=$now"
      Log.w(TAG, suppMsg)
      com.remmi.browser.util.DebugLogManager.log(suppMsg)
      return
    }

    lastDispatchedUrls[tabId] = targetUrl
    val resumeMsg = "[FORENSIC][CONTENT_RECOVERY_RESUME] tabId=$tabId session=$sessId view=$viewId navId=${pendingRecovery.navId} url=$targetUrl gen=${pendingRecovery.generation} termination=${pendingRecovery.terminationType} elapsedRealtime=$now"
    Log.i(TAG, resumeMsg)
    com.remmi.browser.util.DebugLogManager.log(resumeMsg)

    lastRecoveredGenerations[tabId] = pendingRecovery.generation

    transitionRecoveryState(tabId, RecoveryState.STARTING, pendingRecovery.navId, pendingRecovery.generation, "view_reattached")
    val startMsg = "[FORENSIC][CONTENT_RECOVERY_START] tabId=$tabId session=$sessId view=$viewId navId=${pendingRecovery.navId} url=$targetUrl gen=${pendingRecovery.generation} termination=${pendingRecovery.terminationType} elapsedRealtime=$now"
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

    val loadMsg = "[FORENSIC][CONTENT_RECOVERY_LOAD] tabId=$tabId session=$sessId view=$viewId navId=${pendingRecovery.navId} url=$targetUrl gen=${pendingRecovery.generation} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
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
        navId = pendingRecovery.navId,
        startTime = now,
        stage = RecoveryStage.DISPATCHED,
      )
      val timeoutRunnable = Runnable {
        val current = activeRecoveries[tabId]
        if (current != null && current.generation == pendingRecovery.generation && current.stage != RecoveryStage.SUCCESS && current.stage != RecoveryStage.FAILED) {
          activeRecoveries.remove(tabId)
          lastRecoveredGenerations[tabId] = pendingRecovery.generation
          transitionRecoveryState(tabId, RecoveryState.FAILED, pendingRecovery.navId, pendingRecovery.generation, "recovery_timeout")
          val toMsg = "[FORENSIC][CONTENT_RECOVERY_TIMEOUT] tabId=$tabId session=$sessId view=$viewId navId=${pendingRecovery.navId} url=$targetUrl gen=${pendingRecovery.generation} stage=${current.stage} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
          Log.w(TAG, toMsg)
          com.remmi.browser.util.DebugLogManager.log(toMsg)
        }
      }
      recovery.timeoutRunnable = timeoutRunnable
      mainHandler.postDelayed(timeoutRunnable, 15000L)
      activeRecoveries[tabId] = recovery
      transitionRecoveryState(tabId, RecoveryState.IN_FLIGHT, pendingRecovery.navId, pendingRecovery.generation, "dispatched_load")

      val dispMsg = "[FORENSIC][CONTENT_RECOVERY_DISPATCHED] tabId=$tabId session=$sessId view=$viewId navId=${pendingRecovery.navId} url=$targetUrl gen=${pendingRecovery.generation} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, dispMsg)
      com.remmi.browser.util.DebugLogManager.log(dispMsg)
    } catch (e: Exception) {
      transitionRecoveryState(tabId, RecoveryState.FAILED, pendingRecovery.navId, pendingRecovery.generation, "dispatch_exception_${e.message}")
      val failMsg = "[FORENSIC][CONTENT_RECOVERY_FAILED] tabId=$tabId session=$sessId view=$viewId navId=${pendingRecovery.navId} url=$targetUrl gen=${pendingRecovery.generation} error=${e.message} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
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
      viewGenerations[tabId] = (viewGenerations[tabId] ?: 0L) + 1L
      logSessionViewBinding(tabId, session, geckoView, true, "attachView")

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

    logSessionViewBinding(tabId, session, geckoView, false, "detachViewSync")
    logDestructiveOp("DETACH_VIEW", tabId, session, geckoView, currUrl, "detachViewSync")
    checkPostNavFailure(tabId, "DETACH_VIEW", currUrl)

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
    
    val (navId, gen) = allocateNavigationGeneration(tabId, "loadUrl", targetUrl)
    val now = android.os.SystemClock.elapsedRealtime()
    val navReqMsg = "[FORENSIC] [NAV_REQUESTED] tabId=$tabId navId=$navId url=$targetUrl gen=$gen trigger=loadUrl elapsedRealtime=$now"
    Log.i(TAG, navReqMsg)
    com.remmi.browser.util.DebugLogManager.log(navReqMsg)

    val session = activeSessions[tabId]
    val sessId = session?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val threadName = Thread.currentThread().name

    val activeRecovery = activeRecoveries.remove(tabId)
    if (activeRecovery != null) {
      activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
      transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, activeRecovery.navId, activeRecovery.generation, "superseded_by_loadUrl")
      val superMsg = "[FORENSIC][CONTENT_RECOVERY_SUPERSEDED] tabId=$tabId session=$sessId url=${activeRecovery.targetUrl} newUrl=$targetUrl gen=${activeRecovery.generation} newGen=$gen elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      Log.i(TAG, superMsg)
      com.remmi.browser.util.DebugLogManager.log(superMsg)
    }
    val pendingRec = pendingContentRecoveries.remove(tabId)
    if (pendingRec != null) {
      transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, pendingRec.navId, pendingRec.generation, "superseded_by_loadUrl")
    }

    if (_initState.value == GeckoInitState.NOT_STARTED) {
      Log.d(TAG, "[GECKO] loadUrl requesting init on tabId=$tabId")
      initializeRuntimeAsync()
    }

    val isRuntimeReady = (_initState.value == GeckoInitState.READY && (runtime != null || uriLoaderForTest != null))
    val isAttached = isViewAttached(tabId)

    if (!isRuntimeReady || !isAttached) {
      val queueMsg = "[FORENSIC] [GECKO_NAV_QUEUE] tabId=$tabId session=$sessId navId=$navId gen=$gen url=$targetUrl thread=$threadName"
      Log.i(TAG, queueMsg)
      com.remmi.browser.util.DebugLogManager.log(queueMsg)

      // Store latest navigation only (replaces any previous pending navigation for this tab)
      pendingNavigations[tabId] = PendingNavigation(targetUrl, gen, navId)
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
    val currentViewId = attachedViews[tabId]?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"

    val isRecoveryActive = activeRecovery != null || pendingContentRecoveries.containsKey(tabId)
    val isActualDuplicate = (lastDispatchedUrls[tabId] == targetUrl) && !isRecoveryActive

    if (isActualDuplicate) {
      logNavDuplicateClassification(
        tabId = tabId,
        navId = navId,
        generation = gen,
        previousNavId = currentNavIds[tabId] ?: 0L,
        previousGeneration = gen,
        classification = "APP_LOAD_URL",
        trigger = "loadUrl",
        reason = "url_already_dispatched"
      )
      logNavAllocationRejected(
        tabId = tabId,
        navId = navId,
        generation = gen,
        url = targetUrl,
        trigger = "loadUrl",
        reason = "url_already_dispatched"
      )
      val skipMsg = "[FORENSIC] [GECKO_NAV_SKIPPED_DUPLICATE] tabId=$tabId session=$currentSessId view=$currentViewId navId=$navId gen=$gen url=$targetUrl thread=$threadName"
      Log.i(TAG, skipMsg)
      com.remmi.browser.util.DebugLogManager.log(skipMsg)
      return
    }

    lastDispatchedUrls[tabId] = targetUrl
    pendingNavigations.remove(tabId)
    dispatchedNavigationsHistory.getOrPut(tabId) { mutableListOf() }.add(targetUrl)
    val dispatchMsg = "[FORENSIC] [GECKO_NAV_DISPATCH] tabId=$tabId session=$currentSessId view=$currentViewId navId=$navId gen=$gen url=$targetUrl thread=$threadName"
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
      val url = lastDispatchedUrls[tabId] ?: "reload"
      val (navId, gen) = allocateNavigationGeneration(tabId, "reload", url)
      lastDispatchedUrls.remove(tabId)
      val pendingRec = pendingContentRecoveries.remove(tabId)
      if (pendingRec != null) {
        transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, pendingRec.navId, pendingRec.generation, "superseded_by_reload")
      }
      val activeRecovery = activeRecoveries.remove(tabId)
      if (activeRecovery != null) {
        activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, activeRecovery.navId, activeRecovery.generation, "superseded_by_reload")
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
      logDestructiveOp("RESET_TO_NEW_TAB", tabId, session, null, null, "resetToNewTab")
      checkPostNavFailure(tabId, "RESET_TO_NEW_TAB")
      val pendingRec = pendingContentRecoveries.remove(tabId)
      if (pendingRec != null) {
        transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, pendingRec.navId, pendingRec.generation, "superseded_by_reset_to_new_tab")
      }
      val activeRecovery = activeRecoveries.remove(tabId)
      if (activeRecovery != null) {
        activeRecovery.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        transitionRecoveryState(tabId, RecoveryState.SUPERSEDED, activeRecovery.navId, activeRecovery.generation, "superseded_by_reset_to_new_tab")
        val superMsg = "[FORENSIC][CONTENT_RECOVERY_SUPERSEDED] tabId=$tabId url=${activeRecovery.targetUrl} reason=reset_to_new_tab elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
        Log.i(TAG, superMsg)
        com.remmi.browser.util.DebugLogManager.log(superMsg)
      }
      session.load(GeckoSession.Loader().uri("about:blank").flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY))
    }
  }

  fun stopLoading(tabId: String) {
    onMainSession(tabId, "STOP_LOADING") { session ->
      logDestructiveOp("STOP_LOADING", tabId, session, null, null, "stopLoading")
      checkPostNavFailure(tabId, "STOP_LOADING")
      session.stop()
    }
  }

  fun goBack(tabId: String) {
    onMainSession(tabId, "GO_BACK") { session ->
      allocateNavigationGeneration(tabId, "goBack", "history_back")
      Log.i(TAG, "[FORENSIC] NAV_BACK_GECKO tabId=$tabId sessionHash=${session.hashCode()} thread=${Thread.currentThread().name}")
      session.goBack()
    }
  }

  fun goForward(tabId: String) {
    onMainSession(tabId, "GO_FORWARD") { session ->
      allocateNavigationGeneration(tabId, "goForward", "history_forward")
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
    latestProgressUrls.remove(tabId)
    latestLocationUrls.remove(tabId)
    dispatchedNavigationsHistory.remove(tabId)
    navGenerations.remove(tabId)
    lastRecoveredGenerations.remove(tabId)
    val session = activeSessions.remove(tabId)
    if (session == null) {
      Log.d(TAG, "[GECKO] operation=CLOSE_NOT_FOUND id=$tabId thread=main")
      return@withContext CloseResult.NotFound
    }
    logDestructiveOp("CLOSE_SESSION", tabId, session, null, null, "closeSessionSafely")
    checkPostNavFailure(tabId, "CLOSE_SESSION")
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
    latestProgressUrls.clear()
    latestLocationUrls.clear()
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
      latestProgressUrls.clear()
      latestLocationUrls.clear()
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
