package com.remmi.browser.security

/**
 * Tor-Style Security Level.
 * Decoupled from network routing (PrivacyProfile).
 * Can be applied to SHIELD, GHOST, or INCOGNITO tabs.
 */
enum class SecurityLevel(
  val displayName: String,
  val tag: String,
  val description: String,
  val javascriptEnabled: Boolean,
  val mediaAutoplayAllowed: Boolean,
  val dangerousApisRestricted: Boolean,
  val blockNonHttpsActiveContent: Boolean,
  val maximumFingerprintShield: Boolean
) {
  STANDARD(
    displayName = "STANDARD",
    tag = "DEFAULT",
    description = "JavaScript enabled. Standard tracking and fingerprint protection.",
    javascriptEnabled = true,
    mediaAutoplayAllowed = true,
    dangerousApisRestricted = false,
    blockNonHttpsActiveContent = false,
    maximumFingerprintShield = false
  ),
  SAFER(
    displayName = "SAFER",
    tag = "BALANCED",
    description = "Restricts risky JavaScript APIs, blocks media autoplay, forces HTTPS content, reduces fingerprinting surface.",
    javascriptEnabled = true,
    mediaAutoplayAllowed = false,
    dangerousApisRestricted = true,
    blockNonHttpsActiveContent = true,
    maximumFingerprintShield = true
  ),
  SAFEST(
    displayName = "SAFEST",
    tag = "PARANOID",
    description = "JavaScript disabled by default. Media disabled. Dangerous APIs blocked. Maximum fingerprint reduction.",
    javascriptEnabled = false,
    mediaAutoplayAllowed = false,
    dangerousApisRestricted = true,
    blockNonHttpsActiveContent = true,
    maximumFingerprintShield = true
  )
}
