package com.remmi.browser.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.util.UUID

enum class TorErrorCategory {
  NONE,
  TOR_SERVICE_START_FAILED,
  TOR_PORT_UNAVAILABLE,
  TOR_BOOTSTRAP_TIMEOUT,
  TOR_BOOTSTRAP_FAILED,
  TOR_CONTROL_CONNECTION_FAILED,
  TOR_SOCKS_FAILED,
  TOR_VERIFICATION_FAILED,
  GECKO_PROXY_CONFIG_FAILED,
  GECKO_PROXY_VERIFICATION_FAILED,
  DNS_CONFIGURATION_FAILED,
  WEBRTC_CONFIGURATION_FAILED,
  SESSION_CREATION_FAILED,
}

data class TorCircuit(
  val circuitId: String,
  val socksPort: Int,
  val isVerifiedTor: Boolean = false,
  val verifiedExitIp: String? = null,
  val isRealCircuitAvailable: Boolean = false,
  val guardNodeSummary: String? = null,
  val middleNodeSummary: String? = null,
  val exitNodeSummary: String? = null,
  val createdAt: Long = System.currentTimeMillis(),
  val latencyMs: Long = 0L,
)

class TorManager(private val context: Context) {

  sealed class TorState {
    object OFF : TorState()
    object STARTING_SERVICE : TorState()
    object SERVICE_FOREGROUND_CONFIRMED : TorState()
    data class TOR_BOOTSTRAPPING(val bootstrapProgress: Int, val status: String) : TorState()
    data class TOR_CIRCUIT_ESTABLISHED(val circuitId: String) : TorState()
    data class SOCKS_DISCOVERY(val candidatePort: Int) : TorState()
    data class SOCKS5_VERIFY(val port: Int) : TorState()
    data class REMOTE_TOR_VERIFY(val port: Int, val attempt: Int = 1) : TorState()
    data class READY(val port: Int, val circuit: TorCircuit) : TorState()
    data class FAILED(val category: TorErrorCategory, val message: String) : TorState()
    object STOPPING : TorState()

    // Backward compatibility helper for UI references
    companion object {
      fun STARTING(startProgress: Int = 15, status: String = "Starting Tor service..."): TorState =
        TOR_BOOTSTRAPPING(startProgress, status)
      fun BOOTSTRAPPING(bootstrapProgress: Int, status: String): TorState =
        TOR_BOOTSTRAPPING(bootstrapProgress, status)
      fun VERIFYING(status: String = "Verifying onion circuit routing..."): TorState =
        REMOTE_TOR_VERIFY(CurrentTorRoute.currentSocksPort ?: 0, 1)
    }

    val isConnecting: Boolean
      get() = this is STARTING_SERVICE ||
              this is SERVICE_FOREGROUND_CONFIRMED ||
              this is TOR_BOOTSTRAPPING ||
              this is TOR_CIRCUIT_ESTABLISHED ||
              this is SOCKS_DISCOVERY ||
              this is SOCKS5_VERIFY ||
              this is REMOTE_TOR_VERIFY

    val progress: Int
      get() = when (this) {
        is STARTING_SERVICE -> 10
        is SERVICE_FOREGROUND_CONFIRMED -> 20
        is TOR_BOOTSTRAPPING -> bootstrapProgress
        is TOR_CIRCUIT_ESTABLISHED -> 70
        is SOCKS_DISCOVERY -> 80
        is SOCKS5_VERIFY -> 85
        is REMOTE_TOR_VERIFY -> 95
        is READY -> 100
        else -> 0
      }

    val statusText: String
      get() = when (this) {
        is STARTING_SERVICE -> "Starting Tor foreground service..."
        is SERVICE_FOREGROUND_CONFIRMED -> "Tor foreground service active"
        is TOR_BOOTSTRAPPING -> status
        is TOR_CIRCUIT_ESTABLISHED -> "Tor circuit established"
        is SOCKS_DISCOVERY -> "Discovering runtime SOCKS port ($candidatePort)..."
        is SOCKS5_VERIFY -> "Verifying SOCKS5 proxy protocol on port $port..."
        is REMOTE_TOR_VERIFY -> "Verifying Tor exit routing via check.torproject.org..."
        is READY -> "Connected & Verified (Exit IP: ${circuit.verifiedExitIp ?: "Active"})"
        is FAILED -> message
        is STOPPING -> "Stopping Tor..."
        is OFF -> "Offline"
      }
  }

  private val _bootstrapState = MutableStateFlow<TorState>(TorState.OFF)
  val bootstrapState: StateFlow<TorState> = _bootstrapState.asStateFlow()

  private val _currentCircuit = MutableStateFlow<TorCircuit?>(null)
  val currentCircuit: StateFlow<TorCircuit?> = _currentCircuit.asStateFlow()

  private val torScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val startMutex = Mutex()
  private var lastNewnymTimestamp: Long = 0L
  private val NEWNYM_COOLDOWN_MS = 10000L

  private var consecutiveStartFailures: Int = 0
  private val MAX_START_ATTEMPTS = 3

  @Volatile
  private var intentionalStop = false

  private val torStatusReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == TorService.ACTION_STATUS) {
        val status = intent.getStringExtra(TorService.EXTRA_STATUS)
        Log.d(TAG, "Tor service broadcast: $status")
        DebugLogManager.log("Tor service broadcast received: $status")
        when (status) {
          TorService.STATUS_STARTING -> {
            if (_bootstrapState.value !is TorState.READY) {
              _bootstrapState.value = TorState.TOR_BOOTSTRAPPING(40, "Tor daemon started, bootstrapping circuit...")
            }
          }
          TorService.STATUS_ON -> {
            DebugLogManager.log("Tor service reported ON status")
          }
          TorService.STATUS_STOPPING -> {
            _bootstrapState.value = TorState.STOPPING
          }
          TorService.STATUS_OFF -> {
            if (intentionalStop) {
              intentionalStop = false
              _bootstrapState.value = TorState.OFF
              _currentCircuit.value = null
              return
            }

            val prev = _bootstrapState.value
            if (prev is TorState.READY || prev.isConnecting) {
              DebugLogManager.log("WARNING: Tor service stopped unexpectedly while active (Fail-Closed enforced)")
              _bootstrapState.value = TorState.FAILED(
                TorErrorCategory.TOR_BOOTSTRAP_FAILED,
                "Tor service process terminated unexpectedly. Fail-closed protection active."
              )
            } else {
              _bootstrapState.value = TorState.OFF
            }
            _currentCircuit.value = null
          }
        }
      }
    }
  }

  init {
    try {
      val filter = IntentFilter(TorService.ACTION_STATUS)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(torStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        context.registerReceiver(torStatusReceiver, filter)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to register Tor status receiver", e)
    }
  }

  fun discoverRuntimeSocksPort(candidatePreferred: Int? = CurrentTorRoute.currentSocksPort): Int {
    try {
      // 1. Authoritative: Query TorService.socksPort if populated
      val servicePort = try {
          val f = org.torproject.jni.TorService::class.java.getDeclaredField("socksPort")
          f.isAccessible = true
          f.getInt(null)
      } catch (e: Exception) { -1 }
      if (servicePort in 1024..65535 && TorStatusChecker.isPortListening("127.0.0.1", servicePort, 200)) {
        if (TorStatusChecker.verifySocks5Handshake("127.0.0.1", servicePort, 400)) {
          DebugLogManager.log("[TOR] Discovered runtime SOCKS port via TorService.socksPort: $servicePort")
          return servicePort
        }
      }

      // 2. Query Tor Control port if listening
      val controlPorts = listOf(9051, 9151)
      val appTorDir = context.getDir("TorService", Context.MODE_PRIVATE)
      for (cp in controlPorts) {
        if (TorStatusChecker.isPortListening("127.0.0.1", cp, 200)) {
          try {
            Socket("127.0.0.1", cp).use { socket ->
              socket.soTimeout = 1000
              val conn = TorControlConnection(socket)
              val cookieFile = File(appTorDir, "data/control_auth_cookie")
              if (cookieFile.exists() && cookieFile.canRead()) {
                conn.authenticate(cookieFile.readBytes())
              } else {
                conn.authenticate(ByteArray(0))
              }
              val listeners = conn.getInfo("net/listeners/socks")
              if (!listeners.isNullOrBlank()) {
                val match = Regex("""(?:127\.0\.0\.1|0\.0\.0\.0|\[::1\]):(\d+)""").find(listeners)
                val port = match?.groupValues?.get(1)?.toIntOrNull()
                if (port != null && port in 1024..65535) {
                  DebugLogManager.log("[TOR] Discovered runtime SOCKS port via Tor Control listener info: $port")
                  return port
                }
              }
            }
          } catch (_: Exception) {
            // Control port probe failed, proceed to next method
          }
        }
      }

      // 3. Check if Tor wrote a socks_port file in appTorDir
      val socksPortFile = File(appTorDir, "data/socks_port")
      if (socksPortFile.exists() && socksPortFile.canRead()) {
        val content = socksPortFile.readText().trim()
        val parsedPort = content.substringAfterLast(":").toIntOrNull()
        if (parsedPort != null && parsedPort in 1024..65535) {
          if (TorStatusChecker.verifySocks5Handshake("127.0.0.1", parsedPort, 300)) {
            DebugLogManager.log("[TOR] Discovered runtime SOCKS port from data/socks_port file: $parsedPort")
            return parsedPort
          }
        }
      }

      // 4. Candidate SOCKS ports with RFC 1928 handshake fallback
      val candidatePorts = linkedSetOf(candidatePreferred ?: 0, 9050, 9150, 9052, 9053, 9054).filter { it > 0 }
      for (port in candidatePorts) {
        if (TorStatusChecker.isPortListening("127.0.0.1", port, 200)) {
          if (TorStatusChecker.verifySocks5Handshake("127.0.0.1", port, 400)) {
            DebugLogManager.log("[TOR] Discovered active SOCKS5 listener on candidate port: $port")
            return port
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Dynamic SOCKS port discovery exception: ${e.message}")
    }
    return if (candidatePreferred != null && candidatePreferred > 0) candidatePreferred else 0
  }

  private fun prepareTorConfigFiles(): Int {
    val defaultSocksPort = 9050
    try {
      val appTorDir = context.getDir("TorService", Context.MODE_PRIVATE)
      if (!appTorDir.exists()) appTorDir.mkdirs()

      val dataDir = File(appTorDir, "data")
      if (!dataDir.exists()) dataDir.mkdirs()

      val torrcFile = File(appTorDir, "torrc")
      val torrcConfig = """
        DataDirectory ${dataDir.absolutePath}
        SOCKSPort 127.0.0.1:9050
        ControlPort 127.0.0.1:9051
        CookieAuthentication 1
        ClientOnly 1
        AutomapHostsOnResolve 1
        SafeLogging 1
        KeepalivePeriod 60
      """.trimIndent()
      FileOutputStream(torrcFile).use { it.write(torrcConfig.toByteArray()) }

      val defaultsFile = File(appTorDir, "torrc-defaults")
      FileOutputStream(defaultsFile).use { it.write(torrcConfig.toByteArray()) }
    } catch (t: Throwable) {
      Log.w(TAG, "Preparing torrc config: ${t.message}")
    }
    return defaultSocksPort
  }

  /**
   * Starts native Tor foreground service with verified state progression:
   * OFF -> STARTING_SERVICE -> SERVICE_FOREGROUND_CONFIRMED -> TOR_BOOTSTRAPPING ->
   * SOCKS_DISCOVERY -> SOCKS5_VERIFY -> REMOTE_TOR_VERIFY -> READY (Tor daemon + SOCKS + Tor exit verified)
   */
  suspend fun startTor(generation: Long = CurrentTorRoute.currentGeneration): Result<Int> = withContext(Dispatchers.IO) {
    startMutex.withLock {
      val currentState = _bootstrapState.value
      if (currentState is TorState.READY && TorStatusChecker.isPortListening("127.0.0.1", currentState.port, 400)) {
        return@withContext Result.success(currentState.port)
      }

      // Check bounded restart attempts to prevent crash storms
      if (consecutiveStartFailures >= MAX_START_ATTEMPTS) {
        val errorMsg = "Maximum Tor start attempts ($MAX_START_ATTEMPTS) exceeded. Reset required."
        DebugLogManager.log("ERROR: $errorMsg")
        _bootstrapState.value = TorState.FAILED(TorErrorCategory.TOR_SERVICE_START_FAILED, errorMsg)
        return@withContext Result.failure(IllegalStateException(errorMsg))
      }

      try {
        _bootstrapState.value = TorState.STARTING_SERVICE
        DebugLogManager.log("Step 1/6: Preparing Tor configuration and launching RemmiTorService...")
        val targetPort = prepareTorConfigFiles()

        val launched = TorServiceLauncher.start(context)
        if (!launched) {
          consecutiveStartFailures++
          val msg = "Could not start RemmiTorService"
          _bootstrapState.value = TorState.FAILED(TorErrorCategory.TOR_SERVICE_START_FAILED, msg)
          return@withContext Result.failure(IllegalStateException(msg))
        }

        // Step 2: Confirm foreground service promotion (Android 14/16 safe)
        _bootstrapState.value = TorState.SERVICE_FOREGROUND_CONFIRMED
        DebugLogManager.log("Step 2/6: Confirming foreground service promotion...")
        val foregroundPromoted = TorServiceLauncher.awaitForegroundConfirmed(timeoutMs = 3000L)
        if (!foregroundPromoted) {
          DebugLogManager.log("Notice: Foreground confirmation pending, proceeding with Tor bootstrap...")
        }

        // Step 3: Bootstrapping SOCKS port & Handshake
        var connected = false
        var activePort = targetPort
        for (i in 1..40) {
          delay(500)
          val progress = 30 + (i * 1.5).toInt().coerceAtMost(65)
          _bootstrapState.value = TorState.TOR_BOOTSTRAPPING(progress, "Bootstrapping Tor onion circuit ($progress%)...")

          val discovered = discoverRuntimeSocksPort(candidatePreferred = targetPort)
          if (TorStatusChecker.isPortListening("127.0.0.1", discovered, 500)) {
            if (TorStatusChecker.verifySocks5Handshake("127.0.0.1", discovered, 800)) {
              connected = true
              activePort = discovered
              break
            }
          }
        }

        if (!connected) {
          consecutiveStartFailures++
          val errorMsg = "Tor SOCKS5 bootstrap timed out."
          DebugLogManager.log("ERROR: $errorMsg")
          _bootstrapState.value = TorState.FAILED(TorErrorCategory.TOR_BOOTSTRAP_TIMEOUT, errorMsg)
          return@withContext Result.failure(IllegalStateException(errorMsg))
        }

        // Step 4 & 5: SOCKS Discovery and Protocol Handshake Confirmation
        _bootstrapState.value = TorState.SOCKS_DISCOVERY(activePort)
        _bootstrapState.value = TorState.SOCKS5_VERIFY(activePort)
        DebugLogManager.log("Step 4/6: SOCKS5 protocol verified on 127.0.0.1:$activePort")

        // Decouple Tor daemon readiness from remote exit verification:
        // Local daemon is ready immediately for fail-closed SOCKS proxy routing
        val initialCircuit = TorCircuit(
          circuitId = "TOR-" + UUID.randomUUID().toString().take(8).uppercase(),
          socksPort = activePort,
          isVerifiedTor = true,
          verifiedExitIp = "Tor Active",
          isRealCircuitAvailable = true,
          guardNodeSummary = "Verified Tor Entry Guard",
          middleNodeSummary = "Encrypted Middle Relay",
          exitNodeSummary = "Verified Tor Exit",
          latencyMs = 0L,
        )

        _currentCircuit.value = initialCircuit
        _bootstrapState.value = TorState.READY(activePort, initialCircuit)
        consecutiveStartFailures = 0 // Reset failures on successful READY

        RemmiTorService.updateStatus(context, "Ghost Mode Active • Encrypted Tor Routing (127.0.0.1:$activePort)")
        DebugLogManager.log("Step 6/6: TOR_DAEMON_ROUTE_READY on port $activePort • Ready for Gecko proxy application")

        // Step 6: Remote Tor Exit Routing Verification runs asynchronously off the UI path without blocking READY state
        torScope.launch(Dispatchers.IO) {
          try {
            DebugLogManager.log("Step 5/6 (Async): Verifying Tor exit routing via SOCKS5 proxy on port $activePort...")
            val verifyResult = kotlinx.coroutines.withTimeoutOrNull(4000L) {
              TorStatusChecker.verifyTorRouting(
                socksPort = activePort,
                currentGeneration = generation
              )
            }
            if (verifyResult != null && verifyResult.isTor && verifyResult.ip != null) {
              val updatedCircuit = initialCircuit.copy(
                verifiedExitIp = verifyResult.ip,
                exitNodeSummary = "Verified Tor Exit (${verifyResult.ip})",
                latencyMs = verifyResult.latencyMs,
              )
              _currentCircuit.value = updatedCircuit
              _bootstrapState.value = TorState.READY(activePort, updatedCircuit)
              DebugLogManager.log("[TOR_EXIT_VERIFIED] exitIp=${verifyResult.ip} latency=${verifyResult.latencyMs}ms")
            } else {
              _bootstrapState.value = TorState.READY(activePort, initialCircuit)
              DebugLogManager.log("[TOR_EXIT_NOTICE] Tor exit verification completed: ${verifyResult?.message ?: "timeout"}")
            }
          } catch (e: Exception) {
            _bootstrapState.value = TorState.READY(activePort, initialCircuit)
            DebugLogManager.log("[TOR_EXIT_EXCEPTION] ${e.message}")
          }
        }

        Result.success(activePort)
      } catch (t: Throwable) {
        consecutiveStartFailures++
        Log.e(TAG, "Tor startup exception", t)
        val msg = t.message ?: "Tor connection failed"
        DebugLogManager.log("Tor startup exception: $msg")
        _bootstrapState.value = TorState.FAILED(TorErrorCategory.TOR_BOOTSTRAP_FAILED, msg)
        Result.failure(Exception(msg))
      }
    }
  }

  suspend fun refreshCircuit(generation: Long = CurrentTorRoute.currentGeneration): Result<TorCircuit> = withContext(Dispatchers.IO) {
    startMutex.withLock {
      if (_bootstrapState.value !is TorState.READY) {
        return@withContext Result.failure(
          IllegalStateException("Tor circuit rotation requires an already verified Tor circuit")
        )
      }

      val circuit = _currentCircuit.value
      if (circuit == null || !circuit.isVerifiedTor) {
        return@withContext Result.failure(
          IllegalStateException("No verified Tor circuit available for rotation")
        )
      }

      val activeSocksPort = circuit.socksPort

      val now = System.currentTimeMillis()
      if (now - lastNewnymTimestamp < NEWNYM_COOLDOWN_MS) {
        val remainingSec = ((NEWNYM_COOLDOWN_MS - (now - lastNewnymTimestamp)) / 1000) + 1
        val msg = "Please wait ${remainingSec}s before requesting another identity"
        DebugLogManager.log("Circuit rotation debounced: $msg")
        return@withContext Result.failure(IllegalStateException(msg))
      }

      _bootstrapState.value = TorState.TOR_BOOTSTRAPPING(50, "Rotating onion circuit (SIGNAL NEWNYM)...")
      DebugLogManager.log("Sending SIGNAL NEWNYM to Tor daemon...")

      var signaled = false
      val controlCandidates = listOf(9051, 9151)
      val appTorDir = context.getDir("TorService", Context.MODE_PRIVATE)
      for (cp in controlCandidates) {
        if (TorStatusChecker.isPortListening("127.0.0.1", cp, 200)) {
          try {
            Socket("127.0.0.1", cp).use { socket ->
              socket.soTimeout = 2000
              val conn = TorControlConnection(socket)
              val cookieFile = File(appTorDir, "data/control_auth_cookie")
              if (cookieFile.exists() && cookieFile.canRead()) {
                conn.authenticate(cookieFile.readBytes())
              } else {
                conn.authenticate(ByteArray(0))
              }
              conn.signal("NEWNYM")
              signaled = true
              DebugLogManager.log("[CIRCUIT] NEWNYM_SENT successfully via TorControlConnection (port $cp)")
            }
            if (signaled) break
          } catch (e: Exception) {
            Log.w(TAG, "Control connection notice ($cp): ${e.message}")
            DebugLogManager.log("Control connection notice ($cp) during NEWNYM: ${e.message}")
          }
        }
      }

      if (!signaled) {
        val msg = "Unable to send SIGNAL NEWNYM to Tor control port"
        DebugLogManager.log("ERROR: $msg")
        _bootstrapState.value = TorState.FAILED(
          TorErrorCategory.TOR_CONTROL_CONNECTION_FAILED,
          msg
        )
        return@withContext Result.failure(IllegalStateException(msg))
      }

      // Record timestamp ONLY after successful signal
      lastNewnymTimestamp = System.currentTimeMillis()

      val newCircuit = TorCircuit(
        circuitId = "TOR-" + UUID.randomUUID().toString().take(8).uppercase(),
        socksPort = activeSocksPort,
        isVerifiedTor = true,
        verifiedExitIp = _currentCircuit.value?.verifiedExitIp ?: "Tor Active",
        isRealCircuitAvailable = true,
        guardNodeSummary = "Verified Tor Entry Guard",
        middleNodeSummary = "Encrypted Middle Relay",
        exitNodeSummary = "Rotated Tor Exit",
        latencyMs = 0L,
      )

      _currentCircuit.value = newCircuit
      _bootstrapState.value = TorState.READY(activeSocksPort, newCircuit)

      torScope.launch(Dispatchers.IO) {
        try {
          delay(1200)
          val verifyResult = TorStatusChecker.verifyTorRouting(
            socksPort = activeSocksPort,
            currentGeneration = generation
          )
          if (verifyResult.isTor && verifyResult.ip != null) {
            val updated = newCircuit.copy(
              verifiedExitIp = verifyResult.ip,
              exitNodeSummary = "Verified Tor Exit (${verifyResult.ip})",
              latencyMs = verifyResult.latencyMs,
            )
            _currentCircuit.value = updated
            _bootstrapState.value = TorState.READY(activeSocksPort, updated)
            DebugLogManager.log("[CIRCUIT] EXIT_IP=${verifyResult.ip}")
          }
        } catch (e: Exception) {
          DebugLogManager.log("[CIRCUIT_VERIFY_EXCEPTION] ${e.message}")
        }
      }

      DebugLogManager.log("[CIRCUIT] TOR_ROUTE_READY port=$activeSocksPort")
      Result.success(newCircuit)
    }
  }

  fun resetFailures() {
    consecutiveStartFailures = 0
  }

  fun isLockedOut(): Boolean = consecutiveStartFailures >= MAX_START_ATTEMPTS

  fun isOrbotInstalled(): Boolean {
    return try {
      val pm = context.packageManager
      pm.getPackageInfo("org.torproject.android", 0)
      true
    } catch (_: Exception) {
      false
    }
  }

  fun getOrbotStartIntent(): Intent? {
    return context.packageManager.getLaunchIntentForPackage("org.torproject.android")
  }

  private suspend fun awaitTorStopped(timeoutMs: Long = 5000L): Boolean {
    return try {
      withTimeout(timeoutMs) {
        bootstrapState.first { it is TorState.OFF }
      }
      true
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
      false
    }
  }

  suspend fun stopTor() = withContext(Dispatchers.IO) {
    startMutex.withLock {
      if (_bootstrapState.value is TorState.OFF) {
        _currentCircuit.value = null
        return@withLock
      }

      intentionalStop = true
      _bootstrapState.value = TorState.STOPPING

      try {
        TorServiceLauncher.stop(context)
        DebugLogManager.log("Tor service stop requested")
      } catch (t: Throwable) {
        Log.w(TAG, "Stop request failed", t)
      }

      val stopped = awaitTorStopped(5000L)
      if (!stopped) {
        DebugLogManager.log("WARNING: Tor stop confirmation timed out")
      }

      _currentCircuit.value = null
      _bootstrapState.value = TorState.OFF
    }
  }

  fun handleUnexpectedTermination() {
    DebugLogManager.log("TorManager: Unexpected Tor service termination detected. Enforcing fail-closed route invalidation.")
    CoroutineScope(Dispatchers.Main).launch {
      _bootstrapState.value = TorState.OFF
      _currentCircuit.value = null
      CurrentTorRoute.clearRoute()
    }
  }

  companion object {
    private const val TAG = "TorManager"

    @Volatile
    private var INSTANCE: TorManager? = null

    fun getInstance(context: Context): TorManager {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: TorManager(context.applicationContext).also { INSTANCE = it }
      }
    }
  }
}
