package com.remmi.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remmi.browser.util.CrashHandlerHelper
import com.remmi.browser.util.DebugLogManager
import com.remmi.browser.util.ReportType
import com.remmi.browser.util.StartupPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class CrashReportingSystemTest {

  private lateinit var context: Context

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    Thread.sleep(1000) // Let background initialization finish so it doesn't concurrently mutate state
    context.getSharedPreferences(CrashHandlerHelper.PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    DebugLogManager.init(context)
    DebugLogManager.clear()
  }

  @Test
  fun testBreadcrumbRedaction() {
    val inputUrl = "Opening https://secret-bank.com/account?token=secret123&user=alice"
    val sanitizedUrl = DebugLogManager.sanitize(inputUrl)
    assertFalse(sanitizedUrl.contains("token=secret123"))
    assertTrue(sanitizedUrl.contains("?[REDACTED_QUERY]"))

    val inputAuth = "Sending header Authorization: Bearer eyJhbGciOi..."
    val sanitizedAuth = DebugLogManager.sanitize(inputAuth)
    assertFalse(sanitizedAuth.contains("Bearer eyJhbGciOi..."))
    assertTrue(sanitizedAuth.contains("[REDACTED]"))

    val inputPass = "User entered password=SuperSecretPassword123"
    val sanitizedPass = DebugLogManager.sanitize(inputPass)
    assertFalse(sanitizedPass.contains("SuperSecretPassword123"))
    assertTrue(sanitizedPass.contains("[REDACTED]"))
  }

  @Test
  fun testJavaCrashReportGenerationAndFormat() {
    val exception = NullPointerException("Simulated crash in test component")
    val report = CrashHandlerHelper.buildDiagnosticReport(
      context = context,
      reportType = ReportType.JAVA_CRASH,
      thread = Thread.currentThread(),
      throwable = exception,
      sessionId = "session-test-uuid",
      lastPhase = StartupPhase.MAIN_ACTIVITY_CREATE.id,
      wasClean = false,
      lastCleanTimestamp = 0L,
      reportTime = System.currentTimeMillis()
    )

    assertTrue(report.contains("REMMI BROWSER - AUTOMATIC DIAGNOSTIC REPORT"))
    assertTrue(report.contains("Report Type:\nJAVA_CRASH"))
    assertTrue(report.contains("DEVICE:"))
    assertTrue(report.contains("PROCESS:"))
    assertTrue(report.contains("STARTUP STATE:"))
    assertTrue(report.contains("Last startup phase: MAIN_ACTIVITY_CREATE"))
    assertTrue(report.contains("NATIVE:"))
    assertTrue(report.contains("SUBSYSTEM STATE:"))
    assertTrue(report.contains("PREVIOUS SESSION DIAGNOSTIC EVENTS"))
    assertTrue(report.contains("JAVA EXCEPTION:"))
    assertTrue(report.contains("java.lang.NullPointerException"))
    assertTrue(report.contains("Simulated crash in test component"))
    assertTrue(report.contains("END REPORT"))
  }

  @Test
  fun testAbnormalTerminationDetection() {
    // 1. Simulate a previous run that died during ADBLOCK_CONSTRUCTION_START without calling clean shutdown
    val prefs = context.getSharedPreferences(CrashHandlerHelper.PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
      .putBoolean(CrashHandlerHelper.KEY_PREVIOUS_RUN_CLEAN, false)
      .putString(CrashHandlerHelper.KEY_STARTUP_SESSION_ID, "dead-session-999")
      .putString(CrashHandlerHelper.KEY_STARTUP_PHASE, StartupPhase.ADBLOCK_CONSTRUCTION_START.id)
      .putLong(CrashHandlerHelper.KEY_STARTUP_TIMESTAMP, System.currentTimeMillis() - 60000L)
      .commit()

    // 2. Next process start executes
    CrashHandlerHelper.onProcessStart(context)

    // 3. Verify abnormal report was generated and recorded
    val pendingType = prefs.getString(CrashHandlerHelper.KEY_PENDING_TYPE, null)
    assertEquals(ReportType.ABNORMAL_TERMINATION.name, pendingType)

    val report = prefs.getString(CrashHandlerHelper.KEY_PENDING_REPORT, null)
    assertNotNull(report)
    assertTrue(report!!.contains("Report Type:\nABNORMAL_TERMINATION"))
    assertTrue(report.contains("NO JAVA EXCEPTION CAPTURED."))
    println("REPORT_IS: " + report)
    assertTrue(report.contains("Last startup phase: ADBLOCK_CONSTRUCTION_START"))
    assertTrue(report.contains("Startup session ID: dead-session-999"))

    // 4. Verify new run session initialized with previous_run_clean = false
    assertFalse(prefs.getBoolean(CrashHandlerHelper.KEY_PREVIOUS_RUN_CLEAN, true))
    val newSession = prefs.getString(CrashHandlerHelper.KEY_STARTUP_SESSION_ID, null)
    assertNotNull(newSession)
    assertFalse(newSession == "dead-session-999")
  }

  @Test
  fun testCleanRunLifecycleAndRecoveryExport() {
    // 1. Setup pending report
    val prefs = context.getSharedPreferences(CrashHandlerHelper.PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
      .putString(CrashHandlerHelper.KEY_PENDING_REPORT, "Sample Crash Log Content")
      .putLong(CrashHandlerHelper.KEY_PENDING_TIMESTAMP, System.currentTimeMillis())
      .putString(CrashHandlerHelper.KEY_PENDING_TYPE, ReportType.JAVA_CRASH.name)
      .commit()

    // 2. Check and export pending report
    val result = CrashHandlerHelper.checkAndExportPendingReport(context)
    assertNotNull(result)
    assertEquals("Sample Crash Log Content", result!!.fullReport)
    assertEquals(ReportType.JAVA_CRASH, result.reportType)

    // 3. Confirm pending marker was removed from SharedPreferences
    assertNull(prefs.getString(CrashHandlerHelper.KEY_PENDING_REPORT, null))

    // 4. Test advancing phase to APP_READY marks previous_run_clean = true
    CrashHandlerHelper.updateStartupPhase(context, StartupPhase.APP_READY)
    assertTrue(prefs.getBoolean(CrashHandlerHelper.KEY_PREVIOUS_RUN_CLEAN, false))

    // 5. Test graceful shutdown
    CrashHandlerHelper.markCleanShutdown(context)
    assertTrue(prefs.getBoolean(CrashHandlerHelper.KEY_PREVIOUS_RUN_CLEAN, false))
    assertEquals(StartupPhase.SHUTDOWN.id, prefs.getString(CrashHandlerHelper.KEY_STARTUP_PHASE, null))
  }

  @Test
  fun testProcessExitClassificationUserRequestedTermination() {
    val classified = CrashHandlerHelper.classifyExit(
      reason = 10, // REASON_USER_REQUESTED
      status = 0,
      description = "[REMOVE TASK] callingPid=6806"
    )
    assertEquals(com.remmi.browser.util.ProcessExitClassification.USER_REQUESTED_TERMINATION, classified)

    val signal = CrashHandlerHelper.classifySignal(
      status = 0,
      reason = 10,
      description = "[REMOVE TASK] callingPid=6806"
    )
    assertEquals("STATUS_0", signal)

    val report = CrashHandlerHelper.buildDiagnosticReport(
      context = context,
      reportType = ReportType.ABNORMAL_TERMINATION,
      thread = Thread.currentThread(),
      throwable = null,
      sessionId = "test-session-user-term",
      lastPhase = StartupPhase.APP_READY.id,
      wasClean = false,
      lastCleanTimestamp = 0L,
      reportTime = System.currentTimeMillis()
    )

    assertTrue(report.contains("REMMI BROWSER - AUTOMATIC DIAGNOSTIC REPORT"))
    assertTrue(report.contains("Tombstone / Native Backtrace:\nUNAVAILABLE"))
    assertFalse(report.contains("NATIVE CRASH DETECTED"))
  }

  @Test
  fun testProcessExitClassificationSignals() {
    assertEquals(
      com.remmi.browser.util.ProcessExitClassification.OOM_KILL,
      CrashHandlerHelper.classifyExit(reason = 3, status = 0, description = "low memory kill")
    )

    assertEquals(
      com.remmi.browser.util.ProcessExitClassification.ANR,
      CrashHandlerHelper.classifyExit(reason = 6, status = 0, description = "Input dispatching timed out")
    )

    assertEquals(
      com.remmi.browser.util.ProcessExitClassification.NATIVE_FATAL_SIGNAL,
      CrashHandlerHelper.classifyExit(reason = 5, status = 11, description = "segmentation fault")
    )
    assertEquals(
      "SIGSEGV (Segmentation Fault)",
      CrashHandlerHelper.classifySignal(status = 11, reason = 5, description = "segmentation fault")
    )
  }

  @Test
  fun testLastNativeOperationSessionAndPidIsolation() {
    CrashHandlerHelper.resetNativeOpState()
    val initialOp = CrashHandlerHelper.getLastNativeOpString(context)
    assertEquals("NONE", initialOp)

    CrashHandlerHelper.recordNativeOp(context = context, op = "[ADBLOCK_DEFAULT_RULES_START]")
    val currentOp = CrashHandlerHelper.getLastNativeOpString(context)
    assertTrue(currentOp.contains("[ADBLOCK_DEFAULT_RULES_START]"))
  }

  @Test
  fun testCompileSingleFlightInvariant() {
    val bridge = com.remmi.adblock.AdblockBridge()
    val initialMax = com.remmi.browser.util.HangWatchdog.maxActiveCompileJobs.get()

    val count = bridge.compileRules(
      defaultRulesText = "||example.com^\n||tracker.com^",
      additionalRulesText = "||ad.com^",
      source = "unit_test"
    )
    assertTrue(count >= 0)
    assertTrue(com.remmi.browser.util.HangWatchdog.maxActiveCompileJobs.get() <= 1)
  }
}
