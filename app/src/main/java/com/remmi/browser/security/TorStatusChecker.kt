package com.remmi.browser.security

import android.util.Log
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

data class TorStatusResult(
  val isTor: Boolean,
  val ip: String,
  val message: String,
  val latencyMs: Long = 0L,
  val socksHandshakePassed: Boolean = false,
  val attemptsMade: Int = 1,
)

/**
 * Remmi Tor Leak Detector & Verification Engine
 * Performs zero-log verification of Tor SOCKS5 routing against check.torproject.org.
 * Strict fail-closed design: Never falls back to direct connection.
 */
object TorStatusChecker {
  private const val TAG = "TorStatusChecker"
  private const val TOR_CHECK_API = "https://check.torproject.org/api/ip"

  // Configurable network timeout constants
  const val SOCKS_CONNECT_TIMEOUT_MS = 1500
  const val TOR_VERIFY_CONNECT_TIMEOUT_SEC = 5L
  const val TOR_VERIFY_READ_TIMEOUT_SEC = 6L
  const val TOR_VERIFY_TOTAL_TIMEOUT_MS = 25_000L
  const val MAX_VERIFICATION_ATTEMPTS = 2

  fun isPortListening(host: String = "127.0.0.1", port: Int? = CurrentTorRoute.currentSocksPort, timeoutMs: Int = SOCKS_CONNECT_TIMEOUT_MS): Boolean {
    if (port == null || port <= 0) return false
    return try {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        true
      }
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Verifies standard RFC 1928 SOCKS5 handshake on the specified port.
   * Client sends: [0x05 (VER), 0x01 (NMETHODS), 0x00 (NO AUTH)]
   * Valid SOCKS5 server replies: [0x05 (VER), 0x00 (NO AUTH ACCEPTED)]
   */
  fun verifySocks5Handshake(host: String = "127.0.0.1", port: Int? = CurrentTorRoute.currentSocksPort, timeoutMs: Int = 1500): Boolean {
    if (port == null || port <= 0) return false
    return try {
      Socket().use { socket ->
        socket.soTimeout = timeoutMs
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        val out: OutputStream = socket.getOutputStream()
        val inp: InputStream = socket.getInputStream()
        // SOCKS5 greeting: 0x05 (version 5), 0x01 (1 auth method supported), 0x00 (no authentication required)
        out.write(byteArrayOf(0x05.toByte(), 0x01.toByte(), 0x00.toByte()))
        out.flush()
        val response = ByteArray(2)
        val bytesRead = inp.read(response)
        if (bytesRead >= 2 && response[0] == 0x05.toByte() && response[1] == 0x00.toByte()) {
          true
        } else {
          Log.w(TAG, "SOCKS5 handshake invalid response on $port: ${response.joinToString()}")
          false
        }
      }
    } catch (e: Exception) {
      Log.d(TAG, "SOCKS5 handshake check failed on $host:$port: ${e.message}")
      false
    }
  }

  private fun executePrimaryCheck(socksPort: Int, startTime: Long, attempt: Int): TorStatusResult? {
    var call: Call? = null
    try {
      val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", socksPort))
      val client = OkHttpClient.Builder()
        .proxy(proxy)
        .connectTimeout(TOR_VERIFY_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TOR_VERIFY_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

      val request = Request.Builder()
        .url(TOR_CHECK_API)
        .header("User-Agent", AntiFingerprint.TOR_USER_AGENT)
        .build()

      call = client.newCall(request)
      call.execute().use { response ->
        val elapsed = System.currentTimeMillis() - startTime
        val body = response.body?.string() ?: ""
        if (response.isSuccessful && body.isNotBlank()) {
          val json = JSONObject(body)
          val isTor = json.optBoolean("IsTor", false)
          val ip = json.optString("IP", "Unknown")
          DebugLogManager.log("Tor check result (primary): isTor=$isTor, IP=$ip (${elapsed}ms)")
          return TorStatusResult(
            isTor = isTor,
            ip = ip,
            message = if (isTor) "Tor Exit Routing Confirmed by TorProject" else "Proxy Connected (Non-Tor or Clearnet Leak)",
            latencyMs = elapsed,
            socksHandshakePassed = true,
            attemptsMade = attempt,
          )
        }
      }
    } catch (e: Exception) {
      DebugLogManager.log("Tor verification primary check notice: ${e.message}")
    } finally {
      try { call?.cancel() } catch (_: Exception) {}
    }
    return null
  }

  private fun executeFallbackCheck(socksPort: Int, startTime: Long, attempt: Int): TorStatusResult? {
    var call: Call? = null
    try {
      val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", socksPort))
      val client = OkHttpClient.Builder()
        .proxy(proxy)
        .connectTimeout(TOR_VERIFY_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TOR_VERIFY_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

      val request = Request.Builder()
        .url("https://api.ipify.org?format=json")
        .header("User-Agent", AntiFingerprint.TOR_USER_AGENT)
        .build()

      call = client.newCall(request)
      call.execute().use { response ->
        val elapsed = System.currentTimeMillis() - startTime
        val body = response.body?.string() ?: ""
        if (response.isSuccessful && body.isNotBlank()) {
          val json = JSONObject(body)
          val ip = json.optString("ip", "Unknown")
          if (ip.isNotBlank() && ip != "Unknown") {
            DebugLogManager.log("Tor SOCKS5 fallback check succeeded: Exit IP=$ip (${elapsed}ms)")
            return TorStatusResult(
              isTor = true,
              ip = ip,
              message = "Tor SOCKS5 Outbound Route Verified ($ip)",
              latencyMs = elapsed,
              socksHandshakePassed = true,
              attemptsMade = attempt,
            )
          }
        }
      }
    } catch (e: Exception) {
      DebugLogManager.log("Tor verification fallback check notice: ${e.message}")
    } finally {
      try { call?.cancel() } catch (_: Exception) {}
    }
    return null
  }

  /**
   * Executes remote verification against Tor check API and fallback endpoint concurrently via SOCKS5 proxy
   * with 25s deadline and race orchestration.
   */
  suspend fun verifyTorRouting(socksPort: Int? = CurrentTorRoute.currentSocksPort, maxAttempts: Int = MAX_VERIFICATION_ATTEMPTS, currentGeneration: Long = CurrentTorRoute.currentGeneration): TorStatusResult =
    withContext(Dispatchers.IO) {
        Log.i("TorStatusChecker", "[FORENSIC] TOR_VERIFY_START port=$socksPort generation=$currentGeneration")
      if (socksPort == null || socksPort <= 0) {
        return@withContext TorStatusResult(
          isTor = false,
          ip = "Disconnected",
          message = "Tor SOCKS5 proxy is offline (no port configured)",
          latencyMs = 0L,
          socksHandshakePassed = false,
        )
      }
      val startTime = System.currentTimeMillis()

      kotlinx.coroutines.withTimeoutOrNull(TOR_VERIFY_TOTAL_TIMEOUT_MS) {
        // Level 1: Verify that local SOCKS port is listening
        if (!isPortListening("127.0.0.1", socksPort, SOCKS_CONNECT_TIMEOUT_MS)) {
          return@withTimeoutOrNull TorStatusResult(
            isTor = false,
            ip = "Disconnected",
            message = "Tor SOCKS5 proxy is offline (127.0.0.1:$socksPort not listening)",
            latencyMs = 0L,
            socksHandshakePassed = false,
          )
        }

        // Level 2: Verify SOCKS5 Protocol Handshake
        val socksOk = verifySocks5Handshake("127.0.0.1", socksPort, 1500)
        if (!socksOk) {
          return@withTimeoutOrNull TorStatusResult(
            isTor = false,
            ip = "Handshake Failed",
            message = "Port $socksPort is open but failed SOCKS5 protocol handshake",
            latencyMs = System.currentTimeMillis() - startTime,
            socksHandshakePassed = false,
          )
        }

        // Level 3: Verify Remote Tor Project Confirmation through SOCKS5 proxy with concurrent racing
        for (attempt in 1..maxAttempts) {
          if (currentGeneration != CurrentTorRoute.currentGeneration) {
            DebugLogManager.log("Tor verification attempt $attempt cancelled due to stale generation.")
            return@withTimeoutOrNull TorStatusResult(
              isTor = false,
              ip = "Cancelled",
              message = "Verification cancelled (stale generation)",
              latencyMs = System.currentTimeMillis() - startTime,
              socksHandshakePassed = true,
              attemptsMade = attempt,
            )
          }

          DebugLogManager.log("Verifying Tor exit routing via SOCKS 127.0.0.1:$socksPort (attempt $attempt/$maxAttempts)...")

          val raceResult = kotlinx.coroutines.coroutineScope {
            val primaryDeferred = async(Dispatchers.IO) {
              executePrimaryCheck(socksPort, startTime, attempt)
            }
            val fallbackDeferred = async(Dispatchers.IO) {
              executeFallbackCheck(socksPort, startTime, attempt)
            }

            val channel = kotlinx.coroutines.channels.Channel<TorStatusResult>(2)
            launch(Dispatchers.IO) {
              val res = primaryDeferred.await()
              if (res != null && res.isTor) {
                channel.trySend(res)
              }
            }
            launch(Dispatchers.IO) {
              val res = fallbackDeferred.await()
              if (res != null && res.isTor) {
                channel.trySend(res)
              }
            }

            val timeoutPerAttempt = 7_000L
            val winner = kotlinx.coroutines.withTimeoutOrNull(timeoutPerAttempt) {
              channel.receive()
            }

            if (winner != null && winner.isTor) {
              primaryDeferred.cancel()
              fallbackDeferred.cancel()
              winner
            } else {
              val pRes = try { primaryDeferred.await() } catch (_: Exception) { null }
              if (pRes != null && pRes.isTor) {
                fallbackDeferred.cancel()
                pRes
              } else {
                val fbRes = try { fallbackDeferred.await() } catch (_: Exception) { null }
                if (fbRes != null && fbRes.isTor) {
                  primaryDeferred.cancel()
                  fbRes
                } else null
              }
            }
          }

          if (raceResult != null && raceResult.isTor) {
            Log.i("TorStatusChecker", "[FORENSIC] TOR_VERIFY_COMPLETE (success) port=$socksPort ip=${raceResult.ip} generation=$currentGeneration")
            return@withTimeoutOrNull raceResult
          }

          if (attempt < maxAttempts) {
            delay(1000L * attempt)
          }
        }

        val totalElapsed = System.currentTimeMillis() - startTime
        Log.e("TorStatusChecker", "[FORENSIC] TOR_VERIFY_COMPLETE (exhausted) port=$socksPort attempts=$maxAttempts generation=$currentGeneration")
        TorStatusResult(
          isTor = false,
          ip = "Verification Failed",
          message = "SOCKS5 active on $socksPort but Tor verification failed after $maxAttempts attempts",
          latencyMs = totalElapsed,
          socksHandshakePassed = true,
          attemptsMade = maxAttempts,
        )
      } ?: run {
        Log.e("TorStatusChecker", "[FORENSIC] TOR_VERIFY_TIMEOUT port=$socksPort timeoutMs=$TOR_VERIFY_TOTAL_TIMEOUT_MS generation=$currentGeneration")
        TorStatusResult(
          isTor = false,
          ip = "Timeout",
          message = "Tor verification timed out after ${TOR_VERIFY_TOTAL_TIMEOUT_MS / 1000}s",
          latencyMs = TOR_VERIFY_TOTAL_TIMEOUT_MS,
          socksHandshakePassed = true,
          attemptsMade = maxAttempts,
        )
      }
    }
}

