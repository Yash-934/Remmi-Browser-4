package com.remmi.browser.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.remmi.browser.util.DebugLogManager
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoPreferenceController as NativePrefCtrl
import org.mozilla.geckoview.GeckoPreferenceController.SetGeckoPreference
import org.mozilla.geckoview.GeckoPreferenceController.GeckoPreference
import kotlin.coroutines.resume

class GeckoPreferenceController(private val runtime: GeckoRuntime?) {
  companion object {
    const val PREF_BRANCH_USER: Int = NativePrefCtrl.PREF_BRANCH_USER
    const val PREF_BRANCH_DEFAULT: Int = NativePrefCtrl.PREF_BRANCH_DEFAULT
    private const val TAG = "GeckoPreferenceCtrl"
    private val mainHandler = Handler(Looper.getMainLooper())
    
    val REQUIRED_PROXY_ROUTING = setOf(
      "network.proxy.type",
      "network.proxy.socks",
      "network.proxy.socks_port",
      "network.proxy.socks_version",
      "network.proxy.socks_remote_dns",
      "network.proxy.socks5_remote_dns",
      "network.proxy.failover_direct",
      "network.proxy.allow_bypass",
      "network.proxy.no_proxies_on"
    )
    
    val REQUIRED_GHOST_PRIVACY = setOf(
      "media.peerconnection.enabled"
    )

    private val appliedPrefsCache = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun resetCache() {
      appliedPrefsCache.clear()
    }
  }

  suspend fun getPreferences(keys: List<String>): Result<Map<String, Any?>> = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    mainHandler.post {
      try {
        NativePrefCtrl.getGeckoPrefs(keys.toMutableList()).accept(
          { result ->
            if (cont.isActive) {
              val map = mutableMapOf<String, Any?>()
              result?.forEach { pref ->
                map[pref.pref] = pref.value
              }
              cont.resume(Result.success(map))
            }
          },
          { error ->
            val ex = IllegalStateException(error?.message ?: "Unknown getGeckoPrefs error")
            Log.e(TAG, "getGeckoPrefs error: ${error?.message}")
            if (cont.isActive) cont.resume(Result.failure(ex))
          }
        )
      } catch (t: Throwable) {
        Log.e(TAG, "getGeckoPrefs exception: ${t.message}", t)
        if (cont.isActive) cont.resume(Result.failure(t))
      }
    }
  }

  suspend fun applyCriticalPreference(
    name: String,
    value: Any,
    branch: Int = PREF_BRANCH_USER
  ): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    if (appliedPrefsCache[name] == value) {
      if (cont.isActive) cont.resume(true)
      return@suspendCancellableCoroutine
    }

    val setter: SetGeckoPreference<*> = when (value) {
      is String -> SetGeckoPreference.setStringPref(name, value, branch)
      is Int -> SetGeckoPreference.setIntPref(name, value, branch)
      is Boolean -> SetGeckoPreference.setBoolPref(name, value, branch)
      else -> {
        DebugLogManager.log(
          "[GECKO_PHASE_A] UNSUPPORTED_TYPE key=$name type=${value::class.java.name}"
        )
        if (cont.isActive) cont.resume(false)
        return@suspendCancellableCoroutine
      }
    }

    mainHandler.post {
      try {
        DebugLogManager.log("[GECKO_PHASE_A_PREF_START] key=$name value=$value")
        NativePrefCtrl.setGeckoPrefs(mutableListOf(setter)).accept(
          { result ->
            val success = result?.get(name) == true
            if (success) {
              appliedPrefsCache[name] = value
            }
            DebugLogManager.log("[GECKO_PHASE_A_PREF_RESULT] key=$name success=$success")
            if (cont.isActive) {
              cont.resume(success)
            }
          },
          { error ->
            DebugLogManager.log(
              "[GECKO_PHASE_A_PREF_ERROR] key=$name error=${error?.message ?: "unknown"}"
            )
            if (cont.isActive) {
              cont.resume(false)
            }
          }
        )
      } catch (t: Throwable) {
        DebugLogManager.log(
          "[GECKO_PHASE_A_PREF_EXCEPTION] key=$name exception=${t.javaClass.name} message=${t.message}"
        )
        if (cont.isActive) {
          cont.resume(false)
        }
      }
    }
  }

  suspend fun applyPreferences(prefs: Map<String, Any>, branch: Int = PREF_BRANCH_USER): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    val startTime = android.os.SystemClock.elapsedRealtime()
    val totalCount = prefs.size

    if (prefs.isEmpty()) {
      val threadName = Thread.currentThread().name
      val isUiThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
      DebugLogManager.log("[FORENSIC] [GECKO_PREF_APPLY_TIMING] count=0 changedCount=0 skippedCount=0 elapsedMs=${android.os.SystemClock.elapsedRealtime() - startTime} thread=$threadName isUiThread=$isUiThread")
      cont.resume(true)
      return@suspendCancellableCoroutine
    }

    val changedPrefs = prefs.filter { (k, v) -> appliedPrefsCache[k] != v }
    val changedCount = changedPrefs.size
    val skippedCount = totalCount - changedCount

    if (changedPrefs.isEmpty()) {
      val threadName = Thread.currentThread().name
      val isUiThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
      DebugLogManager.log("[FORENSIC] [GECKO_PREF_APPLY_TIMING] count=$totalCount changedCount=0 skippedCount=$skippedCount elapsedMs=${android.os.SystemClock.elapsedRealtime() - startTime} thread=$threadName isUiThread=$isUiThread")
      cont.resume(true)
      return@suspendCancellableCoroutine
    }

    val setList = mutableListOf<SetGeckoPreference<*>>()
    for ((name, value) in changedPrefs) {
      when (value) {
        is String -> setList.add(SetGeckoPreference.setStringPref(name, value, branch))
        is Int -> setList.add(SetGeckoPreference.setIntPref(name, value, branch))
        is Boolean -> setList.add(SetGeckoPreference.setBoolPref(name, value, branch))
        else -> {
          val typeName = value::class.java.simpleName
          Log.e(TAG, "Unsupported preference type for key=$name: $typeName")
          DebugLogManager.log("[GECKO_PHASE_A] UNEXPECTED_TYPE key=$name type=$typeName")
          if (cont.isActive) cont.resume(false)
          return@suspendCancellableCoroutine
        }
      }
    }

    mainHandler.post {
      try {
        DebugLogManager.log("[GECKO_PHASE_A] INVOKING setGeckoPrefs count=${setList.size} branch=$branch")
        NativePrefCtrl.setGeckoPrefs(setList).accept(
          { result ->
            val resultTypeName = result?.javaClass?.name ?: "null"
            Log.d(TAG, "[GECKO_PREF_RAW_RESULT] type=$resultTypeName value=$result")
            DebugLogManager.log("[GECKO_PREF_RAW_RESULT] type=$resultTypeName value=$result")
            
            if (result !is Map<*, *>) {
              Log.e(TAG, "[ROUTE] GEOCKO_PREF_FAILURE unexpected_result_type=$resultTypeName")
              DebugLogManager.log("[ROUTE] GECKO_PREF_FAILURE unexpected_result_type=$resultTypeName")
              if (cont.isActive) cont.resume(false)
              return@accept
            }

            val resultMap = result.mapNotNull { (k, v) -> if (k is String && v is Boolean) k to v else null }.toMap()
            var total = 0
            var successful = 0
            var failed = 0
            var criticalFailed = 0
            val criticalFailedList = mutableListOf<String>()
            val failedList = mutableListOf<String>()

            for ((name, value) in changedPrefs) {
              val success = resultMap[name] == true
              if (success) {
                appliedPrefsCache[name] = value
              }
              total++
              if (success) successful++ else failed++
              
              Log.d(TAG, "[GECKO_PREF_RESULT] $name=$value result=$success")
              DebugLogManager.log("[GECKO_PHASE_A] $name=$value success=$success")
              
              if (!success) {
                failedList.add(name)
                if (REQUIRED_PROXY_ROUTING.contains(name) || REQUIRED_GHOST_PRIVACY.contains(name)) {
                  criticalFailed++
                  criticalFailedList.add(name)
                }
              }
            }
            
            Log.d(TAG, "[ROUTE] GECKO_PREF_SUMMARY total=$total successful=$successful failed=$failed criticalFailed=$criticalFailed")
            DebugLogManager.log("[ROUTE] GECKO_PREF_SUMMARY total=$total success=$successful failed=$failed criticalFailed=$criticalFailed")
            
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - startTime
            val threadName = Thread.currentThread().name
            val isUiThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            DebugLogManager.log("[FORENSIC] [GECKO_PREF_APPLY_TIMING] count=$totalCount changedCount=$changedCount skippedCount=$skippedCount elapsedMs=$elapsedMs thread=$threadName isUiThread=$isUiThread")

            if (criticalFailed > 0) {
              Log.e(TAG, "[ROUTE] GECKO_PREF_FAILURE error=CRITICAL Ghost preferences failed: $criticalFailedList")
              DebugLogManager.log("[ROUTE] GECKO_PREF_FAILURE critical_failed=$criticalFailedList")
              if (cont.isActive) cont.resume(false)
            } else {
              if (failed > 0) {
                Log.w(TAG, "Non-critical preferences failed: $failedList")
                DebugLogManager.log("[ROUTE] GECKO_PREF_NOTICE non_critical_failed=$failedList")
              }
              if (cont.isActive) cont.resume(true)
            }
          },
          { error ->
            val errorMsg = error?.message ?: "Unknown error"
            Log.e(TAG, "[ROUTE] GECKO_PREF_FAILURE error=$errorMsg", error)
            DebugLogManager.log("[ROUTE] GECKO_PREF_FAILURE error=$errorMsg")
            if (cont.isActive) cont.resume(false)
          }
        )
      } catch (t: Throwable) {
        val exMsg = t.message ?: t.javaClass.simpleName
        Log.e(TAG, "[ROUTE] GECKO_PREF_FAILURE exception=${t.javaClass.simpleName} message=$exMsg", t)
        DebugLogManager.log("[ROUTE] GECKO_PREF_FAILURE exception=${t.javaClass.simpleName} message=$exMsg")
        if (cont.isActive) cont.resume(false)
      }
    }
  }
}
