package com.remmi.browser.security.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ln

data class MasterKeyDerivationResult(
  val kek: ByteArray, // Key Encryption Key (32 bytes)
  val salt: ByteArray, // 16 bytes
  val paramsDescription: String,
)

data class AesGcmCiphertext(
  val ciphertext: ByteArray,
  val iv: ByteArray,
  val authTag: ByteArray,
)

data class MasterPasswordStrength(
  val score: Int, // 0..100
  val hasMinLength: Boolean,
  val hasUppercase: Boolean,
  val hasLowercase: Boolean,
  val hasDigit: Boolean,
  val hasSymbol: Boolean,
  val feedback: String,
)

data class DecryptedPasswordEntry(
  val id: Long,
  val url: String,
  val username: String,
  val password: String,
  val notes: String,
  val createdAt: Long,
  val updatedAt: Long,
)

/**
 * Bank-Grade, Maximum-Security Password Crypto Engine for Remmi.
 * Implements Argon2id KDF (64 MiB, 3 iter, 1 parallelism), AES-256-GCM, HMAC-SHA256 verifier,
 * Android Keystore Biometric wrapping, and zero-knowledge memory wiping.
 */
object PasswordCryptoEngine {
  private const val ANDROID_KEYSTORE = "AndroidKeyStore"
  const val BIOMETRIC_KEY_ALIAS = "Remmi_PM_Biometric_DEK_Wrapper"

  // Argon2id Parameters as mandated
  const val ARGON2_MEMORY_KB = 65536 // 64 MiB
  const val ARGON2_ITERATIONS = 3
  const val ARGON2_PARALLELISM = 1
  const val KEY_LENGTH_BYTES = 32 // 256-bit
  const val SALT_LENGTH_BYTES = 16
  const val IV_LENGTH_BYTES = 12
  const val AUTH_TAG_LENGTH_BYTES = 16

  private val secureRandom = SecureRandom()

  // --- 1. Master Password Strength Evaluation ---
  fun evaluatePasswordStrength(password: CharArray): MasterPasswordStrength {
    val length = password.size
    var hasUpper = false
    var hasLower = false
    var hasDigit = false
    var hasSymbol = false

    val symbols = "!@#$%^&*()_+-=[]{}|;:,.<>?/~`"

    for (c in password) {
      when {
        c.isUpperCase() -> hasUpper = true
        c.isLowerCase() -> hasLower = true
        c.isDigit() -> hasDigit = true
        symbols.contains(c) || !c.isLetterOrDigit() -> hasSymbol = true
      }
    }

    var poolSize = 0
    if (hasLower) poolSize += 26
    if (hasUpper) poolSize += 26
    if (hasDigit) poolSize += 10
    if (hasSymbol) poolSize += 33

    val entropy = if (length > 0 && poolSize > 0) {
      length * (ln(poolSize.toDouble()) / ln(2.0))
    } else 0.0

    val score = (entropy / 1.2).toInt().coerceIn(0, 100)
    val hasMinLength = length >= 12

    val feedback = when {
      !hasMinLength -> "Minimum 12 characters required for hardened security"
      !(hasUpper && hasLower && hasDigit && hasSymbol) -> "Include uppercase, lowercase, numbers, and symbols"
      score >= 80 -> "High-Assurance Security (Strong Entropy)"
      score >= 60 -> "Good Security"
      else -> "Moderate Security"
    }

    return MasterPasswordStrength(
      score = score,
      hasMinLength = hasMinLength,
      hasUppercase = hasUpper,
      hasLowercase = hasLower,
      hasDigit = hasDigit,
      hasSymbol = hasSymbol,
      feedback = feedback,
    )
  }

  fun isPasswordValid(password: CharArray): Boolean {
    val strength = evaluatePasswordStrength(password)
    return strength.hasMinLength && strength.hasUppercase && strength.hasLowercase && strength.hasDigit && strength.hasSymbol
  }

  // --- 1B. Master PIN Strength & Validation ---
  fun evaluatePinStrength(pin: CharArray): Pair<Boolean, String> {
    if (pin.size < 4) {
      return Pair(false, "PIN must be at least 4 digits")
    }
    if (pin.size > 12) {
      return Pair(false, "PIN cannot exceed 12 digits")
    }
    for (c in pin) {
      if (!c.isDigit()) {
        return Pair(false, "PIN must contain only digits")
      }
    }
    // Check if all digits are the same without creating long-lived strings
    val first = pin[0]
    var allSame = true
    for (i in 1 until pin.size) {
      if (pin[i] != first) {
        allSame = false
        break
      }
    }
    if (allSame) {
      return Pair(false, "PIN cannot consist of a single repeated digit.")
    }

    // Check sequential ascending/descending
    var isAscending = true
    var isDescending = true
    for (i in 0 until pin.size - 1) {
      if (pin[i + 1] != pin[i] + 1) isAscending = false
      if (pin[i + 1] != pin[i] - 1) isDescending = false
    }
    if (isAscending || isDescending) {
      return Pair(false, "PIN is too predictable. Avoid sequential numbers.")
    }

    return Pair(true, if (pin.size >= 8) "Strong PIN" else "Valid PIN")
  }

  fun isPinValid(pin: CharArray): Boolean {
    return evaluatePinStrength(pin).first
  }

  // --- 2. Key Derivation with Argon2id (for Password or PIN) ---
  fun deriveKeyEncryptionKey(
    password: CharArray,
    salt: ByteArray = generateSecureRandomBytes(SALT_LENGTH_BYTES),
  ): MasterKeyDerivationResult {
    val passwordBytes = charArrayToUtf8Bytes(password)
    val outputKek = ByteArray(KEY_LENGTH_BYTES)

    try {
      val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withMemoryAsKB(ARGON2_MEMORY_KB)
        .withIterations(ARGON2_ITERATIONS)
        .withParallelism(ARGON2_PARALLELISM)
        .withSalt(salt)
        .build()

      val generator = Argon2BytesGenerator()
      generator.init(params)
      generator.generateBytes(passwordBytes, outputKek, 0, KEY_LENGTH_BYTES)

      return MasterKeyDerivationResult(
        kek = outputKek,
        salt = salt,
        paramsDescription = "argon2id;m=$ARGON2_MEMORY_KB,t=$ARGON2_ITERATIONS,p=$ARGON2_PARALLELISM",
      )
    } finally {
      zeroize(passwordBytes)
    }
  }

  // --- 3. Master Password Verifier (HMAC-SHA256) ---
  fun computeVerifier(
    kek: ByteArray,
    verifierSalt: ByteArray = generateSecureRandomBytes(SALT_LENGTH_BYTES),
  ): Pair<ByteArray, ByteArray> {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(kek, "HmacSHA256"))
    val verifier = mac.doFinal(verifierSalt)
    return Pair(verifier, verifierSalt)
  }

  fun verifyKek(kek: ByteArray, expectedVerifier: ByteArray, verifierSalt: ByteArray): Boolean {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(kek, "HmacSHA256"))
    val computed = mac.doFinal(verifierSalt)
    return MessageDigest.isEqual(computed, expectedVerifier)
  }

  // --- 4. AES-256-GCM Core Primitives ---
  fun encryptAesGcm(key: ByteArray, plaintext: ByteArray): AesGcmCiphertext {
    val iv = generateSecureRandomBytes(IV_LENGTH_BYTES)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val secretKey = SecretKeySpec(key, "AES")
    val gcmSpec = GCMParameterSpec(AUTH_TAG_LENGTH_BYTES * 8, iv)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

    val fullCiphertext = cipher.doFinal(plaintext)
    // In Java JCE, the last 16 bytes of fullCiphertext is the GCM Authentication Tag
    val tagOffset = fullCiphertext.size - AUTH_TAG_LENGTH_BYTES
    val rawCiphertext = fullCiphertext.copyOfRange(0, tagOffset)
    val authTag = fullCiphertext.copyOfRange(tagOffset, fullCiphertext.size)

    return AesGcmCiphertext(
      ciphertext = rawCiphertext,
      iv = iv,
      authTag = authTag,
    )
  }

  fun decryptAesGcm(key: ByteArray, encrypted: AesGcmCiphertext): ByteArray {
    return decryptAesGcm(key, encrypted.ciphertext, encrypted.iv, encrypted.authTag)
  }

  fun decryptAesGcm(key: ByteArray, ciphertext: ByteArray, iv: ByteArray, authTag: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val secretKey = SecretKeySpec(key, "AES")
    val gcmSpec = GCMParameterSpec(AUTH_TAG_LENGTH_BYTES * 8, iv)
    cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

    // Recombine ciphertext + authTag for JCE GCM
    val combined = ByteArray(ciphertext.size + authTag.size)
    System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
    System.arraycopy(authTag, 0, combined, ciphertext.size, authTag.size)

    return cipher.doFinal(combined)
  }

  /**
   * Encrypts plaintext and packs the unique IV + AuthTag + Ciphertext into a single contiguous BLOB.
   * Format: [12-byte IV] + [16-byte AuthTag] + [Ciphertext]
   * Ensures distinct IVs per field without GCM IV-reuse hazards.
   */
  fun encryptAesGcmPacked(key: ByteArray, plaintext: ByteArray): ByteArray {
    val enc = encryptAesGcm(key, plaintext)
    val packed = ByteArray(IV_LENGTH_BYTES + AUTH_TAG_LENGTH_BYTES + enc.ciphertext.size)
    System.arraycopy(enc.iv, 0, packed, 0, IV_LENGTH_BYTES)
    System.arraycopy(enc.authTag, 0, packed, IV_LENGTH_BYTES, AUTH_TAG_LENGTH_BYTES)
    System.arraycopy(enc.ciphertext, 0, packed, IV_LENGTH_BYTES + AUTH_TAG_LENGTH_BYTES, enc.ciphertext.size)
    return packed
  }

  /**
   * Decrypts a packed AES-GCM blob containing its own IV and AuthTag, with fallback to external IV/tag for legacy records.
   */
  fun decryptAesGcmPacked(
    key: ByteArray,
    blob: ByteArray,
    fallbackIv: ByteArray? = null,
    fallbackAuthTag: ByteArray? = null,
  ): ByteArray {
    if (blob.size >= IV_LENGTH_BYTES + AUTH_TAG_LENGTH_BYTES) {
      try {
        val iv = blob.copyOfRange(0, IV_LENGTH_BYTES)
        val authTag = blob.copyOfRange(IV_LENGTH_BYTES, IV_LENGTH_BYTES + AUTH_TAG_LENGTH_BYTES)
        val ciphertext = blob.copyOfRange(IV_LENGTH_BYTES + AUTH_TAG_LENGTH_BYTES, blob.size)
        return decryptAesGcm(key, ciphertext, iv, authTag)
      } catch (e: Exception) {
        if (fallbackIv != null && fallbackAuthTag != null) {
          return decryptAesGcm(key, blob, fallbackIv, fallbackAuthTag)
        }
        throw e
      }
    }
    if (fallbackIv != null && fallbackAuthTag != null) {
      return decryptAesGcm(key, blob, fallbackIv, fallbackAuthTag)
    }
    throw IllegalArgumentException("Invalid AES-GCM payload format")
  }

  // --- 5. Canonical Site Hash for Autofill Matching ---
  fun hashSiteUrl(url: String): String {
    val host = extractCanonicalHost(url)
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(host.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }

  fun extractCanonicalHost(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return ""

    val normalizedUrl = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val parsedHost = try {
      val uri = java.net.URI(normalizedUrl)
      uri.host ?: android.net.Uri.parse(normalizedUrl).host ?: ""
    } catch (_: Exception) {
      try {
        android.net.Uri.parse(normalizedUrl).host ?: ""
      } catch (_: Exception) {
        val withoutScheme = trimmed.substringAfter("://")
        withoutScheme.substringBefore('/').substringBefore(':')
      }
    }

    var host = parsedHost.trim().lowercase()
    if (host.startsWith("[") && host.endsWith("]")) {
      // IPv6 literal
      return host
    }

    // Strip trailing dots
    host = host.trimEnd('.')

    // IDN Punycode normalization
    host = try {
      java.net.IDN.toASCII(host).lowercase()
    } catch (_: Exception) {
      host
    }

    // Remove leading www.
    if (host.startsWith("www.")) {
      host = host.removePrefix("www.")
    }

    return host
  }

  // --- 6. Hardware Keystore Biometric Key Operations ---
  fun getOrCreateBiometricKeystoreKey(forceRecreate: Boolean = false): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    if (!forceRecreate && keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) {
      try {
        val existing = keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
      } catch (e: Exception) {
        keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
      }
    } else if (keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) {
      keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
    }

    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT

    val builder = KeyGenParameterSpec.Builder(BIOMETRIC_KEY_ALIAS, purposes)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
    } else {
      @Suppress("DEPRECATION")
      builder.setUserAuthenticationRequired(true)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      builder.setInvalidatedByBiometricEnrollment(true)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      try {
        builder.setIsStrongBoxBacked(true)
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
      } catch (e: Exception) {
        // Fallback to normal TEE Keystore if StrongBox is unavailable on device
        builder.setIsStrongBoxBacked(false)
      }
    }

    keyGenerator.init(builder.build())
    return keyGenerator.generateKey()
  }

  fun wrapDekWithBiometric(dek: ByteArray, biometricCipher: Cipher): AesGcmCiphertext {
    val fullCiphertext = biometricCipher.doFinal(dek)
    val iv = biometricCipher.iv
    val tagOffset = fullCiphertext.size - AUTH_TAG_LENGTH_BYTES
    val rawCiphertext = fullCiphertext.copyOfRange(0, tagOffset)
    val authTag = fullCiphertext.copyOfRange(tagOffset, fullCiphertext.size)

    return AesGcmCiphertext(
      ciphertext = rawCiphertext,
      iv = iv,
      authTag = authTag,
    )
  }

  fun unwrapDekWithBiometric(
    biometricCipher: Cipher,
    ciphertext: ByteArray,
    authTag: ByteArray,
  ): ByteArray {
    val combined = ByteArray(ciphertext.size + authTag.size)
    System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
    System.arraycopy(authTag, 0, combined, ciphertext.size, authTag.size)
    return biometricCipher.doFinal(combined)
  }

  // --- 7. Maximum-Entropy Password Generator ---
  fun generatePassword(
    length: Int = 24,
    includeUpper: Boolean = true,
    includeLower: Boolean = true,
    includeDigits: Boolean = true,
    includeSymbols: Boolean = true,
    excludeAmbiguous: Boolean = false,
  ): String {
    val upperChars = if (excludeAmbiguous) "ABCDEFGHJKLMNPQRSTUVWXYZ" else "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val lowerChars = if (excludeAmbiguous) "abcdefghijkmnopqrstuvwxyz" else "abcdefghijklmnopqrstuvwxyz"
    val digitChars = if (excludeAmbiguous) "23456789" else "0123456789"
    val symbolChars = "!@#$%^&*()_+-=[]{}|;:,.<>?"

    val pool = StringBuilder()
    val mandatory = mutableListOf<Char>()

    if (includeUpper) {
      pool.append(upperChars)
      mandatory.add(upperChars[secureRandom.nextInt(upperChars.length)])
    }
    if (includeLower) {
      pool.append(lowerChars)
      mandatory.add(lowerChars[secureRandom.nextInt(lowerChars.length)])
    }
    if (includeDigits) {
      pool.append(digitChars)
      mandatory.add(digitChars[secureRandom.nextInt(digitChars.length)])
    }
    if (includeSymbols) {
      pool.append(symbolChars)
      mandatory.add(symbolChars[secureRandom.nextInt(symbolChars.length)])
    }

    if (pool.isEmpty()) {
      pool.append(lowerChars).append(digitChars)
    }

    val poolStr = pool.toString()
    val result = ArrayList<Char>(length)
    result.addAll(mandatory)

    while (result.size < length) {
      result.add(poolStr[secureRandom.nextInt(poolStr.length)])
    }

    // Fisher-Yates shuffle using SecureRandom
    for (i in result.indices.reversed()) {
      val j = secureRandom.nextInt(i + 1)
      val temp = result[i]
      result[i] = result[j]
      result[j] = temp
    }

    return result.joinToString("")
  }

  // --- 8. Secure Memory Utilities ---
  fun deleteBiometricKeystoreKey(): Boolean {
    return try {
      val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
      keyStore.load(null)
      if (keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) {
        keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
      }
      !keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)
    } catch (e: Exception) {
      Log.w("PasswordCryptoEngine", "Failed to delete biometric Keystore key: ${e.message}")
      false
    }
  }

  fun generateSecureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    secureRandom.nextBytes(bytes)
    return bytes
  }

  fun zeroize(buffer: ByteArray?) {
    if (buffer != null) {
      Arrays.fill(buffer, 0.toByte())
    }
  }

  fun zeroize(buffer: CharArray?) {
    if (buffer != null) {
      Arrays.fill(buffer, '\u0000')
    }
  }

  fun charArrayToUtf8Bytes(chars: CharArray): ByteArray {
    val byteBuffer = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
    val bytes = ByteArray(byteBuffer.remaining())
    byteBuffer.get(bytes)
    return bytes
  }
}
