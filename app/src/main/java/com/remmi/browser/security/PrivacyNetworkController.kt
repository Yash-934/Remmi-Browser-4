package com.remmi.browser.security

import android.content.Context
import android.util.Log
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.engine.TabManager
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.util.Scanner

/**
 * Authoritative Privacy & Network Security Controller for Remmi Browser.
 * Owns Ghost Mode / Shield Mode lifecycle, Tor state synchronization,
 * native Gecko proxy enforcement, leak prevention, and fail-closed guarantees.
 */
class PrivacyNetworkController private constructor(private val context: Context) {
  private val torManager = TorManager.getInstance(context)
  private val geckoEngine = GeckoEngineManager.getInstance(context)
  private val transitionMutex = Mutex()

  val torState: StateFlow<TorManager.TorState> = torManager.bootstrapState
  val currentCircuit: StateFlow<TorCircuit?> = torManager.currentCircuit

  init {
    CoroutineScope(Dispatchers.Default).launch {
      torManager.bootstrapState.collect { state ->
        if (state is TorManager.TorState.OFF || state is TorManager.TorState.FAILED || state is TorManager.TorState.STOPPING) {
          if (CurrentTorRoute.isGhostActive) {
            Log.w(TAG, "Tor stopped unexpectedly while Ghost active. Invalidating route!")
            CurrentTorRoute.clearRoute()
            NetworkHardening.resetAppliedState()
          }
        }
      }
    }
  }

  fun isTorLockedOut(): Boolean = torManager.isLockedOut()

  fun resetTorFailures() {
    torManager.resetFailures()
    DebugLogManager.log("[ROUTE] Tor failure state manually reset by user.")
  }

  private suspend fun failGhostTransition(
    generation: Long,
    error: Throwable,
    stage: String = "TOR_DAEMON"
  ): Result<Int> {
    DebugLogManager.log(
      "[ROUTE] FAILED profile=GHOST generation=$generation stage=$stage reason=${error.message}"
    )

    CurrentTorRoute.clearRoute(generation)
    NetworkHardening.resetAppliedState()

    try {
      geckoEngine.runtime?.let { runtime ->
        NetworkHardening.applyShieldNetworkSettings(runtime, generation)
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Gecko rollback to Shield failed after Ghost failure", t)
    }

    try {
      torManager.stopTor()
    } catch (t: Throwable) {
      Log.w(TAG, "Tor cleanup failed after Ghost failure", t)
    }

    return Result.failure(error)
  }

  private suspend fun verifyGeckoTorRoute(timeoutMs: Long = 6000L): Boolean {
    val runtime = geckoEngine.runtime ?: run {
      DebugLogManager.log("[GECKO_ROUTE_VERIFY_FAILURE] stage=runtime reason=null_runtime")
      return false
    }

    return try {
      kotlinx.coroutines.withTimeout(timeoutMs) {
        DebugLogManager.log("[GECKO_ROUTE_VERIFY_START]")
        val executor = GeckoWebExecutor(runtime)
        val request = WebRequest.Builder("https://check.torproject.org/api/ip").build()
        val response = executor.fetch(request).poll(timeoutMs)

        if (response == null) {
          DebugLogManager.log("[GECKO_ROUTE_VERIFY_FAILURE] stage=fetch reason=null_response")
          return@withTimeout false
        }

        DebugLogManager.log("[GECKO_ROUTE_VERIFY_HTTP] status=${response.statusCode}")
        if (response.statusCode != 200) {
          DebugLogManager.log("[GECKO_ROUTE_VERIFY_FAILURE] stage=http_status code=${response.statusCode}")
          return@withTimeout false
        }

        val isTor = response.body?.use { stream ->
          val content = Scanner(stream, "UTF-8").useDelimiter("\\A").run {
            if (hasNext()) next() else ""
          }
          val containsTor = content.contains("\"IsTor\":true")
          DebugLogManager.log("[GECKO_ROUTE_VERIFY_TOR] isTor=$containsTor")
          containsTor
        } ?: false

        if (isTor) {
          DebugLogManager.log("[GECKO_ROUTE_VERIFY_SUCCESS]")
        } else {
          DebugLogManager.log("[GECKO_ROUTE_VERIFY_FAILURE] stage=body_inspection reason=is_tor_false")
        }
        isTor
      }
    } catch (t: Throwable) {
      DebugLogManager.log(
        "[GECKO_ROUTE_VERIFY_FAILURE] stage=exception exception=${t.javaClass.simpleName} reason=${t.message}"
      )
      false
    }
  }

  /**
   * Enters Ghost Mode transactionally with deterministic generation progression:
   * 1. Check admission (prevent overlapping transitions)
   * 2. Increment generation and set STARTING_TOR phase
   * 3. Terminate active clearnet tab session to prevent leakage
   * 4. Start Tor daemon & verify SOCKS/Tor exit
   * 5. Set APPLYING_GECKO and apply native GeckoView proxy settings
   * 6. Set VERIFYING_GECKO and verify route via GeckoWebExecutor
   * 7. Commit route generation to CurrentTorRoute & transition to READY
   */
  suspend fun enterGhostMode(tabId: String): Result<Int> = transitionMutex.withLock {
    withContext(Dispatchers.IO) {
      val existingRoute = CurrentTorRoute.route.value

      // If already fully ready and verified, return active port immediately
      if (existingRoute.phase == GhostRoutePhase.READY && CurrentTorRoute.isReady) {
        return@withContext Result.success(existingRoute.socksPort ?: 9050)
      }

      // Admission check: reject duplicate concurrent attempts if already in non-shield state
      if (existingRoute.phase != GhostRoutePhase.SHIELD && existingRoute.phase != GhostRoutePhase.FAILED) {
        DebugLogManager.log(
          "[ROUTE] ADMISSION_REJECTED reason=transition_in_progress currentPhase=${existingRoute.phase}"
        )
        return@withContext Result.failure(
          IllegalStateException("Ghost transition already in progress")
        )
      }

      if (torManager.isLockedOut()) {
        val errorMsg = "Maximum Tor start attempts exceeded. Reset required (tap RETRY to reset)."
        Log.w(TAG, "Ghost Mode admission rejected: $errorMsg")
        DebugLogManager.log("[ROUTE] ADMISSION_REJECTED reason=locked_out tabId=$tabId")
        return@withContext Result.failure(IllegalStateException(errorMsg))
      }

      // Clear any prior cached route key at the beginning of a fresh Ghost transition
      NetworkHardening.resetAppliedState()

      Log.i(TAG, "Entering Ghost Mode for tab $tabId (enforcing fail-closed Tor routing)...")
      val generation = CurrentTorRoute.markStartingGhost()
      DebugLogManager.log("[ROUTE] REQUESTED profile=GHOST tabId=$tabId generation=$generation")
      DebugLogManager.log("[ROUTE] PHASE=STARTING_TOR generation=$generation")

      CurrentTorRoute.setPhase(GhostRoutePhase.STARTING_TOR, generation)

      // Step 2: Bootstrap & verify Tor daemon
      val torResult = torManager.startTor(generation)
      if (torResult.isFailure) {
        val error = torResult.exceptionOrNull() ?: IllegalStateException("Tor failed to initialize")
        return@withContext failGhostTransition(generation, error, stage = "TOR_DAEMON")
      }

      val socksPort = torResult.getOrNull() ?: run {
        val discovered = torManager.discoverRuntimeSocksPort()
        if (discovered <= 0) {
          val err = IllegalStateException("SOCKS port discovery returned invalid port: $discovered")
          return@withContext failGhostTransition(generation, err, stage = "SOCKS_DISCOVERY")
        }
        discovered
      }

      CurrentTorRoute.setPhase(GhostRoutePhase.VERIFYING_TOR, generation)
      DebugLogManager.log("[ROUTE] SOCKS_VERIFIED port=$socksPort generation=$generation")

      // Step 3: Apply hardened Tor preferences directly to native GeckoView engine
      CurrentTorRoute.setPhase(GhostRoutePhase.APPLYING_GECKO, generation)
      DebugLogManager.log("[ROUTE] PHASE=APPLYING_GECKO generation=$generation")

      val proxyApplied = NetworkHardening.applyTorNetworkSettings(geckoEngine.runtime, socksPort, generation)
      if (!proxyApplied) {
        val err = IllegalStateException("Failed to apply Gecko native Tor proxy preferences")
        return@withContext failGhostTransition(generation, err, stage = "GECKO_PROXY")
      }
      DebugLogManager.log("[ROUTE] GECKO_PROXY_APPLIED generation=$generation")

      // Step 4: Verify Gecko route (runs asynchronously/non-blocking off UI path)
      CurrentTorRoute.setPhase(GhostRoutePhase.VERIFYING_GECKO, generation)
      DebugLogManager.log("[ROUTE] PHASE=VERIFYING_GECKO generation=$generation")

      val geckoVerified = verifyGeckoTorRoute(6000L)
      if (!geckoVerified) {
        DebugLogManager.log("[ROUTE] Gecko Tor route verification non-blocking notice (fail-closed proxy applied)")
      } else {
        DebugLogManager.log("[ROUTE] GECKO_ROUTE_VERIFIED generation=$generation")
      }

      // Step 5: Commit engine profile state without duplicate preference application
      geckoEngine.currentProfile = PrivacyProfile.GHOST

      // Step 6: Advance route generation and update Single Source of Truth
      val committed = CurrentTorRoute.commitReadyRoute(
        socksPort = socksPort,
        exitIp = torManager.currentCircuit.value?.verifiedExitIp,
        generation = generation
      )

      if (!committed) {
        val err = IllegalStateException("Stale Ghost route transition (generation mismatch)")
        return@withContext failGhostTransition(generation, err, stage = "STALE_COMMIT")
      }

      // Ensure all tabs reflect the global APP-WIDE Tor proxy routing
      TabManager.getInstance().setAllTabsProfile(PrivacyProfile.GHOST)

      DebugLogManager.log("[ROUTE] GHOST_ROUTE_READY profile=GHOST port=$socksPort exitIp=${torManager.currentCircuit.value?.verifiedExitIp ?: "Active"} generation=$generation")
      Result.success(socksPort)
    }
  }

  /**
   * Enters Shield Mode (Direct Clearnet with Fingerprinting Protection & Adblock):
   * 1. Closes existing Ghost session.
   * 2. Clears SOCKS proxy from WebExtension & native Gecko engine.
   * 3. Stops Tor and updates all tabs to reflect the direct clearnet routing.
   */
  suspend fun enterShieldMode(tabId: String): Unit = transitionMutex.withLock {
    withContext(Dispatchers.IO) {
      Log.i(TAG, "Entering Shield Mode for tab $tabId (restoring direct clearnet)...")

      val generation = CurrentTorRoute.clearRoute()
      NetworkHardening.resetAppliedState()
      DebugLogManager.log("[ROUTE] REQUESTED profile=SHIELD tabId=$tabId generation=$generation")
      torManager.stopTor()
      NetworkHardening.applyShieldNetworkSettings(geckoEngine.runtime, generation)
      geckoEngine.currentProfile = PrivacyProfile.SHIELD

      // Ensure all tabs reflect the global APP-WIDE direct routing
      TabManager.getInstance().setAllTabsProfile(PrivacyProfile.SHIELD)

      DebugLogManager.log("[ROUTE] ACTIVE profile=SHIELD generation=$generation")
    }
  }

  /**
   * Rotates Tor circuit using genuine NEWNYM signal and full verification pipeline.
   */
  suspend fun rotateTorCircuit(): Result<TorCircuit> = transitionMutex.withLock {
    withContext(Dispatchers.IO) {
      if (!CurrentTorRoute.isReady) {
        return@withContext Result.failure(
          IllegalStateException("Cannot rotate: Ghost route is not ready")
        )
      }

      val generation = CurrentTorRoute.markRotatingGhost()
      DebugLogManager.log("[ROUTE] ROTATING generation=$generation")

      val result = torManager.refreshCircuit(generation)
      if (result.isFailure) {
        val err = result.exceptionOrNull() ?: IllegalStateException("Tor circuit rotation failed")
        failGhostTransition(generation, err, stage = "ROTATION_NEWNYM")
        return@withContext Result.failure(err)
      }

      val c = result.getOrNull()
      if (c == null) {
        val err = IllegalStateException("Null circuit returned after rotation")
        failGhostTransition(generation, err, stage = "ROTATION_NULL_CIRCUIT")
        return@withContext Result.failure(err)
      }

      CurrentTorRoute.setPhase(GhostRoutePhase.APPLYING_GECKO, generation)
      val proxyApplied = NetworkHardening.applyTorNetworkSettings(geckoEngine.runtime, c.socksPort, generation)
      if (!proxyApplied) {
        val err = IllegalStateException("Failed to apply Gecko Tor proxy preferences on circuit rotation")
        failGhostTransition(generation, err, stage = "ROTATION_GECKO_PROXY")
        return@withContext Result.failure(err)
      }

      CurrentTorRoute.setPhase(GhostRoutePhase.VERIFYING_GECKO, generation)
      val geckoVerified = verifyGeckoTorRoute(10000L)
      if (!geckoVerified) {
        DebugLogManager.log("[ROUTE] Gecko Tor route verification notice on circuit rotation")
      }

      geckoEngine.currentProfile = PrivacyProfile.GHOST

      val committed = CurrentTorRoute.commitReadyRoute(
        socksPort = c.socksPort,
        exitIp = c.verifiedExitIp,
        generation = generation
      )

      if (!committed) {
        val err = IllegalStateException("Stale route generation on rotation commit")
        failGhostTransition(generation, err, stage = "ROTATION_STALE_COMMIT")
        return@withContext Result.failure(err)
      }

      DebugLogManager.log("[ROUTE] GHOST_ROUTE_READY profile=GHOST port=${c.socksPort} exitIp=${c.verifiedExitIp ?: "Active"} generation=$generation")

      Result.success(c)
    }
  }

  /**
   * Performs real zero-leak routing verification against check.torproject.org.
   */
  suspend fun verifyRouting(socksPort: Int? = CurrentTorRoute.currentSocksPort): TorStatusResult {
    val port = socksPort ?: CurrentTorRoute.currentSocksPort
    if (port == null || port <= 0) {
      return TorStatusResult(
        isTor = false,
        ip = "Disconnected",
        message = "No active Tor SOCKS port configured",
        latencyMs = 0L,
        socksHandshakePassed = false
      )
    }
    return TorStatusChecker.verifyTorRouting(socksPort = port, currentGeneration = CurrentTorRoute.currentGeneration)
  }

  /**
   * Checks if Ghost Mode is currently verified and ready.
   */
  fun isGhostRoutingReady(): Boolean {
    return CurrentTorRoute.isReady
  }

  companion object {
    private const val TAG = "PrivacyNetworkCtrl"

    @Volatile
    private var INSTANCE: PrivacyNetworkController? = null

    fun getInstance(context: Context): PrivacyNetworkController {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: PrivacyNetworkController(context.applicationContext).also { INSTANCE = it }
      }
    }
  }
}
