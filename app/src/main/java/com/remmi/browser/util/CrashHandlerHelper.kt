package com.remmi.browser.util

import android.app.ActivityManager
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import com.remmi.adblock.AdblockBridge
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.storage.RemmiDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ReportType {
  JAVA_CRASH,
  ABNORMAL_TERMINATION
}

enum class ProcessExitClassification {
  USER_REQUESTED_TERMINATION,
  SYSTEM_PROCESS_KILL,
  OOM_KILL,
  ANR,
  NATIVE_FATAL_SIGNAL,
  JAVA_UNCAUGHT_EXCEPTION,
  UNKNOWN_PROCESS_EXIT
}

enum class StartupPhase(val id: String) {
  PROCESS_START("PROCESS_START"),
  SQLCIPHER_LOAD_START("SQLCIPHER_LOAD_START"),
  SQLCIPHER_LOAD_OK("SQLCIPHER_LOAD_OK"),
  SQLCIPHER_LOAD_FAILED("SQLCIPHER_LOAD_FAILED"),
  APPLICATION_CREATED("APPLICATION_CREATED"),
  ADBLOCK_CONSTRUCTION_START("ADBLOCK_CONSTRUCTION_START"),
  ADBLOCK_NATIVE_LOAD_OK("ADBLOCK_NATIVE_LOAD_OK"),
  ADBLOCK_NATIVE_INIT_OK("ADBLOCK_NATIVE_INIT_OK"),
  ADBLOCK_CONSTRUCTION_END("ADBLOCK_CONSTRUCTION_END"),
  DATABASE_BOOTSTRAP_START("DATABASE_BOOTSTRAP_START"),
  DATABASE_SQLCIPHER_OPEN_START("DATABASE_SQLCIPHER_OPEN_START"),
  DATABASE_SQLCIPHER_OPEN_OK("DATABASE_SQLCIPHER_OPEN_OK"),
  DATABASE_SQLCIPHER_OPEN_FAILED("DATABASE_SQLCIPHER_OPEN_FAILED"),
  MAIN_ACTIVITY_CREATE("MAIN_ACTIVITY_CREATE"),
  BROWSER_SCREEN_COMPOSE("BROWSER_SCREEN_COMPOSE"),
  GECKO_MANAGER_CONSTRUCT_START("GECKO_MANAGER_CONSTRUCT_START"),
  GECKO_MANAGER_CONSTRUCT_END("GECKO_MANAGER_CONSTRUCT_END"),
  FIRST_FRAME("FIRST_FRAME"),
  APP_READY("APP_READY"),
  SHUTDOWN("SHUTDOWN")
}

data class CrashExportResult(
  val fullReport: String,
  val savedPath: String,
  val timestamp: Long,
  val reportType: ReportType = ReportType.JAVA_CRASH
)

object CrashHandlerHelper {
  private const val TAG = "CrashHandlerHelper"
  const val PREFS_NAME = "remmi_crash_reports"

  const val KEY_PREVIOUS_RUN_CLEAN = "previous_run_clean"
  const val KEY_STARTUP_TIMESTAMP = "startup_timestamp"
  const val KEY_STARTUP_SESSION_ID = "startup_session_id"
  const val KEY_STARTUP_PROCESS_PID = "startup_process_pid"
  const val KEY_STARTUP_PHASE = "startup_phase"
  const val KEY_LAST_CLEAN_TIMESTAMP = "last_clean_timestamp"

  const val KEY_PREVIOUS_SESSION_ID = "previous_session_id"
  const val KEY_PREVIOUS_PROCESS_PID = "previous_process_pid"
  const val KEY_PREVIOUS_LAST_NATIVE_OP = "previous_last_native_op"

  const val KEY_PENDING_REPORT = "pending_report_content"
  const val KEY_PENDING_TIMESTAMP = "pending_report_timestamp"
  const val KEY_PENDING_TYPE = "pending_report_type"
  const val KEY_PENDING_SAVED_PATH = "pending_report_saved_path"
  const val KEY_PENDING_EXPORT_CONFIRMED = "pending_export_confirmed"

  const val KEY_LAST_NATIVE_OP = "last_native_op"
  const val KEY_NATIVE_API_VERSION = "saved_native_api_version"
  const val KEY_NATIVE_BUILD_ID = "saved_native_build_id"
  const val KEY_NATIVE_ABI = "saved_native_abi"

  @Volatile
  var currentSessionId: String = "unknown"
    private set

  @Volatile
  var currentProcessPid: Int = 0
    private set

  @Volatile
  var currentProcessStartTimestamp: Long = 0L
    private set

  @Volatile
  var previousSessionId: String = "NONE"
    private set

  @Volatile
  var previousProcessPid: Int = -1
    private set

  @Volatile
  var currentSessionLastNativeOp: String = "NONE"
    private set

  @Volatile
  var previousSessionLastNativeOp: String = "NONE"
    private set

  // Backwards compatibility getter for callers referencing lastNativeOperation
  val lastNativeOperation: String
    get() = if (currentSessionLastNativeOp != "NONE") currentSessionLastNativeOp else previousSessionLastNativeOp

  @Volatile
  var currentPhase: StartupPhase = StartupPhase.PROCESS_START
    private set

  @Volatile
  private var appContext: Context? = null

  /**
   * Called as the earliest step during process initialization (RemmiApp.onCreate).
   * Checks for abnormal termination of previous run, records session identity, and resets current session journal.
   */
  fun onProcessStart(context: Context) {
    try {
      appContext = context.applicationContext
      currentProcessPid = Process.myPid()
      currentProcessStartTimestamp = System.currentTimeMillis()

      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val wasClean = prefs.getBoolean(KEY_PREVIOUS_RUN_CLEAN, true)
      val prevSessionId = prefs.getString(KEY_STARTUP_SESSION_ID, null)
      val prevPid = prefs.getInt(KEY_STARTUP_PROCESS_PID, -1)
      val prevPhase = prefs.getString(KEY_STARTUP_PHASE, "UNKNOWN") ?: "UNKNOWN"
      val prevTimestamp = prefs.getLong(KEY_STARTUP_TIMESTAMP, 0L)
      val lastCleanTimestamp = prefs.getLong(KEY_LAST_CLEAN_TIMESTAMP, 0L)
      val prevNativeOp = prefs.getString(KEY_LAST_NATIVE_OP, "NONE") ?: "NONE"
      val hasPendingJavaCrash = prefs.contains(KEY_PENDING_REPORT)

      previousSessionId = prevSessionId ?: "NONE"
      previousProcessPid = prevPid
      previousSessionLastNativeOp = prevNativeOp

      // If previous run was NOT clean AND there was a session recorded AND no Java crash report was written,
      // this indicates an abnormal process termination (native crash / OOM / force kill / task removal).
      if (!wasClean && prevSessionId != null && !hasPendingJavaCrash) {
        val abnormalReport = buildDiagnosticReport(
          context = context,
          reportType = ReportType.ABNORMAL_TERMINATION,
          thread = null,
          throwable = null,
          sessionId = prevSessionId,
          lastPhase = prevPhase,
          wasClean = false,
          lastCleanTimestamp = lastCleanTimestamp,
          reportTime = System.currentTimeMillis()
        )

        val reportTimestamp = System.currentTimeMillis()
        prefs.edit()
          .putString(KEY_PENDING_REPORT, abnormalReport)
          .putLong(KEY_PENDING_TIMESTAMP, reportTimestamp)
          .putString(KEY_PENDING_TYPE, ReportType.ABNORMAL_TERMINATION.name)
          .putBoolean(KEY_PENDING_EXPORT_CONFIRMED, false)
          .apply()

        // Immediate persistence of abnormal termination report
        val exportPath = saveToDownloads(context, abnormalReport, reportTimestamp, ReportType.ABNORMAL_TERMINATION)
        if (exportPath != null) {
          prefs.edit()
            .putBoolean(KEY_PENDING_EXPORT_CONFIRMED, true)
            .putString(KEY_PENDING_SAVED_PATH, exportPath)
            .apply()
        }
      }

      // Initialize the new session heartbeat
      val newSessionId = UUID.randomUUID().toString()
      currentSessionId = newSessionId
      currentPhase = StartupPhase.PROCESS_START
      currentSessionLastNativeOp = "NONE"

      prefs.edit()
        .putBoolean(KEY_PREVIOUS_RUN_CLEAN, false)
        .putLong(KEY_STARTUP_TIMESTAMP, currentProcessStartTimestamp)
        .putString(KEY_STARTUP_SESSION_ID, newSessionId)
        .putInt(KEY_STARTUP_PROCESS_PID, currentProcessPid)
        .putString(KEY_STARTUP_PHASE, StartupPhase.PROCESS_START.id)
        .putString(KEY_LAST_NATIVE_OP, "NONE")
        .putString(KEY_PREVIOUS_SESSION_ID, previousSessionId)
        .putInt(KEY_PREVIOUS_PROCESS_PID, previousProcessPid)
        .putString(KEY_PREVIOUS_LAST_NATIVE_OP, previousSessionLastNativeOp)
        .apply()

      DebugLogManager.log("[APP_LIFECYCLE] PROCESS_START (session=$newSessionId, pid=$currentProcessPid)")
    } catch (e: Throwable) {
      Log.e(TAG, "Error in onProcessStart: ${e.message}", e)
    }
  }

  /**
   * Updates startup phase checkpoint. In-memory immediately, persisted asynchronously.
   * When APP_READY is reached, marks the run clean.
   */
  fun updateStartupPhase(context: Context? = null, phase: StartupPhase) {
    try {
      currentPhase = phase
      val ctx = context?.applicationContext ?: appContext
      if (ctx != null) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString(KEY_STARTUP_PHASE, phase.id)

        if (phase == StartupPhase.APP_READY) {
          editor.putBoolean(KEY_PREVIOUS_RUN_CLEAN, true)
          editor.putLong(KEY_LAST_CLEAN_TIMESTAMP, System.currentTimeMillis())
        }
        editor.apply()
      }

      DebugLogManager.log("[APP_LIFECYCLE] ${phase.id}")
    } catch (e: Throwable) {
      Log.e(TAG, "Error updating startup phase: ${e.message}", e)
    }
  }

  /**
   * Records a native operation marker into persistent preferences and the debug journal,
   * strictly bound to the current session and PID.
   */
  fun recordNativeOp(
    context: Context? = null,
    op: String,
    apiVersion: String? = null,
    buildId: String? = null,
    abi: String? = null
  ) {
    try {
      currentSessionLastNativeOp = op
      val ctx = context?.applicationContext ?: appContext
      if (ctx != null) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString(KEY_LAST_NATIVE_OP, op)
        if (apiVersion != null) editor.putString(KEY_NATIVE_API_VERSION, apiVersion)
        if (buildId != null) editor.putString(KEY_NATIVE_BUILD_ID, buildId)
        if (abi != null) editor.putString(KEY_NATIVE_ABI, abi)
        editor.apply()
      }

      DebugLogManager.log("[NATIVE_OP] $op")
    } catch (e: Throwable) {
      Log.e(TAG, "Error recording native op: ${e.message}", e)
    }
  }

  /**
   * Marks clean shutdown when the activity/app finishes gracefully.
   */
  fun markCleanShutdown(context: Context) {
    try {
      currentPhase = StartupPhase.SHUTDOWN
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      prefs.edit()
        .putBoolean(KEY_PREVIOUS_RUN_CLEAN, true)
        .putString(KEY_STARTUP_PHASE, StartupPhase.SHUTDOWN.id)
        .putLong(KEY_LAST_CLEAN_TIMESTAMP, System.currentTimeMillis())
        .apply()

      DebugLogManager.log("[APP_LIFECYCLE] SHUTDOWN")
    } catch (e: Throwable) {
      Log.e(TAG, "Error marking clean shutdown: ${e.message}", e)
    }
  }

  /**
   * Layer 1: Install standard UncaughtExceptionHandler for Java/Kotlin exceptions.
   */
  fun install(app: Application) {
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      try {
        val now = System.currentTimeMillis()
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastClean = prefs.getLong(KEY_LAST_CLEAN_TIMESTAMP, 0L)

        val report = buildDiagnosticReport(
          context = app,
          reportType = ReportType.JAVA_CRASH,
          thread = thread,
          throwable = throwable,
          sessionId = currentSessionId,
          lastPhase = currentPhase.id,
          wasClean = false,
          lastCleanTimestamp = lastClean,
          reportTime = now
        )

        Log.e(TAG, "FATAL UNCAUGHT EXCEPTION:\n$report")
        DebugLogManager.log("[APP_LIFECYCLE] FATAL_EXCEPTION: ${throwable.javaClass.name} - ${throwable.message}")
        DebugLogManager.flushSynchronously()

        // 1. Save pending report to SharedPreferences synchronously
        HangWatchdog.recordUiThreadBlockingOpIfNeeded("synchronous SharedPreferences.commit") {
          prefs.edit()
            .putString(KEY_PENDING_REPORT, report)
            .putLong(KEY_PENDING_TIMESTAMP, now)
            .putString(KEY_PENDING_TYPE, ReportType.JAVA_CRASH.name)
            .putBoolean(KEY_PREVIOUS_RUN_CLEAN, false)
            .putBoolean(KEY_PENDING_EXPORT_CONFIRMED, false)
            .commit()
        }

        // 2. Save to app-private internal storage
        try {
          val internalFile = File(app.filesDir, "crash_latest.txt")
          internalFile.writeText(report)
        } catch (_: Throwable) {}

        // 3. Attempt immediate synchronous export to Downloads/Remmi Crash Reports/
        val exportPath = saveToDownloads(app, report, now, ReportType.JAVA_CRASH)
        if (exportPath != null) {
          HangWatchdog.recordUiThreadBlockingOpIfNeeded("synchronous SharedPreferences.commit") {
            prefs.edit()
              .putBoolean(KEY_PENDING_EXPORT_CONFIRMED, true)
              .putString(KEY_PENDING_SAVED_PATH, exportPath)
              .commit()
          }
        }
      } catch (e: Throwable) {
        Log.e(TAG, "Failed to capture crash report: ${e.message}", e)
      } finally {
        defaultHandler?.uncaughtException(thread, throwable)
      }
    }
  }

  suspend fun checkAndExportPendingCrashAsync(context: Context): CrashExportResult? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      checkAndExportPendingReport(context)
    }

  fun checkAndExportPendingCrash(context: Context): CrashExportResult? {
    return checkAndExportPendingReport(context)
  }

  /**
   * Checks for pending diagnostic/crash reports, ensures export to Downloads, and removes
   * the pending marker only upon confirmed persistence.
   */
  fun checkAndExportPendingReport(context: Context): CrashExportResult? {
    try {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val report = prefs.getString(KEY_PENDING_REPORT, null) ?: return null
      val timestamp = prefs.getLong(KEY_PENDING_TIMESTAMP, System.currentTimeMillis())
      val typeStr = prefs.getString(KEY_PENDING_TYPE, ReportType.JAVA_CRASH.name) ?: ReportType.JAVA_CRASH.name
      val reportType = if (typeStr == ReportType.ABNORMAL_TERMINATION.name) {
        ReportType.ABNORMAL_TERMINATION
      } else {
        ReportType.JAVA_CRASH
      }

      val alreadyConfirmed = prefs.getBoolean(KEY_PENDING_EXPORT_CONFIRMED, false)
      var savedPath = prefs.getString(KEY_PENDING_SAVED_PATH, null)

      if (!alreadyConfirmed || savedPath == null) {
        savedPath = saveToDownloads(context, report, timestamp, reportType)
        if (savedPath != null) {
          prefs.edit()
            .putBoolean(KEY_PENDING_EXPORT_CONFIRMED, true)
            .putString(KEY_PENDING_SAVED_PATH, savedPath)
            .apply()
        }
      }

      // Only clear pending report marker once export/persistence is confirmed
      val isConfirmed = (savedPath != null || alreadyConfirmed)
      if (isConfirmed) {
        prefs.edit()
          .remove(KEY_PENDING_REPORT)
          .remove(KEY_PENDING_TIMESTAMP)
          .remove(KEY_PENDING_TYPE)
          .remove(KEY_PENDING_SAVED_PATH)
          .remove(KEY_PENDING_EXPORT_CONFIRMED)
          .apply()
      }

      return CrashExportResult(
        fullReport = report,
        savedPath = savedPath ?: "Downloads/Remmi Crash Reports/",
        timestamp = timestamp,
        reportType = reportType
      )
    } catch (e: Throwable) {
      Log.e(TAG, "Error checking pending crash: ${e.message}", e)
      return null
    }
  }

  /**
   * Safely exports report text to Downloads/Remmi Crash Reports/ using MediaStore on Q+
   * and direct filesystem on older versions, with automatic fallback to app-private storage.
   */
  fun saveToDownloads(
    context: Context,
    report: String,
    timestamp: Long,
    reportType: ReportType = ReportType.JAVA_CRASH
  ): String? {
    return HangWatchdog.recordUiThreadBlockingOpIfNeeded("synchronous file write") {
      val dateStr = SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.US).format(Date(timestamp))
      val fileName = if (reportType == ReportType.ABNORMAL_TERMINATION) {
        "abnormal_termination_$dateStr.txt"
      } else {
        "crash_$dateStr.txt"
      }
      val latestFileName = if (reportType == ReportType.ABNORMAL_TERMINATION) {
        "abnormal_latest.txt"
      } else {
        "crash_latest.txt"
      }

      var resultPath: String? = null

      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val resolver = context.contentResolver

          // 1. Write timestamped file with IS_PENDING safety
          val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Remmi Crash Reports")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
          }
          val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
          if (uri != null) {
            try {
              resolver.openOutputStream(uri)?.use { os ->
                os.write(report.toByteArray(Charsets.UTF_8))
                os.flush()
              }
              contentValues.clear()
              contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
              resolver.update(uri, contentValues, null, null)
              resultPath = "Downloads/Remmi Crash Reports/$fileName"
            } catch (writeEx: Throwable) {
              try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
              throw writeEx
            }
          }

          // 2. Also write/update latest file
          try {
            val latestValues = ContentValues().apply {
              put(MediaStore.MediaColumns.DISPLAY_NAME, latestFileName)
              put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
              put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Remmi Crash Reports")
              put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val latestUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, latestValues)
            if (latestUri != null) {
              resolver.openOutputStream(latestUri)?.use { os ->
                os.write(report.toByteArray(Charsets.UTF_8))
                os.flush()
              }
              latestValues.clear()
              latestValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
              resolver.update(latestUri, latestValues, null, null)
            }
          } catch (_: Throwable) {}

        } else {
          val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
          val crashDir = File(downloadsDir, "Remmi Crash Reports")
          if (!crashDir.exists()) {
            crashDir.mkdirs()
          }
          val targetFile = File(crashDir, fileName)
          targetFile.writeText(report)

          val latestFile = File(crashDir, latestFileName)
          latestFile.writeText(report)

          resultPath = targetFile.absolutePath
        }
      } catch (e: Throwable) {
        Log.e(TAG, "Error saving report to public Downloads: ${e.message}", e)
        // Fallback to app-private external / internal storage
        try {
          val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
          val fallbackDir = File(extDir, "Remmi Crash Reports").apply { mkdirs() }
          val fallbackFile = File(fallbackDir, fileName)
          fallbackFile.writeText(report)
          File(fallbackDir, latestFileName).writeText(report)
          resultPath = fallbackFile.absolutePath
        } catch (_: Throwable) {
          try {
            val internalDir = File(context.filesDir, "Remmi Crash Reports").apply { mkdirs() }
            val internalFile = File(internalDir, fileName)
            internalFile.writeText(report)
            File(internalDir, latestFileName).writeText(report)
            resultPath = internalFile.absolutePath
          } catch (_: Throwable) {}
        }
      }

      resultPath
    }
  }

  data class NativeExitInfo(
    val classification: ProcessExitClassification,
    val signalName: String,
    val signalCode: Int,
    val description: String,
    val traceBacktrace: String?,
    val historicalPid: Int,
    val callingPid: String,
    val isOom: Boolean,
    val isAnr: Boolean
  )

  fun resetNativeOpState() {
    currentSessionLastNativeOp = "NONE"
    previousSessionLastNativeOp = "NONE"
  }

  fun getLastNativeOpString(context: Context? = null): String {
    return lastNativeOperation
  }

  fun classifyExit(reason: Int, status: Int, description: String = ""): ProcessExitClassification {
    val descLower = description.lowercase()
    val isUserRequested = (
      reason == 10 || // REASON_USER_REQUESTED
      reason == 11 || // REASON_USER_STOPPED
      reason == 1 ||  // REASON_EXIT_SELF
      descLower.contains("remove task") ||
      descLower.contains("user_requested") ||
      descLower.contains("user requested")
    )

    val isOom = (
      reason == 3 || // REASON_LOW_MEMORY
      descLower.contains("lowmemorykiller") ||
      descLower.contains("lmkd") ||
      descLower.contains("oom") ||
      descLower.contains("out of memory") ||
      descLower.contains("memory pressure")
    )

    val isAnr = (
      reason == 6 || // REASON_ANR
      descLower.contains("anr")
    )

    val isNativeFatal = (
      reason == 5 || // REASON_CRASH_NATIVE
      (reason == 2 && status in listOf(4, 6, 7, 8, 11)) // REASON_SIGNALED
    )

    return when {
      isUserRequested -> ProcessExitClassification.USER_REQUESTED_TERMINATION
      isOom -> ProcessExitClassification.OOM_KILL
      isAnr -> ProcessExitClassification.ANR
      isNativeFatal -> ProcessExitClassification.NATIVE_FATAL_SIGNAL
      status in listOf(9, 15) -> ProcessExitClassification.SYSTEM_PROCESS_KILL
      reason == 4 -> ProcessExitClassification.JAVA_UNCAUGHT_EXCEPTION // REASON_CRASH
      else -> ProcessExitClassification.UNKNOWN_PROCESS_EXIT
    }
  }

  fun classifySignal(status: Int, reason: Int = 0, description: String = ""): String {
    val descLower = description.lowercase()
    val isUserRequested = (
      reason == 10 || reason == 11 || reason == 1 ||
      descLower.contains("remove task") || descLower.contains("user_requested") || descLower.contains("user requested")
    )
    val isOom = (
      reason == 3 || descLower.contains("lowmemorykiller") || descLower.contains("lmkd") || descLower.contains("oom")
    )
    val isAnr = (
      reason == 6 || descLower.contains("anr")
    )

    return when (status) {
      11 -> "SIGSEGV (Segmentation Fault)"
      6 -> "SIGABRT (Abort)"
      7 -> "SIGBUS (Bus Error)"
      4 -> "SIGILL (Illegal Instruction)"
      8 -> "SIGFPE (Floating Point Exception)"
      9 -> "SIGKILL (Killed by OS)"
      15 -> "SIGTERM (Termination Request)"
      0 -> if (isUserRequested) "STATUS_0" else "STATUS_0"
      else -> when {
        isOom -> "OOM (Low Memory Killer)"
        isAnr -> "ANR (Application Not Responding)"
        else -> "STATUS_$status"
      }
    }
  }

  fun queryHistoricalProcessExit(context: Context): NativeExitInfo? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val exitList = am.getHistoricalProcessExitReasons(context.packageName, 0, 1)
        val latest = exitList.firstOrNull() ?: return null

        val desc = latest.description ?: ""
        val classification = classifyExit(latest.reason, latest.status, desc)
        val signalName = classifySignal(latest.status, latest.reason, desc)

        val isOom = classification == ProcessExitClassification.OOM_KILL
        val isAnr = classification == ProcessExitClassification.ANR

        val callingPidMatch = Regex("""callingPid=(\d+)""").find(desc)
        val extractedCallingPid = callingPidMatch?.groupValues?.get(1) ?: "PID ROLE = UNKNOWN"

        var traceText: String? = null
        try {
          latest.traceInputStream?.use { stream ->
            traceText = stream.bufferedReader().use { it.readText() }
          }
        } catch (_: Throwable) {}

        val formattedDesc = if (desc.isNotBlank()) desc else "Reason: ${latest.reason}, Status: ${latest.status}"

        return NativeExitInfo(
          classification = classification,
          signalName = signalName,
          signalCode = latest.status,
          description = formattedDesc,
          traceBacktrace = traceText?.takeIf { it.isNotBlank() },
          historicalPid = latest.pid,
          callingPid = extractedCallingPid,
          isOom = isOom,
          isAnr = isAnr
        )
      } catch (t: Throwable) {
        Log.w(TAG, "Could not query ApplicationExitInfo: ${t.message}")
      }
    }
    return null
  }

  fun buildDiagnosticReport(
    context: Context,
    reportType: ReportType,
    thread: Thread?,
    throwable: Throwable?,
    sessionId: String,
    lastPhase: String,
    wasClean: Boolean,
    lastCleanTimestamp: Long,
    reportTime: Long
  ): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.US)
    val now = dateFormat.format(Date(reportTime))
    val lastCleanTimeStr = if (lastCleanTimestamp > 0L) {
      dateFormat.format(Date(lastCleanTimestamp))
    } else {
      "None (First run or previous unclean termination)"
    }

    val packageInfo = try {
      context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (_: Throwable) { null }

    val versionName = packageInfo?.versionName ?: "1.0.0"
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageInfo?.longVersionCode ?: 1L
    } else {
      @Suppress("DEPRECATION")
      packageInfo?.versionCode?.toLong() ?: 1L
    }

    val prefs = try { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) } catch (_: Throwable) { null }
    val savedPrevSessionId = prefs?.getString(KEY_PREVIOUS_SESSION_ID, null) ?: previousSessionId
    val savedPrevProcessPid = prefs?.getInt(KEY_PREVIOUS_PROCESS_PID, -1) ?: previousProcessPid
    val savedPrevNativeOp = prefs?.getString(KEY_PREVIOUS_LAST_NATIVE_OP, null) ?: previousSessionLastNativeOp

    val adblock = try { AdblockBridge.getInstance() } catch (_: Throwable) { null }
    val adblockVersion = adblock?.nativeApiVersion?.takeIf { it != "unknown" }
      ?: (prefs?.getString(KEY_NATIVE_API_VERSION, null) ?: "unknown")
    val adblockBuildId = adblock?.nativeBuildId?.takeIf { it != "unknown" }
      ?: (prefs?.getString(KEY_NATIVE_BUILD_ID, null) ?: "unknown")
    val adblockAbi = adblock?.nativeAbi?.takeIf { it != "unknown" }
      ?: (prefs?.getString(KEY_NATIVE_ABI, null) ?: "unknown")
    val adblockApiVersionNumeric = adblock?.nativeNumericApiVersion?.toString() ?: "0"
    val nativeCompatState = if (adblock?.isJniSignatureCompatible == true) "COMPATIBLE" else "GATED_FALLBACK"
    val adblockState = adblock?.state?.name ?: "UNKNOWN"

    val sqlcipherLoadState = if (com.remmi.browser.storage.SqlCipherInitializer.isLoaded()) "LOADED" else "NOT_LOADED"

    val dbState = try {
      when (val s = RemmiDatabase.databaseState.value) {
        is RemmiDatabase.DatabaseState.Ready -> "READY"
        is RemmiDatabase.DatabaseState.Error -> "ERROR: ${s.throwable.javaClass.simpleName}"
        RemmiDatabase.DatabaseState.Loading -> "LOADING"
      }
    } catch (_: Throwable) { "UNKNOWN" }

    val geckoState = try {
      GeckoEngineManager.peekInitState() ?: "NOT_INITIALIZED"
    } catch (_: Throwable) { "UNKNOWN" }

    val torState = try {
      if (CurrentTorRoute.currentSocksPort != null) "ACTIVE" else "INACTIVE"
    } catch (_: Throwable) { "UNKNOWN" }

    val ghostState = try {
      if (CurrentTorRoute.isGhostActive) "ENABLED" else "DISABLED"
    } catch (_: Throwable) { "UNKNOWN" }

    val shieldState = "ENABLED"
    val webExtState = "REGISTERED"

    val currentBreadcrumbs = try {
      DebugLogManager.getCurrentSessionEvents(100)
    } catch (_: Throwable) {
      emptyList()
    }
    val previousBreadcrumbs = try {
      DebugLogManager.getPreviousSessionEvents(100)
    } catch (_: Throwable) {
      emptyList()
    }
    
    val currentBreadcrumbText = if (currentBreadcrumbs.isNotEmpty()) {
      currentBreadcrumbs.joinToString("\n")
    } else {
      "No diagnostic events recorded for current session"
    }
    
    val previousBreadcrumbText = if (previousBreadcrumbs.isNotEmpty()) {
      previousBreadcrumbs.joinToString("\n")
    } else {
      "No diagnostic events recorded for previous session"
    }

    val currentSessionLastEvent = DebugLogManager.getLastCurrentSessionEvent()
    val previousSessionLastEvent = DebugLogManager.getLastPreviousSessionEvent()

    val stackTrace = if (throwable != null) {
      Log.getStackTraceString(throwable)
    } else {
      ""
    }

    val nativeExitInfo = if (throwable == null) queryHistoricalProcessExit(context) else null
    val classification = when {
      throwable != null -> ProcessExitClassification.JAVA_UNCAUGHT_EXCEPTION
      nativeExitInfo != null -> nativeExitInfo.classification
      else -> ProcessExitClassification.UNKNOWN_PROCESS_EXIT
    }

    val detectedSignal = when {
      throwable != null -> "N/A (Java Exception)"
      nativeExitInfo != null -> nativeExitInfo.signalName
      else -> "UNKNOWN"
    }

    val exitDetails = nativeExitInfo?.description ?: "No historical exit info recorded"
    val historicalPidStr = if (nativeExitInfo != null && nativeExitInfo.historicalPid > 0) nativeExitInfo.historicalPid.toString() else "UNKNOWN"
    val callingPidStr = nativeExitInfo?.callingPid ?: "PID ROLE = UNKNOWN"
    val backtraceText = if (nativeExitInfo?.traceBacktrace != null) {
      "Tombstone / Native Backtrace:\n${nativeExitInfo.traceBacktrace}"
    } else {
      "Tombstone / Native Backtrace:\nUNAVAILABLE"
    }

    val currentPid = Process.myPid()
    val currentNativeOpDisplay = currentSessionLastNativeOp
    val previousNativeOpDisplay = savedPrevNativeOp

    return """
======================================================================
REMMI BROWSER - AUTOMATIC DIAGNOSTIC REPORT
======================================================================

Report Type:
${reportType.name}

PROCESS TERMINATION:
Classification: ${classification.name}
Signal: $detectedSignal
Exit Reason: ${if (throwable != null) "Java Uncaught Exception: ${throwable.javaClass.name}" else exitDetails}
Current Process PID: $currentPid
Historical Terminated PID: $historicalPidStr
Calling PID: $callingPidStr

CURRENT SESSION:
Session ID: $currentSessionId
Process PID: $currentPid
Last Diagnostic Event: $currentSessionLastEvent
Last Native Operation: $currentNativeOpDisplay

PREVIOUS SESSION:
Session ID: $savedPrevSessionId
Process PID: ${if (savedPrevProcessPid > 0) savedPrevProcessPid.toString() else "NONE"}
Last Diagnostic Event: $previousSessionLastEvent
Last Native Operation: $previousNativeOpDisplay

DEVICE:
Brand: ${Build.BRAND}
Manufacturer: ${Build.MANUFACTURER}
Model: ${Build.MODEL}
Device: ${Build.DEVICE}
Android Version: ${Build.VERSION.RELEASE}
SDK: ${Build.VERSION.SDK_INT}
Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}

PROCESS:
Process ID: $currentPid
Exit Classification: ${classification.name}
Thread if available: ${thread?.let { "${it.name} (ID: ${it.id})" } ?: "N/A (Process-level termination)"}

STARTUP STATE:
Previous run clean: $wasClean
Previous startup phase: $lastPhase
Last startup phase: $lastPhase
Current startup phase: ${currentPhase.id}
Startup session ID: $sessionId
Last clean timestamp: $lastCleanTimeStr

NATIVE:
Native ABI: $adblockAbi (Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")})
Adblock native version: $adblockVersion
Adblock native build ID: $adblockBuildId
Adblock API version: $adblockApiVersionNumeric
Native compatibility state: $nativeCompatState
Current session last native operation: $currentNativeOpDisplay
Previous session last native operation: $previousNativeOpDisplay
Last native operation: ${if (currentNativeOpDisplay != "NONE") currentNativeOpDisplay else if (previousNativeOpDisplay != "NONE") "PREVIOUS_SESSION: $previousNativeOpDisplay" else "NONE"}
SQLCipher load state: $sqlcipherLoadState

SUBSYSTEM STATE:
Adblock: $adblockState
Gecko: $geckoState
Database: $dbState
SQLCipher load state: $sqlcipherLoadState
Tor: $torState
Ghost: $ghostState
Shield: $shieldState
WebExtension: $webExtState

PREVIOUS SESSION DIAGNOSTIC EVENTS (LAST 100):
$previousBreadcrumbText

CURRENT SESSION DIAGNOSTIC EVENTS (LAST 100):
$currentBreadcrumbText

JAVA EXCEPTION:
${if (throwable != null) """
Exception Class: ${throwable.javaClass.name}
Message: ${throwable.message ?: "No error message provided"}
Stacktrace:
$stackTrace
""".trimIndent() else "N/A"}

ABNORMAL TERMINATION FORENSICS:
${if (throwable == null) """
Classification: ${classification.name}
Signal: $detectedSignal
Exit Details: $exitDetails
Current session last native operation: $currentNativeOpDisplay
Previous session last native operation: $previousNativeOpDisplay
Last native operation: ${if (currentNativeOpDisplay != "NONE") currentNativeOpDisplay else if (previousNativeOpDisplay != "NONE") "PREVIOUS_SESSION: $previousNativeOpDisplay" else "NONE"}
NO JAVA EXCEPTION CAPTURED.
$backtraceText
""".trimIndent() else "N/A (Captured by Java UncaughtExceptionHandler)"}

END REPORT
======================================================================
    """.trimIndent()
  }
}
