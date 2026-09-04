package com.remmi.browser.security

import android.content.Context
import android.os.Environment
import android.util.Log
import com.remmi.browser.storage.RemmiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore

data class LogicalVerificationReport(
  val isCompleteSuccess: Boolean,
  val geckoStorageVerified: Boolean,
  val databaseRecordsVerified: Boolean,
  val diskArtifactsVerified: Boolean,
  val vaultSecretsVerified: Boolean,
  val remainingFilesFound: Int,
  val remainingDbRowsFound: Int,
  val details: List<String>,
  val suspiciousArtifacts: List<String> = emptyList(),
)

/**
 * WipeVerifier: Performs post-wipe logical verification across database records,
 * GeckoView storage, filesystem sandbox artifacts, and cryptographic Keystore aliases
 * to verify complete zero-trace sanitization.
 *
 * Strictly READ-ONLY: Must never mutate, clear, or delete files during verification.
 * Strictly FAIL-CLOSED: Initializes all verification flags to false and requires verified pass.
 */
object WipeVerifier {
  private const val TAG = "WipeVerifier"

  suspend fun performLogicalDestructionVerification(
    context: Context,
    vaultWiped: Boolean,
  ): LogicalVerificationReport = withContext(Dispatchers.IO) {
    val details = mutableListOf<String>()
    val detectedArtifacts = mutableListOf<String>()
    val uniqueResidualFiles = mutableSetOf<String>()
    var remainingRows = 0

    // True fail-closed: All verification flags initialize to false
    var dbVerified = false
    var geckoVerified = false
    var diskVerified = false
    var vaultVerified = !vaultWiped

    val dbFile = context.getDatabasePath("remmi_vault.db")
    val isDbFileAbsent = !dbFile.exists()

    // 1. Room Database Record Verification (READ-ONLY)
    try {
      if (isDbFileAbsent) {
        val walFile = File(dbFile.path + "-wal")
        val shmFile = File(dbFile.path + "-shm")
        val journalFile = File(dbFile.path + "-journal")

        val walAbsent = !walFile.exists()
        val shmAbsent = !shmFile.exists()
        val journalAbsent = !journalFile.exists()

        if (walAbsent && shmAbsent && journalAbsent) {
          dbVerified = true
          details.add("Database Record Audit: Database files purged (0 residual records).")
        } else {
          dbVerified = false
          if (!walAbsent) {
            uniqueResidualFiles.add(walFile.canonicalPath)
            details.add("Database Record Audit FAILED: Residual WAL file detected.")
          }
          if (!shmAbsent) {
            uniqueResidualFiles.add(shmFile.canonicalPath)
            details.add("Database Record Audit FAILED: Residual SHM file detected.")
          }
          if (!journalAbsent) {
            uniqueResidualFiles.add(journalFile.canonicalPath)
            details.add("Database Record Audit FAILED: Residual Journal file detected.")
          }
        }
      } else {
        // DB file exists
        if (vaultWiped) {
           dbVerified = false
           remainingRows += 1
           details.add("Database Record Audit FAILED: Database file still exists after full wipe.")
        } else {
           // read row counts
           val db = RemmiDatabase.getDatabaseAsync(context)
           val historyCount = db.historyDao().getCount()
           val tabCount = db.sessionTabDao().getCount()
           val downloadCount = db.downloadDao().getCount()
           val eventCount = db.blockedEventDao().getCount()

           val totalDbRows = historyCount + tabCount + downloadCount + eventCount
           remainingRows += totalDbRows

           if (totalDbRows == 0) {
             dbVerified = true
             details.add("Database Record Audit: Verified 0 remaining history, tab, download, and event rows.")
           } else {
             dbVerified = false
             details.add("Database Record Audit FAILED: Verified $totalDbRows remaining rows.")
           }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Database verification error: ${e.message}", e)
      dbVerified = false
      details.add("Database Record Audit FAILED: ${e.message}")
    }

    // 2. Observable Gecko Storage Verification (READ-ONLY)
    try {
      val geckoResidualArtifacts = mutableListOf<String>()
      scanGeckoSensitiveArtifacts(context, geckoResidualArtifacts, uniqueResidualFiles)

      if (geckoResidualArtifacts.isEmpty()) {
        geckoVerified = true
        details.add("Gecko Storage Audit: Sensitive Gecko browsing data verified absent (0 residual user-data artifacts).")
      } else {
        geckoVerified = false
        detectedArtifacts.addAll(geckoResidualArtifacts)
        details.add("Gecko Storage Audit FAILED: ${geckoResidualArtifacts.size} residual/unknown Gecko artifact(s) detected.")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Gecko storage verification error: ${e.message}", e)
      geckoVerified = false
      details.add("Gecko Storage Audit FAILED: ${e.message}")
    }

    // 3. Deep Recursive Disk Artifact Scanner with Unique-Path Tracking (READ-ONLY)
    try {
      val extDownloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
      
      collectDirectoryFiles(extDownloadDir, uniqueResidualFiles)
      collectDirectoryFiles(context.cacheDir, uniqueResidualFiles)
      collectDirectoryFiles(context.codeCacheDir, uniqueResidualFiles)

      // Recursively scan target folders for suspicious artifacts (.wal, .shm, .tmp, .bak, etc.)
      context.cacheDir?.let { scanSandboxArtifacts(it, detectedArtifacts, uniqueResidualFiles) }
      context.codeCacheDir?.let { scanSandboxArtifacts(it, detectedArtifacts, uniqueResidualFiles) }
      extDownloadDir?.let { scanSandboxArtifacts(it, detectedArtifacts, uniqueResidualFiles) }
      context.filesDir?.let { scanSandboxArtifacts(it, detectedArtifacts, uniqueResidualFiles) }

      if (uniqueResidualFiles.isEmpty() && detectedArtifacts.isEmpty()) {
        diskVerified = true
        details.add("Recursive Disk Audit: Zero residual cache, temporary, or unencrypted artifacts found on disk.")
      } else {
        diskVerified = false
        details.add("Recursive Disk Audit FAILED: ${uniqueResidualFiles.size} unique residual file(s), ${detectedArtifacts.size} suspicious artifact(s).")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Disk artifact verification error: ${e.message}", e)
      diskVerified = false
      details.add("Disk artifact verification FAILED: ${e.message}")
    }

    // 4. Password Vault & Cryptographic Keys Verification (READ-ONLY)
    if (vaultWiped) {
      try {
        if (isDbFileAbsent) {
          val walFile = File(dbFile.path + "-wal")
          val shmFile = File(dbFile.path + "-shm")
          val journalFile = File(dbFile.path + "-journal")

          val walAbsent = !walFile.exists()
          val shmAbsent = !shmFile.exists()
          val journalAbsent = !journalFile.exists()

          var keyRevoked = true
          try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val masterAliasExists = ks.containsAlias("remmi_db_master_key")
            val bioAliasExists = ks.containsAlias("Remmi_PM_Biometric_DEK_Wrapper")
            if (masterAliasExists || bioAliasExists) {
              keyRevoked = false
              if (masterAliasExists) details.add("Cryptographic Audit FAILED: remmi_db_master_key still present in AndroidKeyStore.")
              if (bioAliasExists) details.add("Cryptographic Audit FAILED: Remmi_PM_Biometric_DEK_Wrapper still present in AndroidKeyStore.")
            }
          } catch (e: Exception) {
            Log.e(TAG, "Keystore alias verification error: ${e.message}", e)
            keyRevoked = false
            details.add("Cryptographic Audit FAILED: Keystore check exception: ${e.message}")
          }

          if (isDbFileAbsent && walAbsent && shmAbsent && journalAbsent && keyRevoked) {
            vaultVerified = true
            details.add("Cryptographic Audit: Verified zeroized password vault, purged database files, and revoked hardware Keystore wrapping keys.")
          } else {
            vaultVerified = false
            if (!walAbsent) uniqueResidualFiles.add(walFile.canonicalPath)
            if (!shmAbsent) uniqueResidualFiles.add(shmFile.canonicalPath)
            if (!journalAbsent) uniqueResidualFiles.add(journalFile.canonicalPath)
            details.add("Cryptographic Audit FAILED: Vault residual artifacts or Keystore keys detected.")
          }
        } else {
          vaultVerified = false
          details.add("Cryptographic Audit FAILED: Database file still exists after vault wipe.")
        }
      } catch (e: Exception) {
        Log.e(TAG, "Vault verification error: ${e.message}", e)
        vaultVerified = false
        details.add("Cryptographic Audit FAILED: ${e.message}")
      }
    } else {
      vaultVerified = true
      details.add("Cryptographic Audit: Vault preserved as per user request.")
    }

    val remainingFiles = uniqueResidualFiles.size
    val isAllPassed = dbVerified && diskVerified && geckoVerified && (!vaultWiped || vaultVerified) && remainingFiles == 0 && remainingRows == 0

    LogicalVerificationReport(
      isCompleteSuccess = isAllPassed,
      geckoStorageVerified = geckoVerified,
      databaseRecordsVerified = dbVerified,
      diskArtifactsVerified = diskVerified,
      vaultSecretsVerified = vaultVerified,
      remainingFilesFound = remainingFiles,
      remainingDbRowsFound = remainingRows,
      details = details,
      suspiciousArtifacts = detectedArtifacts.distinct(),
    )
  }

  enum class GeckoArtifactClassification {
    MUST_BE_GONE_USER_DATA,
    ENGINE_INFRASTRUCTURE,
    UNKNOWN_GECKO_CRYPTO_ARTIFACT,
    UNKNOWN_GECKO_ARTIFACT
  }

  /**
   * Classifies GeckoView artifacts into user browsing data, required engine infrastructure,
   * or unknown artifacts.
   *
   * ENGINE_INFRASTRUCTURE_ALLOWED:
   * - handlers.json, xulstore.json, compatibility.ini, prefs.js, user.js (Engine configuration/UI metadata)
   * - extensions.json, addonStartup.json.lz4 (WebExtension platform state)
   * - parent.lock, .parentlock (Runtime process lockfiles)
   * Note: These are required by the Gecko runtime and are intentionally preserved.
   *
   * UNKNOWN_GECKO_CRYPTO_ARTIFACT:
   * - cert9.db: NSS certificate database. Could potentially contain user-added certificate trust exceptions.
   * - key4.db: NSS key database. Could potentially contain keys for client certificates or other user-created crypto material.
   * - pkcs11.txt, secmod.db: Related NSS crypto modules.
   * Since there is insufficient evidence that these cannot contain user-created keys/certificates,
   * they are marked as UNKNOWN_GECKO_CRYPTO_ARTIFACT and fail verification to prevent unsupported security claims.
   */
  fun classifyGeckoArtifact(file: File): GeckoArtifactClassification {
    val name = file.name.lowercase()
    val path = file.absolutePath.lowercase().replace('\\', '/')

    // 1. MUST_BE_GONE_USER_DATA: Any residual browsing session, cookie, cache, or credential data
    val isUserData = name.contains("cookies.sqlite") ||
      name.contains("webappsstore.sqlite") ||
      name.contains("formhistory.sqlite") ||
      name.contains("logins.json") ||
      name.contains("signons.sqlite") ||
      name.contains("permissions.sqlite") ||
      name.contains("content-prefs.sqlite") ||
      name.contains("places.sqlite") ||
      name.contains("favicons.sqlite") ||
      path.contains("/storage/default/") ||
      path.contains("/storage/permanent/") ||
      path.contains("/cache2/entries/") ||
      path.contains("/net_cache/") ||
      path.contains("/gecko_cache/")

    if (isUserData) return GeckoArtifactClassification.MUST_BE_GONE_USER_DATA

    // 2. UNKNOWN_GECKO_CRYPTO_ARTIFACT: Crypto DBs whose user-state footprint is uncertain
    val isCryptoArtifact = name == "cert9.db" ||
      name == "key4.db" ||
      name == "pkcs11.txt" ||
      name == "secmod.db"

    if (isCryptoArtifact) return GeckoArtifactClassification.UNKNOWN_GECKO_CRYPTO_ARTIFACT

    // 3. ENGINE_INFRASTRUCTURE: Required Gecko runtime state - explicitly allowed
    val isEngineInfrastructure = name == "handlers.json" ||
      name == "xulstore.json" ||
      name == "compatibility.ini" ||
      name == "prefs.js" ||
      name == "user.js" ||
      name == "extensions.json" ||
      name.startsWith("addonstartup") ||
      name == ".parentlock" ||
      name == "parent.lock"

    if (isEngineInfrastructure) return GeckoArtifactClassification.ENGINE_INFRASTRUCTURE

    // 4. Any unclassified file in Gecko profile directories is treated as unknown (Fail-closed)
    return GeckoArtifactClassification.UNKNOWN_GECKO_ARTIFACT
  }

  /**
   * Performs read-only inspection of dedicated GeckoView profile and cache directories
   * looking for residual cookies, web storage, logins, or unclassified artifacts.
   *
   * Scoped strictly to Gecko profile roots and Gecko storage subtrees:
   * 1. context.dataDir/app_gecko (Gecko profile and runtime directory)
   * 2. context.dataDir/files/mozilla (Gecko profile subtrees)
   * 3. context.filesDir/mozilla (Mozilla profile folder if present)
   * 4. context.cacheDir/gecko or context.cacheDir/cache2 (Gecko HTTP/media cache subtrees)
   *
   * Non-Gecko generic application files in filesDir (e.g. app config, Room DBs) are NOT
   * evaluated under Gecko profile inspection.
   */
  fun scanGeckoSensitiveArtifacts(
    context: Context,
    outResiduals: MutableList<String>,
    outUniqueFiles: MutableSet<String>,
  ) {
    val searchRoots = listOfNotNull(
      context.dataDir?.let { File(it, "app_gecko") },
      context.dataDir?.let { File(it, "files/mozilla") },
      context.filesDir?.let { File(it, "mozilla") },
      context.cacheDir?.let { File(it, "gecko") },
      context.cacheDir?.let { File(it, "cache2") }
    )

    searchRoots.forEach { root ->
      if (root.exists() && root.isDirectory) {
        root.walkTopDown().forEach { file ->
          if (file.isFile) {
            when (classifyGeckoArtifact(file)) {
              GeckoArtifactClassification.MUST_BE_GONE_USER_DATA -> {
                outResiduals.add("[USER_DATA] ${file.absolutePath}")
                outUniqueFiles.add(file.canonicalPath)
              }
              GeckoArtifactClassification.UNKNOWN_GECKO_CRYPTO_ARTIFACT -> {
                outResiduals.add("[UNKNOWN_GECKO_CRYPTO_ARTIFACT] ${file.absolutePath}")
                outUniqueFiles.add(file.canonicalPath)
              }
              GeckoArtifactClassification.UNKNOWN_GECKO_ARTIFACT -> {
                outResiduals.add("[UNKNOWN_GECKO_ARTIFACT] ${file.absolutePath}")
                outUniqueFiles.add(file.canonicalPath)
              }
              GeckoArtifactClassification.ENGINE_INFRASTRUCTURE -> {
                // Intentionally preserved engine infrastructure; not a violation
              }
            }
          }
        }
      }
    }
  }

  private fun collectDirectoryFiles(dir: File?, outSet: MutableSet<String>) {
    if (dir == null || !dir.exists() || !dir.isDirectory) return
    dir.walkTopDown().forEach { file ->
      if (file.isFile) {
        outSet.add(file.canonicalPath)
      }
    }
  }

  /**
   * Recursively scans application private sandbox directories for sensitive leftovers:
   * *.wal, *.shm (unflushed database transaction logs), *.tmp, old_backup.*, .cache_backup
   */
  fun scanSandboxArtifacts(
    rootDir: File,
    outArtifacts: MutableList<String>,
    outUniqueFiles: MutableSet<String>? = null,
  ) {
    if (!rootDir.exists() || !rootDir.isDirectory) return
    rootDir.walkTopDown().forEach { file ->
      if (file.isFile) {
        val name = file.name.lowercase()
        val isSuspicious = name.endsWith(".wal") ||
          name.endsWith(".shm") ||
          name.endsWith(".tmp") ||
          name.endsWith(".bak") ||
          name.startsWith("old_backup") ||
          name.startsWith(".cache_backup") ||
          name.contains("cookies.sqlite") ||
          name.contains("places.sqlite")

        if (isSuspicious) {
          outArtifacts.add(file.absolutePath)
          outUniqueFiles?.add(file.canonicalPath)
        }
      }
    }
  }

  fun countFiles(dir: File?): Int {
    if (dir == null || !dir.exists()) return 0
    var count = 0
    dir.walkTopDown().forEach { file ->
      if (file.isFile) count++
    }
    return count
  }
}
