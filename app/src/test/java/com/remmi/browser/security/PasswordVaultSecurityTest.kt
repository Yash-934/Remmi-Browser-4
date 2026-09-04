package com.remmi.browser.security

import com.remmi.browser.security.crypto.PasswordCryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class PasswordVaultSecurityTest {

  @Test
  fun testPinValidation() {
    assertTrue(PasswordCryptoEngine.isPinValid("749182".toCharArray()))
    assertTrue(PasswordCryptoEngine.isPinValid("93847281".toCharArray()))
    assertFalse(PasswordCryptoEngine.isPinValid("123456".toCharArray())) // trivial sequential pattern
    assertFalse(PasswordCryptoEngine.isPinValid("111111".toCharArray())) // repeating digits
    assertFalse(PasswordCryptoEngine.isPinValid("123".toCharArray())) // too short (< 4 digits)
    assertFalse(PasswordCryptoEngine.isPinValid("74918a".toCharArray())) // non-digit
  }

  @Test
  fun testKeyDerivationAndAesGcmRoundtrip() {
    val masterPassword = "TestMasterPassword!2026".toCharArray()
    val kdfResult = PasswordCryptoEngine.deriveKeyEncryptionKey(masterPassword)

    val testData = "super_sensitive_api_key_42".toByteArray(StandardCharsets.UTF_8)
    val ciphertext = PasswordCryptoEngine.encryptAesGcm(kdfResult.kek, testData)

    val decrypted = PasswordCryptoEngine.decryptAesGcm(kdfResult.kek, ciphertext)
    assertEquals("super_sensitive_api_key_42", String(decrypted, StandardCharsets.UTF_8))
  }

  @Test
  fun testVerifierComputation() {
    val masterPassword = "VaultAccessKey#999".toCharArray()
    val kdfResult = PasswordCryptoEngine.deriveKeyEncryptionKey(masterPassword)

    val (verifier, salt) = PasswordCryptoEngine.computeVerifier(kdfResult.kek)
    assertTrue(PasswordCryptoEngine.verifyKek(kdfResult.kek, verifier, salt))

    val wrongKek = PasswordCryptoEngine.generateSecureRandomBytes(32)
    assertFalse(PasswordCryptoEngine.verifyKek(wrongKek, verifier, salt))
  }

  @Test
  fun testCanonicalHostExtractionAndNormalization() {
    assertEquals("example.com", PasswordCryptoEngine.extractCanonicalHost("https://www.example.com/login"))
    assertEquals("example.com", PasswordCryptoEngine.extractCanonicalHost("http://example.com:8080/path?query=1"))
    assertEquals("example.com", PasswordCryptoEngine.extractCanonicalHost("example.com."))
    assertEquals("example.com", PasswordCryptoEngine.extractCanonicalHost("https://www.example.com."))
    assertEquals("sub.example.com", PasswordCryptoEngine.extractCanonicalHost("https://sub.example.com/"))
    assertEquals("[::1]", PasswordCryptoEngine.extractCanonicalHost("http://[::1]:8080/"))
    assertEquals("192.168.1.1", PasswordCryptoEngine.extractCanonicalHost("http://192.168.1.1:8000/"))

    // IDN normalization
    val idnHost = PasswordCryptoEngine.extractCanonicalHost("https://münchen.de/")
    assertEquals("xn--mnchen-3ya.de", idnHost)
  }

  @Test
  fun testSiteHashingConsistency() {
    val hash1 = PasswordCryptoEngine.hashSiteUrl("https://www.google.com/search")
    val hash2 = PasswordCryptoEngine.hashSiteUrl("http://google.com:443/login")
    val hash3 = PasswordCryptoEngine.hashSiteUrl("google.com.")
    assertEquals(hash1, hash2)
    assertEquals(hash1, hash3)
  }
}
