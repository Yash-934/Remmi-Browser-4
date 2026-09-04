package com.remmi.browser.security

enum class PrivacyProfile(
  val displayName: String,
  val tag: String,
  val description: String,
) {
  SHIELD(
    displayName = "SHIELD MODE",
    tag = "FPP + dFPI",
    description = "Granular Fingerprint Protection, Dynamic First-Party Isolation, WebRTC Off, HTTPS-Only direct connection.",
  ),
  GHOST(
    displayName = "GHOST MODE",
    tag = "TOR + FULL RFP",
    description = "Full Tor Onion Routing, SOCKS5 remote DNS, uniform spoofed Tor UA, canvas letterboxing, UTC timezone, hardware concurrency 2.",
  ),
  INCOGNITO(
    displayName = "INCOGNITO MODE",
    tag = "PRIVATE",
    description = "Private browsing session. Leaves no trace on your device.",
  );

  val isTorEnabled: Boolean
    get() = this == GHOST
}
