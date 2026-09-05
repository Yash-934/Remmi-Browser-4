package com.remmi.browser

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.remmi.adblock.AdblockBridge
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.NetworkRouteAuthority
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.storage.SettingsRepository
import okhttp3.Call
import java.io.File
import java.util.concurrent.Executors

class RemmiApp : Application(), SingletonImageLoader.Factory {

  override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
    val callFactory = Call.Factory { request ->
      val targetUrl = request.url.toString()
      val isGhost = CurrentTorRoute.isGhostActive || NetworkRouteAuthority.isOnionDestination(targetUrl)
      try {
        val client = NetworkRouteAuthority.createHttpClient(
          isGhost = isGhost,
          targetUrl = targetUrl,
          connectTimeoutSeconds = 10L,
          readTimeoutSeconds = 15L
        )
        client.newCall(request)
      } catch (e: Exception) {
        throw java.io.IOException("Route authority rejected Coil fetch: ${e.message}", e)
      }
    }

    return ImageLoader.Builder(context)
      .components {
        add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = callFactory))
      }
      .crossfade(true)
      .build()
  }

  private fun isMainProcess(): Boolean {
    val processName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
      getProcessName()
    } else {
      try {
        File("/proc/self/cmdline").readText().trim().trim(0.toChar())
      } catch (_: Exception) {
        val pid = android.os.Process.myPid()
        val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
      }
    }
    return processName == null || processName == packageName
  }

  override fun onCreate() {
    super.onCreate()

    if (!isMainProcess()) {
      Log.i("RemmiApp", "Skipping application onCreate for child/isolated process: ${if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) getProcessName() else "child"}")
      return
    }

    // 1. Earliest process start & abnormal termination journal check
    com.remmi.browser.util.DebugLogManager.init(this)
    com.remmi.browser.util.CrashHandlerHelper.onProcessStart(this)
    com.remmi.browser.util.HangWatchdog.startMainThreadWatchdog()
    com.remmi.browser.util.ProcessMemoryTelemetry.init(this)
    com.remmi.browser.util.ProcessMemoryTelemetry.startSampling()

    // 2. Global Uncaught Exception Handler to capture crash logs & export to Downloads
    com.remmi.browser.util.CrashHandlerHelper.install(this)

    com.remmi.browser.storage.SqlCipherInitializer.ensureLoaded()
    com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(this, com.remmi.browser.util.StartupPhase.APPLICATION_CREATED)

    // Initialize local storage and settings in background to keep startup instant
    val executor = Executors.newSingleThreadExecutor { r ->
      Thread(r, "RemmiApp-Init").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
      }
    }
    executor.execute {
      try {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        Log.i("RemmiApp", "Background initialization started...")
        val bridge = AdblockBridge.getInstance()
        bridge.initEngine()
        RemmiDatabase.bootstrap(this)
        SettingsRepository.getInstance(this)
        val filterManager = com.remmi.adblock.FilterManager.getInstance(this, bridge)
        kotlinx.coroutines.runBlocking {
          try {
            filterManager.ensureFiltersReady()
          } catch (e: Throwable) {
            Log.w("RemmiApp", "[ADBLOCK] Background filter bootstrap error: ${e.message}")
          }
        }
        Log.i("RemmiApp", "Background initialization completed.")
      } catch (e: Throwable) {
        Log.e("RemmiApp", "Error during background init: ${e.message}", e)
      }
    }
  }
}

