package com.remmi.browser.security

import android.net.Uri
import android.util.Log
import java.net.InetAddress
import java.net.URI

enum class NavigationDecision {
  ALLOW,
  BLOCK,
  SANITIZE_AND_LOAD,
  REDIRECT_SEARCH
}

data class NavigationCheckResult(
  val decision: NavigationDecision,
  val sanitizedUrl: String? = null,
  val reason: String? = null
)

/**
 * Centralized, authoritative validator and gatekeeper for all user navigation and GeckoSession.loadUri calls.
 * 
 * Strict Enforcement:
 * 1. Blocks dangerous schemes: javascript:, data:, file:, content:, chrome:, resource:, intent:, filesystem:, blob:
 * 2. Blocks loopback (127.0.0.1, localhost), RFC 1918 private subnets, link-local, cloud metadata
 * 3. Enforces HTTPS upgrade (unless valid .onion in Tor mode)
 * 4. In Ghost mode, validates .onion domains and fail-closed verified Tor status.
 */
object NavigationSecurityAuthority {
  private const val TAG = "NavSecurityAuthority"

  private val ALLOWED_SCHEMES = setOf(
    "http", "https", "about"
  )

  fun validateAndSanitizeNavigation(rawUrl: String, isGhost: Boolean = false): NavigationCheckResult {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) {
      return NavigationCheckResult(NavigationDecision.BLOCK, reason = "Empty navigation target")
    }

    if (trimmed.equals("about:blank", ignoreCase = true)) {
      return NavigationCheckResult(NavigationDecision.ALLOW, sanitizedUrl = "about:blank")
    }

    // Check scheme
    val scheme = try {
      val uri = Uri.parse(trimmed)
      uri.scheme?.lowercase() ?: ""
    } catch (_: Exception) {
      ""
    }

    if (scheme.isNotEmpty() && !ALLOWED_SCHEMES.contains(scheme)) {
      Log.w(TAG, "Navigation BLOCKED: dangerous or unsupported scheme '$scheme' in $trimmed")
      return NavigationCheckResult(
        NavigationDecision.BLOCK,
        reason = "Unsupported navigation scheme '$scheme:'"
      )
    }

    // Parse host for SSRF / loopback / private IP filtering
    val host = try {
      val uri = URI(if (trimmed.contains("://")) trimmed else "https://$trimmed")
      uri.host?.lowercase() ?: ""
    } catch (_: Exception) {
      ""
    }

    if (isPrivateOrLocalHost(host, isGhost)) {
      Log.w(TAG, "Navigation BLOCKED: Local/Private address target '$host'")
      return NavigationCheckResult(
        NavigationDecision.BLOCK,
        reason = "Access to local and private network addresses is blocked for security."
      )
    }

    val isOnion = NetworkRouteAuthority.isOnionDestination(trimmed)
    if (isOnion) {
      if (!isGhost || !CurrentTorRoute.isReady) {
        Log.w(TAG, "Navigation BLOCKED: .onion requested but Ghost/Tor is not active/verified for this tab (isGhost=$isGhost, torReady=${CurrentTorRoute.isReady}).")
        return NavigationCheckResult(
          NavigationDecision.BLOCK,
          reason = ".onion hidden services require an active and verified Tor (Ghost) tab session."
        )
      }
    } else if (isGhost) {
      if (!CurrentTorRoute.isReady) {
        Log.w(TAG, "Navigation BLOCKED: Ghost mode requested but Tor is not active/verified (torReady=${CurrentTorRoute.isReady}).")
        return NavigationCheckResult(
          NavigationDecision.BLOCK,
          reason = "Ghost navigation requires an active and verified Tor tab session."
        )
      }
    }

    // Sanitize and upgrade protocol if applicable
    val sanitized = NetworkHardening.sanitizeUrl(trimmed)
    return NavigationCheckResult(NavigationDecision.ALLOW, sanitizedUrl = sanitized)
  }

  fun isPrivateOrLocalHost(host: String, isGhost: Boolean = false): Boolean {
    val clean = host.trim().lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')
    if (clean.isEmpty()) return false

    if (clean == "localhost" || clean.endsWith(".localhost") || clean == "127.0.0.1" || clean == "::1" || clean == "0.0.0.0") {
      return true
    }

    // Check numeric IPv4 literals
    try {
      val parts = clean.split(".")
      if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
        val o0 = parts[0].toInt()
        val o1 = parts[1].toInt()
        if (o0 == 0 || o0 == 10 || o0 == 127) return true
        if (o0 == 169 && o1 == 254) return true // Link-Local / Metadata
        if (o0 == 172 && o1 in 16..31) return true // RFC 1918 Class B
        if (o0 == 192 && o1 == 168) return true // RFC 1918 Class C
        if (o0 == 100 && o1 in 64..127) return true // Carrier-grade NAT
      }
    } catch (_: Exception) {}

    // Check IP literal directly
    try {
      if (android.net.InetAddresses.isNumericAddress(clean.removePrefix("[").removeSuffix("]"))) {
        val addr = InetAddress.getByName(clean.removePrefix("[").removeSuffix("]"))
        val (isSafe, _) = RedirectInspector.isInetAddressSafe(addr)
        if (!isSafe) return true
      }
    } catch (_: Exception) {}

    // Check numeric IPv4 & IPv6 literals and local TLDs without blocking UI thread on synchronous DNS queries
    if (clean.endsWith(".local") || clean.endsWith(".internal") || clean.endsWith(".lan") || clean.endsWith(".home.arpa")) {
      return true
    }
    return false
  }
}
