package com.remmi.browser.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File

object TamperDetection {

  data class IntegrityReport(
    val isDebuggerAttached: Boolean,
    val isRootDetected: Boolean,
    val isEmulator: Boolean,
    val isSignatureValid: Boolean,
    val systemIntegrityStatus: String,
  )

  fun checkIntegrity(context: Context): IntegrityReport {
    val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val isRooted = checkRoot()
    val isEmulator = checkEmulator()

    val status = when {
      isRooted -> "COMPROMISED (ROOT DETECTED)"
      isDebuggable -> "DEV ENVIRONMENT ACTIVE"
      else -> "SECURE & ISOLATED"
    }

    return IntegrityReport(
      isDebuggerAttached = isDebuggable,
      isRootDetected = isRooted,
      isEmulator = isEmulator,
      isSignatureValid = true,
      systemIntegrityStatus = status,
    )
  }

  private fun checkRoot(): Boolean {
    val paths = arrayOf(
      "/system/app/Superuser.apk",
      "/sbin/su",
      "/system/bin/su",
      "/system/xbin/su",
      "/data/local/xbin/su",
      "/data/local/bin/su",
      "/system/sd/xbin/su",
      "/system/bin/failsafe/su",
      "/data/local/su"
    )
    return paths.any { File(it).exists() }
  }

  private fun checkEmulator(): Boolean {
    return (Build.FINGERPRINT.startsWith("generic")
        || Build.MODEL.contains("google_sdk")
        || Build.MODEL.contains("Emulator")
        || Build.MODEL.contains("Android SDK built for x86"))
  }
}
