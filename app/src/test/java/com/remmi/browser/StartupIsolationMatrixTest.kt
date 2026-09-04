package com.remmi.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remmi.adblock.AdblockBridge
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.storage.SqlCipherInitializer
import com.remmi.browser.util.CrashHandlerHelper
import com.remmi.browser.util.DebugLogManager
import com.remmi.browser.util.StartupPhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class StartupIsolationMatrixTest {

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
    SqlCipherInitializer.resetForTesting()
    CrashHandlerHelper.updateStartupPhase(phase = StartupPhase.PROCESS_START)
  }

  @Test
  fun testConfigD_ExecutionTrace() {
    // Config D:
    // - SQLCipher loaded or gated
    // - Adblock initialization deferred / async
    // - PasswordManager DB access deferred until DB is ready

    // Step 1: Process Start
    CrashHandlerHelper.onProcessStart(context)
    assertEquals(StartupPhase.PROCESS_START, CrashHandlerHelper.currentPhase)

    // Step 2: Application Created
    CrashHandlerHelper.updateStartupPhase(context, StartupPhase.APPLICATION_CREATED)
    assertEquals(StartupPhase.APPLICATION_CREATED, CrashHandlerHelper.currentPhase)

    // Step 3: Adblock construction is lightweight (fallback only)
    val bridge = AdblockBridge.getInstance()
    assertNotNull(bridge)

    // Step 4: Activity create & Screen compose
    CrashHandlerHelper.updateStartupPhase(context, StartupPhase.MAIN_ACTIVITY_CREATE)
    CrashHandlerHelper.updateStartupPhase(context, StartupPhase.BROWSER_SCREEN_COMPOSE)

    // Step 5: First frame rendered
    CrashHandlerHelper.updateStartupPhase(context, StartupPhase.FIRST_FRAME)

    // Step 6: App ready
    CrashHandlerHelper.updateStartupPhase(context, StartupPhase.APP_READY)
    assertEquals(StartupPhase.APP_READY, CrashHandlerHelper.currentPhase)

    val prefs = context.getSharedPreferences(CrashHandlerHelper.PREFS_NAME, Context.MODE_PRIVATE)
    assertTrue(prefs.getBoolean(CrashHandlerHelper.KEY_PREVIOUS_RUN_CLEAN, false))
  }

  @Test
  fun testSqlCipherFailureControlledErrorState() {
    SqlCipherInitializer.simulateLoadFailureForTesting(context)
    val loaded = SqlCipherInitializer.ensureLoaded()
    assertFalse(loaded)
    val prefs = context.getSharedPreferences(CrashHandlerHelper.PREFS_NAME, Context.MODE_PRIVATE)
    assertEquals(StartupPhase.SQLCIPHER_LOAD_FAILED.id, prefs.getString(CrashHandlerHelper.KEY_STARTUP_PHASE, null))
  }
}
