package com.remmi.browser.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.remmi.browser.R
import com.remmi.browser.util.DebugLogManager
import org.torproject.jni.TorService

/**
 * RemmiTorService — Android 16-Compatible Native Tor Foreground Service
 *
 * Promotes itself to Foreground Service immediately in [onCreate] and [onStartCommand]
 * to prevent [android.app.RemoteServiceException.ForegroundServiceDidNotStartInTimeException]
 * on Android 14+ / Android 16 (API 36).
 */
class RemmiTorService : TorService() {

  override fun onCreate() {
    createNotificationChannel()
    val initialNotification = buildNotification("Initializing Tor onion service...")
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
          0 // Fallback for older versions if specialUse is not available
        }
        ServiceCompat.startForeground(
          this,
          NOTIFICATION_ID,
          initialNotification,
          type
        )
      } else {
        startForeground(NOTIFICATION_ID, initialNotification)
      }
      isForegroundPromoted = true
      DebugLogManager.log("RemmiTorService: Promoted to Foreground Service successfully")
    } catch (e: Exception) {
      DebugLogManager.log("RemmiTorService: Foreground promotion error: ${e.message}")
    }
    super.onCreate()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val status = intent?.getStringExtra(EXTRA_NOTIFICATION_STATUS) ?: "Tor protection active"
    val notification = buildNotification(status)
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
          0
        }
        ServiceCompat.startForeground(
          this,
          NOTIFICATION_ID,
          notification,
          type
        )
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
      isForegroundPromoted = true
    } catch (e: Exception) {
      DebugLogManager.log("RemmiTorService onStartCommand: Foreground update error: ${e.message}")
    }
    return super.onStartCommand(intent, flags, startId)
  }

  override fun onDestroy() {
    DebugLogManager.log("RemmiTorService onDestroy: Stopping foreground service and Tor daemon")
    try {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } catch (_: Exception) {}
    isForegroundPromoted = false

    // Invalidate route immediately on Tor termination (P0-5)
    com.remmi.browser.security.CurrentTorRoute.clearRoute()
    com.remmi.browser.security.TorManager.getInstance(applicationContext).handleUnexpectedTermination()

    super.onDestroy()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      val existingChannel = notificationManager?.getNotificationChannel(CHANNEL_ID)
      if (existingChannel == null) {
        val channel = NotificationChannel(
          CHANNEL_ID,
          "Remmi Ghost Tor Protection",
          NotificationManager.IMPORTANCE_LOW
        ).apply {
          description = "Encrypted onion routing for Ghost Mode browsing"
          setShowBadge(false)
        }
        notificationManager?.createNotificationChannel(channel)
      }
    }
  }

  private fun buildNotification(statusText: String): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Remmi Browser • Ghost Protection")
      .setContentText(statusText)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .build()
  }

  companion object {
    const val CHANNEL_ID = "remmi_tor_fgs_channel"
    const val NOTIFICATION_ID = 9050
    const val EXTRA_NOTIFICATION_STATUS = "com.remmi.browser.extra.NOTIFICATION_STATUS"

    @Volatile
    var isForegroundPromoted: Boolean = false
      private set

    fun updateStatus(context: Context, statusText: String) {
      if (!isForegroundPromoted) return
      try {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
          .setSmallIcon(R.mipmap.ic_launcher)
          .setContentTitle("Remmi Browser • Ghost Protection")
          .setContentText(statusText)
          .setOngoing(true)
          .setPriority(NotificationCompat.PRIORITY_LOW)
          .setCategory(NotificationCompat.CATEGORY_SERVICE)
          .build()
        notificationManager?.notify(NOTIFICATION_ID, notification)
      } catch (_: Exception) {}
    }
  }
}
