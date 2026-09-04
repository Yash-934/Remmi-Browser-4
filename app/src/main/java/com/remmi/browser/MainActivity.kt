package com.remmi.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.remmi.browser.engine.TabManager
import com.remmi.browser.security.PanicWipeManager
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.storage.SettingsRepository
import com.remmi.browser.ui.passwords.PasswordManagerScreen
import com.remmi.browser.ui.screens.BrowserScreen
import com.remmi.browser.ui.screens.DebugLogsScreen
import com.remmi.browser.ui.screens.SettingsScreen
import com.remmi.browser.ui.screens.WelcomeScreen
import com.remmi.browser.ui.theme.RemmiTheme

import com.remmi.browser.ui.components.CrashReportDialog
import com.remmi.browser.util.CrashExportResult
import com.remmi.browser.util.CrashHandlerHelper

enum class ScreenRoute {
  WELCOME,
  BROWSER,
  SETTINGS,
  PASSWORDS,
  DEBUG_LOGS,
  EMERGENCY_RECOVERY,
  VAULT_RECOVERY
}

class MainActivity : FragmentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    val startTime = android.os.SystemClock.elapsedRealtime()
    android.util.Log.i("AppStartup", "STATE_LOG: APP_START (time=$startTime)")

    super.onCreate(savedInstanceState)
    com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(this, com.remmi.browser.util.StartupPhase.MAIN_ACTIVITY_CREATE)

    enableEdgeToEdge()

    val isPendingWipe = PanicWipeManager.isWipePending(applicationContext)

    val prefs = getSharedPreferences("remmi_app_state", Context.MODE_PRIVATE)
    val hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false)
    val settingsRepo = SettingsRepository.getInstance(applicationContext)

    val incomingUrl = handleIncomingIntent(intent)
    val initialScreen = when {
      isPendingWipe -> ScreenRoute.EMERGENCY_RECOVERY
      incomingUrl != null || hasSeenOnboarding -> ScreenRoute.BROWSER
      else -> ScreenRoute.WELCOME
    }

    android.util.Log.i("AppStartup", "STATE_LOG: UI_CONTENT_SET (time=${android.os.SystemClock.elapsedRealtime()})")

    setContent {
      val settings by settingsRepo.settings.collectAsState()
      var crashResultToShow by remember { mutableStateOf<CrashExportResult?>(null) }

      androidx.compose.runtime.LaunchedEffect(Unit) {
        android.util.Log.i("AppStartup", "STATE_LOG: FIRST_FRAME (time=${android.os.SystemClock.elapsedRealtime()})")
        com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(this@MainActivity, com.remmi.browser.util.StartupPhase.FIRST_FRAME)
        kotlinx.coroutines.withContext(Dispatchers.IO) {
          val pendingCrash = com.remmi.browser.util.CrashHandlerHelper.checkAndExportPendingReport(this@MainActivity)
          if (pendingCrash != null) {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
              crashResultToShow = pendingCrash
              val label = if (pendingCrash.reportType == com.remmi.browser.util.ReportType.ABNORMAL_TERMINATION) {
                "Abnormal termination report"
              } else {
                "Crash report"
              }
              android.widget.Toast.makeText(
                this@MainActivity,
                "$label saved to Downloads/Remmi Crash Reports/",
                android.widget.Toast.LENGTH_LONG
              ).show()
            }
          }
        }
        com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(this@MainActivity, com.remmi.browser.util.StartupPhase.APP_READY)
      }

      RemmiTheme(
        cyberTheme = settings.cyberTheme,
        pureBlackOled = settings.pureBlackOled,
        browserFont = settings.browserFont,
      ) {
        var currentScreen by remember {
          mutableStateOf(initialScreen)
        }
        
        val dbState by com.remmi.browser.storage.RemmiDatabase.databaseState.collectAsState()
        androidx.compose.runtime.LaunchedEffect(dbState) {
          if (dbState is com.remmi.browser.storage.RemmiDatabase.DatabaseState.Error) {
             val err = (dbState as com.remmi.browser.storage.RemmiDatabase.DatabaseState.Error).throwable
             if (err is com.remmi.browser.storage.VaultRecoveryRequiredException) {
                 currentScreen = ScreenRoute.VAULT_RECOVERY
             }
          }
        }

        if (crashResultToShow != null) {
          CrashReportDialog(
            crashResult = crashResultToShow!!,
            onDismiss = { crashResultToShow = null }
          )
        }

        AnimatedContent(
          targetState = currentScreen,
          transitionSpec = { fadeIn() togetherWith fadeOut() },
          label = "screen_navigation",
        ) { screen ->
          when (screen) {
            ScreenRoute.EMERGENCY_RECOVERY -> {
              com.remmi.browser.ui.screens.EmergencyWipeRecoveryScreen(
                onRecoveryComplete = {
                  com.remmi.browser.engine.GeckoEngineManager.getInstance(applicationContext).initializeRuntimeAsync()
                  currentScreen = ScreenRoute.BROWSER
                }
              )
            }
            ScreenRoute.VAULT_RECOVERY -> {
              com.remmi.browser.ui.screens.VaultRecoveryScreen(
                onProceedToWipe = {
                  com.remmi.browser.security.PanicWipeManager.markWipeInProgress(this@MainActivity, wipeVault = true)
                  currentScreen = ScreenRoute.EMERGENCY_RECOVERY
                }
              )
            }
            ScreenRoute.WELCOME -> {
              WelcomeScreen(
                onEnterBrowser = {
                  prefs.edit().putBoolean("has_seen_onboarding", true).apply()
                  currentScreen = ScreenRoute.BROWSER
                }
              )
            }
            ScreenRoute.BROWSER -> {
              BrowserScreen(
                onOpenSettings = {
                  currentScreen = ScreenRoute.SETTINGS
                },
                onOpenWelcome = {
                  currentScreen = ScreenRoute.WELCOME
                },
                onOpenPasswords = {
                  currentScreen = ScreenRoute.PASSWORDS
                }
              )
            }
            ScreenRoute.SETTINGS -> {
              SettingsScreen(
                onBack = {
                  currentScreen = ScreenRoute.BROWSER
                },
                onOpenPasswords = {
                  currentScreen = ScreenRoute.PASSWORDS
                },
                onOpenDebugLogs = {
                  currentScreen = ScreenRoute.DEBUG_LOGS
                }
              )
            }
            ScreenRoute.PASSWORDS -> {
              PasswordManagerScreen(
                onBack = {
                  currentScreen = ScreenRoute.SETTINGS
                }
              )
            }
            ScreenRoute.DEBUG_LOGS -> {
              DebugLogsScreen(
                onBack = {
                  currentScreen = ScreenRoute.SETTINGS
                }
              )
            }
          }
        }
      }
    }
  }

  override fun onDestroy() {
    if (isFinishing) {
      com.remmi.browser.util.CrashHandlerHelper.markCleanShutdown(this)
    }
    super.onDestroy()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIncomingIntent(intent)
  }

  private fun handleIncomingIntent(intent: Intent?): String? {
    val data = intent?.dataString
    if (!data.isNullOrBlank() && (data.startsWith("http://") || data.startsWith("https://"))) {
      TabManager.getInstance().openOrNavigateTab(url = data)
      return data
    }
    return null
  }
}
