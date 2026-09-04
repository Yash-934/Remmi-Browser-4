package com.remmi.browser.security.crypto

import com.remmi.browser.storage.PasswordEntryEntity
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class BackupExportPackage(
  val version: Int = 1,
  val isDeviceBound: Boolean,
  val encryptedPayloadB64: String,
  val ivB64: String,
  val authTagB64: String,
  val saltB64: String,
  val hmacB64: String,
  val timestamp: Long = System.currentTimeMillis(),
  val entriesCount: Int,
)

data class RestoredEntry(
  val siteUrlHash: String,
  val siteUrlEncrypted: ByteArray,
  val usernameEncrypted: ByteArray,
  val passwordEncrypted: ByteArray,
  val notesEncrypted: ByteArray,
  val createdAt: Long,
  val updatedAt: Long,
  val iv: ByteArray,
  val authTag: ByteArray,
)

/**
 * Encrypted Backup & Restore Manager for Remmi Password Vault.
 * Provides bank-grade export with Argon2id + AES-256-GCM + HMAC-SHA256 tamper verification.
 */
object PasswordBackupManager {

  private const val BACKUP_HMAC_KEY_SALT = "Remmi_Vault_Backup_HMAC_Integrity"

  fun exportEncryptedBackup(
    entries: List<PasswordEntryEntity>,
    exportPassword: CharArray?, // null for device-bound
    dek: ByteArray,
  ): String {
    // 1. Build plaintext JSON containing decrypted entries re-encrypted for backup
    val jsonEntries = JSONArray()
    for (entry in entries) {
      val obj = JSONObject().apply {
        put("url_hash", entry.siteUrlHash)
        put("url_enc", Base64.getEncoder().encodeToString(entry.siteUrlEncrypted))
        put("user_enc", Base64.getEncoder().encodeToString(entry.usernameEncrypted))
        put("pass_enc", Base64.getEncoder().encodeToString(entry.passwordEncrypted))
        put("notes_enc", Base64.getEncoder().encodeToString(entry.notesEncrypted))
        put("iv", Base64.getEncoder().encodeToString(entry.iv))
        put("tag", Base64.getEncoder().encodeToString(entry.authTag))
        put("created", entry.createdAt)
        put("updated", entry.updatedAt)
      }
      jsonEntries.put(obj)
    }

    val root = JSONObject().apply {
      put("version", 1)
      put("count", entries.size)
      put("timestamp", System.currentTimeMillis())
      put("dek_b64", Base64.getEncoder().encodeToString(dek))
      put("entries", jsonEntries)
    }

    val plaintextPayload = root.toString().toByteArray(StandardCharsets.UTF_8)
    var salt = PasswordCryptoEngine.generateSecureRandomBytes(PasswordCryptoEngine.SALT_LENGTH_BYTES)
    val backupKey = if (exportPassword != null && exportPassword.isNotEmpty()) {
      val kdf = PasswordCryptoEngine.deriveKeyEncryptionKey(exportPassword, salt)
      kdf.kek
    } else {
      // Device-bound fallback
      dek
    }

    try {
      val encrypted = PasswordCryptoEngine.encryptAesGcm(backupKey, plaintextPayload)

      // Compute HMAC-SHA256 over ciphertext + iv + authTag for integrity
      val hmac = computeBackupHmac(backupKey, encrypted.ciphertext, encrypted.iv, encrypted.authTag)

      val exportJson = JSONObject().apply {
        put("remmi_vault_backup", true)
        put("remmi_vault_backup", true)
        put("version", 1)
        put("device_bound", exportPassword == null || exportPassword.isEmpty())
        put("entries_count", entries.size)
        put("timestamp", System.currentTimeMillis())
        put("payload", Base64.getEncoder().encodeToString(encrypted.ciphertext))
        put("iv", Base64.getEncoder().encodeToString(encrypted.iv))
        put("tag", Base64.getEncoder().encodeToString(encrypted.authTag))
        put("salt", Base64.getEncoder().encodeToString(salt))
        put("hmac", Base64.getEncoder().encodeToString(hmac))
      }

      return exportJson.toString(2)
    } finally {
      if (exportPassword != null) {
        PasswordCryptoEngine.zeroize(backupKey)
      }
      PasswordCryptoEngine.zeroize(plaintextPayload)
    }
  }

  fun importEncryptedBackup(
    backupJsonString: String,
    exportPassword: CharArray?,
    currentDek: ByteArray,
  ): Pair<ByteArray, List<RestoredEntry>> {
    val root = JSONObject(backupJsonString)
    if (!root.optBoolean("remmi_vault_backup", false) && !root.optBoolean("remmi_vault_backup", false)) {
      throw IllegalArgumentException("Invalid backup file: Not a Remmi Vault backup.")
    }

    val isDeviceBound = root.getBoolean("device_bound")
    val payloadBytes = Base64.getDecoder().decode(root.getString("payload"))
    val ivBytes = Base64.getDecoder().decode(root.getString("iv"))
    val tagBytes = Base64.getDecoder().decode(root.getString("tag"))
    val saltBytes = Base64.getDecoder().decode(root.getString("salt"))
    val expectedHmacBytes = Base64.getDecoder().decode(root.getString("hmac"))

    val backupKey = if (!isDeviceBound) {
      if (exportPassword == null || exportPassword.isEmpty()) {
        throw IllegalArgumentException("Password required for portable encrypted backup.")
      }
      val kdf = PasswordCryptoEngine.deriveKeyEncryptionKey(exportPassword, saltBytes)
      kdf.kek
    } else {
      currentDek
    }

    try {
      // 1. Verify HMAC integrity first (tamper-proofing)
      val computedHmac = computeBackupHmac(backupKey, payloadBytes, ivBytes, tagBytes)
      if (!MessageDigest.isEqual(computedHmac, expectedHmacBytes)) {
        throw SecurityException("Backup integrity check failed: Tampered or incorrect password.")
      }

      // 2. Decrypt payload
      val decryptedBytes = PasswordCryptoEngine.decryptAesGcm(backupKey, payloadBytes, ivBytes, tagBytes)
      val payloadJson = JSONObject(String(decryptedBytes, StandardCharsets.UTF_8))
      val backupDekB64 = payloadJson.getString("dek_b64")
      val backupDek = Base64.getDecoder().decode(backupDekB64)
      val entriesArray = payloadJson.getJSONArray("entries")

      val restoredList = mutableListOf<RestoredEntry>()
      for (i in 0 until entriesArray.length()) {
        val item = entriesArray.getJSONObject(i)
        restoredList.add(
          RestoredEntry(
            siteUrlHash = item.getString("url_hash"),
            siteUrlEncrypted = Base64.getDecoder().decode(item.getString("url_enc")),
            usernameEncrypted = Base64.getDecoder().decode(item.getString("user_enc")),
            passwordEncrypted = Base64.getDecoder().decode(item.getString("pass_enc")),
            notesEncrypted = Base64.getDecoder().decode(item.getString("notes_enc")),
            createdAt = item.optLong("created", System.currentTimeMillis()),
            updatedAt = item.optLong("updated", System.currentTimeMillis()),
            iv = Base64.getDecoder().decode(item.getString("iv")),
            authTag = Base64.getDecoder().decode(item.getString("tag")),
          )
        )
      }

      return Pair(backupDek, restoredList)
    } finally {
      if (!isDeviceBound) {
        PasswordCryptoEngine.zeroize(backupKey)
      }
    }
  }

  private fun computeBackupHmac(
    key: ByteArray,
    payload: ByteArray,
    iv: ByteArray,
    tag: ByteArray,
  ): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.update(BACKUP_HMAC_KEY_SALT.toByteArray(StandardCharsets.UTF_8))
    mac.update(iv)
    mac.update(tag)
    return mac.doFinal(payload)
  }
}
