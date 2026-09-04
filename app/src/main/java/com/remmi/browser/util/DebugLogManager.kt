package com.remmi.browser.util

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unified Thread-Safe Diagnostic Log Store & Persistent Breadcrumbs for Remmi Browser.
 * Non-blocking in-memory ring buffer with coalesced, debounced background I/O writer.
 * Strictly binds every event to sessionId, processPid, timestamp, and thread.
 */
object DebugLogManager {
  private const val TAG = "RemmiDebugLog"
  private const val MAX_LOGS = 300
  private const val MAX_BREADCRUMBS = 200
  private const val BREADCRUMBS_FILE = "remmi_breadcrumbs.log"
  private const val DEBOUNCE_DELAY_MS = 1000L

  private val _logs = MutableStateFlow<List<String>>(emptyList())
  val logs: StateFlow<List<String>> = _logs.asStateFlow()

  @Volatile
  private var appContext: Context? = null
  private val persistentBreadcrumbs = ConcurrentLinkedDeque<String>()
  private val previousSessionBreadcrumbs = ConcurrentLinkedDeque<String>()
  private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
  private val isDirty = AtomicBoolean(false)

  private val backgroundWriter = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "DebugLogWriter").apply {
      isDaemon = true
      priority = Thread.MIN_PRIORITY
    }
  }

  @Volatile
  private var pendingFlushTask: ScheduledFuture<*>? = null

  fun init(context: Context) {
    appContext = context.applicationContext
    backgroundWriter.execute {
      loadPersistedBreadcrumbs()
    }
  }

  fun log(message: String) {
    val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
    val sanitized = sanitize(message)
    val thread = Thread.currentThread()
    val sess = CrashHandlerHelper.currentSessionId
    val pid = Process.myPid()
    val entry = "[$timestamp][session=$sess][pid=$pid][thread=${thread.name}(${thread.id})] $sanitized"

    Log.d(TAG, sanitized)

    // 1. Update in-memory StateFlow for UI (newest first)
    synchronized(this) {
      val current = _logs.value.toMutableList()
      current.add(0, entry)
      if (current.size > MAX_LOGS) {
        current.removeAt(current.size - 1)
      }
      _logs.value = current
    }

    // 2. Update thread-safe in-memory ring buffer (chronological order)
    persistentBreadcrumbs.addLast(entry)
    while (persistentBreadcrumbs.size > MAX_BREADCRUMBS) {
      persistentBreadcrumbs.pollFirst()
    }

    // 3. Debounced, coalescing background write (Zero blocking on UI or worker threads)
    scheduleDebouncedFlush()
  }

  private fun scheduleDebouncedFlush() {
    isDirty.set(true)
    synchronized(isDirty) {
      if (pendingFlushTask == null || pendingFlushTask?.isDone == true) {
        pendingFlushTask = backgroundWriter.schedule({
          if (isDirty.compareAndSet(true, false)) {
            flushInternal()
          }
        }, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS)
      }
    }
  }

  fun sanitize(message: String): String {
    var sanitized = message
    // 1. Redact query parameters in standard URLs
    sanitized = sanitized.replace(Regex("""(https?://[^\s?#]+)\?[^\s]*""")) { mr ->
      "${mr.groupValues[1]}?[REDACTED_QUERY]"
    }
    // 2. Redact authorization headers, bearer tokens, passwords, cookies, secrets
    sanitized = sanitized.replace(
      Regex("""(?i)\b(authorization|bearer|token|password|passwd|secret|cookie|set-cookie|key|pin|passphrase)\s*[:=]\s*([^\s,;]+)""")
    ) { mr ->
      "${mr.groupValues[1]}: [REDACTED]"
    }
    // 3. Redact Basic auth blobs
    sanitized = sanitized.replace(Regex("""(?i)Basic\s+[A-Za-z0-9+/=]+"""), "Basic [REDACTED]")
    // 4. Redact Onion addresses query params if any
    sanitized = sanitized.replace(Regex("""([a-z2-7]{56}\.onion)/[^\s?#]*\?[^\s]*""")) { mr ->
      "${mr.groupValues[1]}/[REDACTED_PATH]?[REDACTED_QUERY]"
    }
    return sanitized
  }

  /**
   * Explicit synchronous flush strictly for fatal crash handler hand-off or test teardown.
   */
  fun flushSynchronously() {
    HangWatchdog.recordUiThreadBlockingOpIfNeeded("diagnostic flush") {
      synchronized(persistentBreadcrumbs) {
        val ctx = appContext ?: return@recordUiThreadBlockingOpIfNeeded
        try {
          val file = File(ctx.filesDir, BREADCRUMBS_FILE)
          val tempFile = File(ctx.filesDir, "$BREADCRUMBS_FILE.tmp")
          val content = persistentBreadcrumbs.joinToString("\n")
          tempFile.writeText(content)
          if (tempFile.exists()) {
            tempFile.renameTo(file)
          }
          isDirty.set(false)
        } catch (_: Throwable) {}
      }
    }
  }

  private fun flushInternal() {
    synchronized(persistentBreadcrumbs) {
      val ctx = appContext ?: return
      try {
        val file = File(ctx.filesDir, BREADCRUMBS_FILE)
        val tempFile = File(ctx.filesDir, "$BREADCRUMBS_FILE.tmp")
        val content = persistentBreadcrumbs.joinToString("\n")
        tempFile.writeText(content)
        if (tempFile.exists()) {
          tempFile.renameTo(file)
        }
      } catch (_: Throwable) {}
    }
  }

  private fun loadPersistedBreadcrumbs() {
    val ctx = appContext ?: return
    try {
      val file = File(ctx.filesDir, BREADCRUMBS_FILE)
      if (file.exists()) {
        val lines = file.readLines().takeLast(MAX_BREADCRUMBS)
        previousSessionBreadcrumbs.clear()
        previousSessionBreadcrumbs.addAll(lines)
      }
    } catch (_: Throwable) {}
  }

  fun getCurrentSessionEvents(limit: Int = MAX_BREADCRUMBS): List<String> {
    return persistentBreadcrumbs.toList().takeLast(limit)
  }

  fun getPreviousSessionEvents(limit: Int = MAX_BREADCRUMBS): List<String> {
    return previousSessionBreadcrumbs.toList().takeLast(limit)
  }

  fun getRecentEvents(limit: Int = MAX_BREADCRUMBS): List<String> {
    val list = persistentBreadcrumbs.toList()
    return if (list.isNotEmpty()) {
      list.takeLast(limit)
    } else {
      previousSessionBreadcrumbs.toList().takeLast(limit)
    }
  }

  fun getLastCurrentSessionEvent(): String {
    val current = persistentBreadcrumbs.peekLast()
    return current ?: "NONE"
  }

  fun getLastPreviousSessionEvent(): String {
    val prev = previousSessionBreadcrumbs.peekLast()
    return prev ?: "NONE"
  }

  fun clear() {
    synchronized(this) {
      _logs.value = emptyList()
      persistentBreadcrumbs.clear()
      previousSessionBreadcrumbs.clear()
      isDirty.set(false)
      backgroundWriter.execute {
        val ctx = appContext
        if (ctx != null) {
          try {
            File(ctx.filesDir, BREADCRUMBS_FILE).delete()
          } catch (_: Throwable) {}
        }
      }
    }
  }
}
