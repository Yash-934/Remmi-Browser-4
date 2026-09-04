package com.remmi.browser.security

import android.content.Context
import android.os.Environment
import android.util.Log
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.storage.RemmiDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

enum class WipePhase(val title: String) {
  DESTROY_SESSIONS("Terminating browser tabs & active sessions"),
  PURGE_GECKO_STORAGE("Purging cookies, cache & Gecko storage"),
  SCRUB_DATABASE("Scrubbing encrypted database records"),
  WIPE_DISK_STORAGE("Removing downloaded files & temporary artifacts from disk"),
  CLEAR_CLIPBOARD("Clearing sensitive clipboard buffers"),
  WIPE_PASSWORD_VAULT("Zeroizing Password Vault & cryptographic master keys"),
  VERIFY_DESTRUCTION("Verifying zero-trace data destruction")
}

data class WipeStepTelemetry(
  val phase: WipePhase,
  val isSuccess: Boolean,
  val details: String,
)

sealed class PanicWipeState {
  object Idle : PanicWipeState()
  data class InProgress(
    val phaseDescription: String,
    val progress: Float,
    val currentPhase: WipePhase,
    val verifiedSteps: List<WipeStepTelemetry> = emptyList(),
  ) : PanicWipeState()
  data class Completed(
    val message: String,
    val verifiedSteps: List<WipeStepTelemetry>,
    val logicalReport: LogicalVerificationReport? = null,
  ) : PanicWipeState()
  data class Failed(
    val error: String,
    val failedPhase: WipePhase = WipePhase.VERIFY_DESTRUCTION,
    val verifiedSteps: List<WipeStepTelemetry> = emptyList(),
    val logicalReport: LogicalVerificationReport? = null,
  ) : PanicWipeState()
}

/**
 * PanicWipeManager: Autonomous, hardened, asynchronous Panic Wipe coordinator.
 * Operates on a dedicated SupervisorJob scope so wipe continues even if UI Composable is destroyed.
 * Coordinates multi-phase browser destruction, memory zeroization, recursive disk sanitization,
 * crash recovery, and cryptographic destruction + logical sanitization verification.
 */
object PanicWipeManager {
  private const val TAG = "PanicWipeManager"
  private const val PREFS_RECOVERY = "remmi_panic_recovery_state"
  private const val KEY_PENDING_WIPE = "is_wipe_pending"
  private const val KEY_PENDING_VAULT_WIPE = "is_vault_wipe_pending"

  private val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val wipeMutex = Mutex()

  private val _state = MutableStateFlow<PanicWipeState>(PanicWipeState.Idle)
  val state: StateFlow<PanicWipeState> = _state.asStateFlow()

  fun resetState() {
    _state.value = PanicWipeState.Idle
  }

  fun isWipePending(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_RECOVERY, Context.MODE_PRIVATE)
    return prefs.getBoolean(KEY_PENDING_WIPE, false)
  }

  /**
   * Checks if an emergency panic wipe was interrupted by app termination or process death.
   * If detected on app launch, immediately executes and completes the remaining sanitization.
   */
  suspend fun checkAndResumePendingWipe(context: Context, database: RemmiDatabase? = null): Boolean = withContext(Dispatchers.IO) {
    val prefs = context.getSharedPreferences(PREFS_RECOVERY, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(KEY_PENDING_WIPE, false)) {
      return@withContext false
    }
    val wipeVault = prefs.getBoolean(KEY_PENDING_VAULT_WIPE, false)
    Log.w(TAG, "[CRASH RECOVERY] Interrupted panic wipe detected! Resuming immediate sanitization (wipeVault=$wipeVault)...")
    return@withContext executeWipe(context, database, wipeVault)
  }

  internal fun markWipeInProgress(context: Context, wipeVault: Boolean) {
    try {
      context.getSharedPreferences(PREFS_RECOVERY, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_PENDING_WIPE, true)
        .putBoolean(KEY_PENDING_VAULT_WIPE, wipeVault)
        .commit()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to persist pending wipe state: ${e.message}")
    }
  }

  fun clearWipeMarker(context: Context) {
    try {
      context.getSharedPreferences(PREFS_RECOVERY, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to clear pending wipe state: ${e.message}")
    }
  }

  suspend fun executeWipe(
    context: Context,
    database: RemmiDatabase? = null,
    wipeVault: Boolean = false,
    onTabsClosed: () -> Unit = {}
  ): Boolean = withContext(Dispatchers.IO) {
    if (!wipeMutex.tryLock()) {
      Log.w(TAG, "Panic wipe already in progress. Ignoring duplicate invocation.")
      return@withContext false
    }

    markWipeInProgress(context, wipeVault)
    val telemetryList = mutableListOf<WipeStepTelemetry>()
    val totalSteps = if (wipeVault) 7f else 6f

    try {
      val geckoEngine = GeckoEngineManager.getInstance(context)
      
      // Stop background jobs
      try {
          com.remmi.browser.downloads.DownloadHandler.getInstance(context).cancelAllDownloads()
      } catch(e: Exception) {
          Log.w(TAG, "Failed to cancel downloads: ${e.message}")
      }
      try {
          com.remmi.browser.security.TorManager.getInstance(context).stopTor()
      } catch(e: Exception) {
          Log.w(TAG, "Failed to stop Tor: ${e.message}")
      }

      // Step 1: DESTROY_SESSIONS (Atomic session, delegate, and tab destruction)
      _state.value = PanicWipeState.InProgress(
        phaseDescription = "${WipePhase.DESTROY_SESSIONS.title}...",
        progress = 1f / totalSteps,
        currentPhase = WipePhase.DESTROY_SESSIONS,
        verifiedSteps = telemetryList.toList()
      )
      val sessionsHalted = withContext(Dispatchers.Main.immediate) {
        val halted = geckoEngine.destroyAllBrowserState()
        try {
          onTabsClosed()
        } catch (e: Exception) {
          Log.w(TAG, "onTabsClosed UI notice: ${e.message}")
        }
        halted
      }
      telemetryList.add(
        WipeStepTelemetry(
          WipePhase.DESTROY_SESSIONS,
          sessionsHalted,
          if (sessionsHalted) "Sessions & tabs halted" else "Session halt encountered issues"
        )
      )

      // Step 2: PURGE_GECKO_STORAGE (Clear Gecko Runtime Cookies & Site Data)
      _state.value = PanicWipeState.InProgress(
        phaseDescription = "${WipePhase.PURGE_GECKO_STORAGE.title}...",
        progress = 2f / totalSteps,
        currentPhase = WipePhase.PURGE_GECKO_STORAGE,
        verifiedSteps = telemetryList.toList()
      )
      val geckoCleared = geckoEngine.clearCookiesAndCacheSafely()
      telemetryList.add(
        WipeStepTelemetry(
          WipePhase.PURGE_GECKO_STORAGE,
          geckoCleared,
          if (geckoCleared) "GeckoView storage & cookies purged" else "Storage purge completed with fallback"
        )
      )

      // Step 3 & 4 Orchestrated: WIPE_PASSWORD_VAULT and SCRUB_DATABASE
      _state.value = PanicWipeState.InProgress(
        phaseDescription = "${WipePhase.SCRUB_DATABASE.title}...",
        progress = 4f / totalSteps,
        currentPhase = WipePhase.SCRUB_DATABASE,
        verifiedSteps = telemetryList.toList()
      )

      try {
        val purgeResult = RemmiDatabase.secureWipe(context, wipeVault) {
          _state.value = PanicWipeState.InProgress(
            phaseDescription = "${WipePhase.WIPE_PASSWORD_VAULT.title}...",
            progress = 3f / totalSteps,
            currentPhase = WipePhase.WIPE_PASSWORD_VAULT,
            verifiedSteps = telemetryList.toList()
          )
          val pmRepo = PasswordManagerRepository.getInstance(context)
          val success = pmRepo.wipeAllVaultData()
          PasswordManagerRepository.resetInstance()
          success
        }

        if (wipeVault) {
          if (purgeResult.vaultScrubSucceeded) {
            telemetryList.add(WipeStepTelemetry(WipePhase.WIPE_PASSWORD_VAULT, true, "Vault records & cryptographic master keys destroyed"))
          } else {
            telemetryList.add(WipeStepTelemetry(WipePhase.WIPE_PASSWORD_VAULT, false, "Vault wipe reported failure or key revocation failed"))
          }
        }

        if (purgeResult.filesFailed == 0 && purgeResult.errors.isEmpty()) {
          telemetryList.add(WipeStepTelemetry(WipePhase.SCRUB_DATABASE, true, "Database files (${purgeResult.filesDeleted} files) and journals erased"))
        } else {
          val errorMsg = if (purgeResult.errors.isNotEmpty()) purgeResult.errors.joinToString() else "Failed to delete ${purgeResult.filesFailed} database file(s)"
          telemetryList.add(WipeStepTelemetry(WipePhase.SCRUB_DATABASE, false, "Database purge encountered issues: $errorMsg"))
        }
      } catch (e: Exception) {
        Log.e(TAG, "Database secure wipe error: ${e.message}")
        telemetryList.add(WipeStepTelemetry(WipePhase.SCRUB_DATABASE, false, "Database secure wipe failed: ${e.message}"))
      }

      // Step 5: WIPE_DISK_STORAGE (Recursive Disk & Temp File Sanitization)
      _state.value = PanicWipeState.InProgress(
        phaseDescription = "${WipePhase.WIPE_DISK_STORAGE.title}...",
        progress = 5f / totalSteps,
        currentPhase = WipePhase.WIPE_DISK_STORAGE,
        verifiedSteps = telemetryList.toList()
      )
      try {
        var filesDeleted = 0
        var filesFailed = 0
        val diskErrors = mutableListOf<String>()

        fun deleteFileOrDir(target: File?) {
          if (target == null || !target.exists()) return
          if (target.isDirectory) {
            target.listFiles()?.forEach { deleteFileOrDir(it) }
            if (target.delete()) {
              filesDeleted++
            } else {
              filesFailed++
              diskErrors.add("Failed to delete directory: ${target.name}")
            }
          } else {
            if (target.delete()) {
              filesDeleted++
            } else {
              filesFailed++
              diskErrors.add("Failed to delete file: ${target.name}")
            }
          }
        }

        deleteFileOrDir(context.cacheDir)
        deleteFileOrDir(context.codeCacheDir)
        deleteFileOrDir(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))

        // Scrub downloads created by the browser via MediaStore (API 29+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
          try {
            val resolver = context.contentResolver
            val deletedRows = resolver.delete(
              android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
              "${android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
              arrayOf(context.packageName)
            )
            Log.d(TAG, "MediaStore owner-package download cleanup deleted $deletedRows rows")
          } catch (e: Exception) {
            Log.w(TAG, "MediaStore download scrub notice: ${e.message}")
          }
        }

        // Deep scrub temporary and backup artifacts across private storage
        val filesDir = context.filesDir
        if (filesDir != null && filesDir.exists()) {
          filesDir.walkTopDown().maxDepth(4).forEach { file ->
            val name = file.name.lowercase()
            if (name.endsWith(".tmp") || name.endsWith(".bak") ||
              name.startsWith("old_backup") || name.startsWith(".cache_backup") ||
              name.contains("cookies.sqlite") || name.contains("places.sqlite")
            ) {
              if (file.exists()) {
                if (file.delete()) {
                  filesDeleted++
                } else {
                  filesFailed++
                  diskErrors.add("Failed to delete artifact: ${file.name}")
                }
              }
            }
          }
        }

        val diskSuccess = filesFailed == 0 && diskErrors.isEmpty()
        telemetryList.add(
          WipeStepTelemetry(
            WipePhase.WIPE_DISK_STORAGE,
            diskSuccess,
            if (diskSuccess) "Private downloads, temporary artifacts & caches sanitized ($filesDeleted deleted)"
            else "Disk purge had failures ($filesFailed failed, $filesDeleted deleted): ${diskErrors.take(3).joinToString()}"
          )
        )
      } catch (e: Exception) {
        Log.e(TAG, "Disk wipe error: ${e.message}")
        telemetryList.add(WipeStepTelemetry(WipePhase.WIPE_DISK_STORAGE, false, "Disk purge failed: ${e.message}"))
      }

      // Step 6: CLEAR_CLIPBOARD (Zeroize clipboard memory)
      _state.value = PanicWipeState.InProgress(
        phaseDescription = "${WipePhase.CLEAR_CLIPBOARD.title}...",
        progress = 6f / totalSteps,
        currentPhase = WipePhase.CLEAR_CLIPBOARD,
        verifiedSteps = telemetryList.toList()
      )
      try {
        val clipboard = ClipboardManager(context)
        clipboard.clear()
        telemetryList.add(WipeStepTelemetry(WipePhase.CLEAR_CLIPBOARD, true, "OS Clipboard memory zeroized"))
      } catch (e: Exception) {
        Log.w(TAG, "Clipboard clear notice: ${e.message}")
        telemetryList.add(WipeStepTelemetry(WipePhase.CLEAR_CLIPBOARD, false, "Clipboard clear failed: ${e.message}"))
      }

      // Step 7 / Final: VERIFY_DESTRUCTION (Logical Verification via WipeVerifier)
      _state.value = PanicWipeState.InProgress(
        phaseDescription = "${WipePhase.VERIFY_DESTRUCTION.title}...",
        progress = 1.0f,
        currentPhase = WipePhase.VERIFY_DESTRUCTION,
        verifiedSteps = telemetryList.toList()
      )

      val report = try {
        WipeVerifier.performLogicalDestructionVerification(context, wipeVault)
      } catch (e: Exception) {
        Log.e(TAG, "Logical verification exception: ${e.message}", e)
        null
      }

      val hasCriticalFailure = telemetryList.any { telemetry ->
        !telemetry.isSuccess && telemetry.phase != WipePhase.CLEAR_CLIPBOARD
      }

      val isCompleteSuccess = (report?.isCompleteSuccess == true) && !hasCriticalFailure

      if (isCompleteSuccess) {
        clearWipeMarker(context)
        telemetryList.add(WipeStepTelemetry(WipePhase.VERIFY_DESTRUCTION, true, "Logical sanitization audit verified (0 residual records)"))
        _state.value = PanicWipeState.Completed(
          message = "Cryptographic destruction + logical sanitization verified.",
          verifiedSteps = telemetryList.toList(),
          logicalReport = report
        )
        true
      } else {
        // KEEP recovery marker
        val failureReason = if (report == null) {
          "Logical verification threw an unexpected exception"
        } else if (!report.isCompleteSuccess) {
          "Logical destruction verification failed (${report.remainingFilesFound} residual files, ${report.remainingDbRowsFound} residual DB rows)"
        } else {
          "Critical wipe step failure detected during execution"
        }

        telemetryList.add(WipeStepTelemetry(WipePhase.VERIFY_DESTRUCTION, false, failureReason))
        _state.value = PanicWipeState.Failed(
          error = failureReason,
          failedPhase = WipePhase.VERIFY_DESTRUCTION,
          verifiedSteps = telemetryList.toList(),
          logicalReport = report
        )
        false
      }
    } catch (e: Exception) {
      Log.e(TAG, "Fatal Panic Wipe failure: ${e.message}", e)
      // KEEP marker
      // DO NOT claim success
      _state.value = PanicWipeState.Failed(
        error = "Emergency sanitization interrupted: ${e.message}",
        failedPhase = WipePhase.VERIFY_DESTRUCTION,
        verifiedSteps = telemetryList.toList()
      )
      false
    } finally {
      wipeMutex.unlock()
    }
  }
}
