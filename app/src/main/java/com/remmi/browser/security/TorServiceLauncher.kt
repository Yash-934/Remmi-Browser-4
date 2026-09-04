package com.remmi.browser.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.torproject.jni.TorService

/**
 * TorServiceLauncher
 *
 * Provides lifecycle-safe, API-aware starting and stopping of the Tor foreground service.
 * Ensures notification channels are provisioned prior to invocation and tracks foreground
 * confirmation without sleep hacks.
 */
object TorServiceLauncher {

  private const val ACTION_TOR_START = "org.torproject.android.intent.action.START"
  private const val ACTION_TOR_STOP = "org.torproject.android.intent.action.STOP"

  fun prepareNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      val channel = NotificationChannel(
        RemmiTorService.CHANNEL_ID,
        "Remmi Ghost Tor Protection",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Encrypted onion routing for Ghost Mode browsing"
        setShowBadge(false)
      }
      notificationManager?.createNotificationChannel(channel)
    }
  }

  fun start(context: Context): Boolean {
    return try {
      prepareNotificationChannel(context)
      TorService.setBroadcastPackageName(context.packageName)

      val intent = Intent(context, RemmiTorService::class.java).apply {
        action = ACTION_TOR_START
        putExtra(RemmiTorService.EXTRA_NOTIFICATION_STATUS, "Starting Tor daemon...")
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(context, intent)
      } else {
        context.startService(intent)
      }
      DebugLogManager.log("TorServiceLauncher: Sent start intent to RemmiTorService")
      true
    } catch (e: Exception) {
      DebugLogManager.log("TorServiceLauncher: Error starting RemmiTorService: ${e.message}")
      false
    }
  }

  fun stop(context: Context) {
    try {
      val intent = Intent(context, RemmiTorService::class.java).apply {
        action = ACTION_TOR_STOP
      }
      context.stopService(intent)
      DebugLogManager.log("TorServiceLauncher: Stopped RemmiTorService")
    } catch (e: Exception) {
      DebugLogManager.log("TorServiceLauncher: Error stopping RemmiTorService: ${e.message}")
    }
  }

  fun isForegroundConfirmed(): Boolean {
    return RemmiTorService.isForegroundPromoted
  }

  suspend fun awaitForegroundConfirmed(timeoutMs: Long = 3000L): Boolean {
    val result = withTimeoutOrNull(timeoutMs) {
      while (!isForegroundConfirmed()) {
        delay(50)
      }
      true
    }
    return result ?: isForegroundConfirmed()
  }
}
