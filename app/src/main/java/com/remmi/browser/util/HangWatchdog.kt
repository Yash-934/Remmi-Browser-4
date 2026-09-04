package com.remmi.browser.util

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.remmi.browser.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Diagnostic-Only Hang/ANR Watchdogs & Memory Forensics for Remmi Browser.
 * Strict observation only — NEVER kills the app or restarts components.
 */
object HangWatchdog {
  const val TAG = "HangWatchdog"
  private val MAIN_THRESHOLDS_MS = longArrayOf(500L, 1000L, 2000L, 5000L, 10000L, 30000L, 60000L)
  private val COMPILE_THRESHOLDS_MS = longArrayOf(1000L, 5000L, 10000L, 30000L, 60000L)

  @Volatile var maxMainThreadBlockMs: Long = 0L
  @Volatile var maxCompileTimeMs: Long = 0L
  val maxActiveCompileJobs = AtomicInteger(0)

  private val watchdogExecutor = Executors.newScheduledThreadPool(2) { r ->
    Thread(r, "RemmiHangWatchdog").apply {
      isDaemon = true
      priority = Thread.MIN_PRIORITY
    }
  }

  private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
  private val mainWatchdogStarted = AtomicBoolean(false)

  /**
   * Starts a non-blocking background watchdog pinging the Main Looper.
   * Records delays crossing 500ms, 1s, 2s, 5s, 10s, 30s, 60s thresholds.
   */
  fun startMainThreadWatchdog() {
    if (!BuildConfig.DEBUG || !mainWatchdogStarted.compareAndSet(false, true)) return

    watchdogExecutor.scheduleWithFixedDelay({
      try {
        val pingStart = SystemClock.elapsedRealtime()
        val mainThread = Looper.getMainLooper().thread
        val latch = CountDownLatch(1)

        mainHandler.post {
          latch.countDown()
        }

        for (threshold in MAIN_THRESHOLDS_MS) {
          val now = SystemClock.elapsedRealtime()
          val remaining = threshold - (now - pingStart)
          if (remaining > 0) {
            val finished = latch.await(remaining, TimeUnit.MILLISECONDS)
            if (finished) break
          }
          if (latch.count > 0) {
            val elapsed = SystemClock.elapsedRealtime() - pingStart
            if (elapsed > maxMainThreadBlockMs) {
              maxMainThreadBlockMs = elapsed
            }
            val sess = CrashHandlerHelper.currentSessionId
            val pid = Process.myPid()
            val msg = "[MAIN_THREAD_WATCHDOG] elapsedMs=$elapsed threadName=${mainThread.name} threadId=${mainThread.id} sessionId=$sess processPid=$pid"
            Log.w(TAG, msg)
            DebugLogManager.log(msg)
          }
        }
        latch.await()
      } catch (_: Throwable) {}
    }, 500L, 500L, TimeUnit.MILLISECONDS)
  }

  /**
   * Starts a watchdog for Gecko initialization.
   */
  fun startGeckoInitWatchdog(): GeckoInitWatchdogHandle {
    val handle = GeckoInitWatchdogHandle(
      startTime = SystemClock.elapsedRealtime(),
      thread = Thread.currentThread(),
      sessionId = CrashHandlerHelper.currentSessionId,
      processPid = Process.myPid()
    )
    if (!BuildConfig.DEBUG) return handle

    val cancelled = AtomicBoolean(false)
    handle.cancelled = cancelled

    watchdogExecutor.execute {
      for (threshold in MAIN_THRESHOLDS_MS) {
        val now = SystemClock.elapsedRealtime()
        val sleepNeeded = threshold - (now - handle.startTime)
        if (sleepNeeded > 0) {
          try {
            Thread.sleep(sleepNeeded)
          } catch (_: InterruptedException) {
            return@execute
          }
        }
        if (cancelled.get()) return@execute
        val elapsed = SystemClock.elapsedRealtime() - handle.startTime
        if (elapsed > maxMainThreadBlockMs && handle.thread == Looper.getMainLooper().thread) {
          maxMainThreadBlockMs = elapsed
        }
        val msg = "[GECKO_INIT_WATCHDOG] elapsedMs=$elapsed threadName=${handle.thread.name} threadId=${handle.thread.id} sessionId=${handle.sessionId} processPid=${handle.processPid}"
        Log.w(TAG, msg)
        DebugLogManager.log(msg)
      }
    }
    return handle
  }

  class GeckoInitWatchdogHandle(
    val startTime: Long,
    val thread: Thread,
    val sessionId: String,
    val processPid: Int
  ) {
    var cancelled: AtomicBoolean? = null
    fun stop() {
      cancelled?.set(true)
    }
  }

  /**
   * Starts a compilation watchdog tracking elapsed compile time across thresholds.
   * At 60s, records a diagnostic snapshot without cancelling compilation.
   */
  fun startCompileWatchdog(
    jobId: String,
    inputBytes: Int,
    activeCompileJobs: Int
  ): CompileWatchdogHandle {
    val handle = CompileWatchdogHandle(
      jobId = jobId,
      inputBytes = inputBytes,
      activeCompileJobs = activeCompileJobs,
      startTime = SystemClock.elapsedRealtime(),
      thread = Thread.currentThread(),
      sessionId = CrashHandlerHelper.currentSessionId,
      processPid = Process.myPid()
    )

    val cancelled = AtomicBoolean(false)
    handle.cancelled = cancelled

    watchdogExecutor.execute {
      for (threshold in COMPILE_THRESHOLDS_MS) {
        val now = SystemClock.elapsedRealtime()
        val sleepNeeded = threshold - (now - handle.startTime)
        if (sleepNeeded > 0) {
          try {
            Thread.sleep(sleepNeeded)
          } catch (_: InterruptedException) {
            return@execute
          }
        }
        if (cancelled.get()) return@execute
        val elapsed = SystemClock.elapsedRealtime() - handle.startTime
        if (elapsed > maxCompileTimeMs) {
          maxCompileTimeMs = elapsed
        }
        val msg = "[COMPILE_WATCHDOG] jobId=${handle.jobId} elapsedMs=$elapsed thread=${handle.thread.name} threadId=${handle.thread.id} inputBytes=${handle.inputBytes} activeCompileJobs=${handle.activeCompileJobs} sessionId=${handle.sessionId} processPid=${handle.processPid}"
        Log.w(TAG, msg)
        DebugLogManager.log(msg)

        if (elapsed >= 60000L) {
          val snapMsg = "[COMPILE_WATCHDOG_SNAPSHOT_60S] jobId=${handle.jobId} elapsedMs=$elapsed thread=${handle.thread.name} inputBytes=${handle.inputBytes} ${getMemoryStats()} sessionId=${handle.sessionId} processPid=${handle.processPid}"
          Log.w(TAG, snapMsg)
          DebugLogManager.log(snapMsg)
        }
      }
    }
    return handle
  }

  class CompileWatchdogHandle(
    val jobId: String,
    val inputBytes: Int,
    val activeCompileJobs: Int,
    val startTime: Long,
    val thread: Thread,
    val sessionId: String,
    val processPid: Int
  ) {
    var cancelled: AtomicBoolean? = null
    fun stop(durationMs: Long) {
      cancelled?.set(true)
      if (durationMs > maxCompileTimeMs) {
        maxCompileTimeMs = durationMs
      }
    }
  }

  /**
   * Measures and reports UI-thread blocking operations.
   */
  inline fun <T> recordUiThreadBlockingOpIfNeeded(
    operationName: String,
    block: () -> T
  ): T {
    val isMainThread = (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper())
    if (!isMainThread) {
      return block()
    }
    val start = SystemClock.elapsedRealtime()
    try {
      return block()
    } finally {
      val elapsed = SystemClock.elapsedRealtime() - start
      if (elapsed > maxMainThreadBlockMs) {
        maxMainThreadBlockMs = elapsed
      }
      val sess = CrashHandlerHelper.currentSessionId
      val pid = Process.myPid()
      val msg = "[UI_THREAD_BLOCKING_OPERATION] op=$operationName elapsedMs=${elapsed}ms sessionId=$sess processPid=$pid"
      Log.w(TAG, msg)
      DebugLogManager.log(msg)
    }
  }

  fun getHeapUsedBytes(): Long {
    val rt = Runtime.getRuntime()
    return rt.totalMemory() - rt.freeMemory()
  }

  fun getHeapMaxBytes(): Long {
    return Runtime.getRuntime().maxMemory()
  }

  fun getAvailableMemBytes(context: Context?): Long? {
    if (context == null) return null
    return try {
      val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
      val memInfo = ActivityManager.MemoryInfo()
      am.getMemoryInfo(memInfo)
      memInfo.availMem
    } catch (_: Throwable) {
      null
    }
  }

  fun getMemoryStats(context: Context? = null): String {
    val usedMb = getHeapUsedBytes() / (1024 * 1024)
    val maxMb = getHeapMaxBytes() / (1024 * 1024)
    val availBytes = getAvailableMemBytes(context)
    val availStr = if (availBytes != null) " availMem=${availBytes / (1024 * 1024)}MB" else ""
    return "heapUsed=${usedMb}MB heapMax=${maxMb}MB$availStr"
  }
}
