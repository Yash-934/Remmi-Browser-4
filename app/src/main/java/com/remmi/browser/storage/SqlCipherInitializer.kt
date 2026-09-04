package com.remmi.browser.storage

import android.content.Context
import android.util.Log
import com.remmi.browser.util.CrashHandlerHelper
import com.remmi.browser.util.DebugLogManager
import com.remmi.browser.util.StartupPhase

object SqlCipherInitializer {

    @Volatile
    private var loaded = false

    @Volatile
    private var loadFailed = false

    fun isLoaded(): Boolean = loaded

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadFailed) return false

        synchronized(this) {
            if (loaded) return true
            if (loadFailed) return false

            CrashHandlerHelper.updateStartupPhase(phase = StartupPhase.SQLCIPHER_LOAD_START)
            DebugLogManager.log("[SQLCIPHER_LOAD_START]")

            return try {
                System.loadLibrary("sqlcipher")
                loaded = true
                loadFailed = false
                CrashHandlerHelper.updateStartupPhase(phase = StartupPhase.SQLCIPHER_LOAD_OK)
                DebugLogManager.log("[SQLCIPHER_LOAD_OK]")
                true
            } catch (e: Throwable) {
                loadFailed = true
                loaded = false
                CrashHandlerHelper.updateStartupPhase(phase = StartupPhase.SQLCIPHER_LOAD_FAILED)
                DebugLogManager.log("[SQLCIPHER_LOAD_FAILED] ${e.message}")
                Log.e("SqlCipherInitializer", "Native sqlcipher library failed to load: ${e.message}", e)
                false
            }
        }
    }

    fun resetForTesting(forceLoaded: Boolean? = null, forceFailed: Boolean? = null) {
        synchronized(this) {
            loaded = forceLoaded ?: false
            loadFailed = forceFailed ?: false
        }
    }

    fun simulateLoadFailureForTesting(context: Context? = null) {
        synchronized(this) {
            loaded = false
            loadFailed = true
            CrashHandlerHelper.updateStartupPhase(context, StartupPhase.SQLCIPHER_LOAD_FAILED)
            DebugLogManager.log("[SQLCIPHER_LOAD_FAILED] Simulated test failure")
        }
    }
}
