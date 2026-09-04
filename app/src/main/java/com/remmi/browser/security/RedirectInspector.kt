package com.remmi.browser.security

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

enum class RedirectResolutionStatus {
  RESOLVED,
  PARTIAL,
  UNRESOLVED,
  TIMEOUT,
  TOR_ROUTE_LOST,
  LOOP_DETECTED,
  SSRF_BLOCKED,
  UNSUPPORTED_SCHEME,
  NETWORK_ERROR
}

enum class SecurityRiskLevel {
  LOW,
  MEDIUM,
  HIGH,
  BLOCKED
}

data class RedirectHop(
  val step: Int,
  val url: String,
  val statusCode: Int,
  val statusMessage: String,
  val locationHeader: String?,
  val domain: String,
  val redirectType: String = "HTTP", // "HTTP", "META_REFRESH", "JAVASCRIPT", "SSRF_BLOCKED", "LOOP", etc.
)

data class RedirectInspectionResult(
  val originalUrl: String,
  val finalUrl: String?,
  val status: RedirectResolutionStatus,
  val hops: List<RedirectHop>,
  val hasTrackingParams: Boolean,
  val strippedUrl: String,
  val isSecure: Boolean,
  val safetyScore: Int = 100, // 0..100
  val riskLevel: SecurityRiskLevel = SecurityRiskLevel.LOW,
  val securityInsights: List<String> = emptyList(),
  val error: String? = null,
  val extractedNestedUrl: String? = null,
  val requestedUrl: String? = null,
  val actualBrowserLandedUrl: String? = null,
)

/**
 * Deep Link & Redirect Transparency Analyzer.
 * Traces multi-hop HTTP 301/302/303/307/308 redirects, discovers HTML <meta http-equiv="refresh"> and inline JS redirects,
 * strips invasive tracking tokens, prevents SSRF / DNS rebinding attacks across IPv4 and IPv6 subnets,
 * enforces Tor SOCKS proxy isolation in Ghost Mode with fail-closed semantics, and calculates security risk.
 */
object RedirectInspector {
  private const val TAG = "RedirectInspector"
  private const val MAX_HOPS = 15
  private const val MAX_RESPONSE_BODY_BYTES = 512L * 1024L // 512 KB cap to prevent memory exhaustion

  private val TRACKING_PARAMS = setOf(
    "fbclid", "gclid", "dclid", "msclkid", "mc_eid", "mc_cid", "yclid", "igshid",
    "_hsenc", "_hsmi", "ref", "affiliate", "partner", "aff", "tracking_id",
    "s_kwcid", "gclsrc", "sc_eid", "vero_id", "wickedid", "irclickid", "twclid",
    "zanpid", "ndclid", "wbraid", "gbraid", "_ga", "_gl"
  )

  /**
   * Validates if a URL scheme is safe for browser navigation.
   * Rejects dangerous/privileged schemes: javascript:, data:, file:, content:, chrome:, resource:, about:, intent:, blob:, filesystem:
   */
  fun isSchemeSafeForNavigation(url: String): Boolean {
    val clean = url.trim().lowercase()
    if (clean.startsWith("javascript:") || clean.startsWith("data:") || clean.startsWith("file:") ||
      clean.startsWith("content:") || clean.startsWith("chrome:") || clean.startsWith("resource:") ||
      clean.startsWith("intent:") || clean.startsWith("blob:") || clean.startsWith("filesystem:")
    ) {
      return false
    }
    if (clean.startsWith("about:") && clean != "about:blank") {
      return false
    }
    return clean.startsWith("http://") || clean.startsWith("https://") || clean == "about:blank"
  }

  private val META_REFRESH_PATTERN = Pattern.compile(
    """<meta\s+[^>]*http-equiv\s*=\s*["']?refresh["']?[^>]*content\s*=\s*["']?\d+\s*;\s*url=([^"'>\s]+)["']?""",
    Pattern.CASE_INSENSITIVE
  )

  private val JS_REDIRECT_PATTERNS = listOf(
    Pattern.compile("""(?:window\.)?location(?:\.href|\.replace|\.assign)?\s*(?:=|\()\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE),
    Pattern.compile("""(?:window\["location"\]|location)\["(?:href|replace|assign)"\]\s*(?:=|\()\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE),
    Pattern.compile("""window\.open\s*\(\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
  )

  private val SUSPICIOUS_TLDS = setOf(
    "zip", "mov", "xyz", "top", "tk", "work", "click", "link", "gq", "cf", "ml", "ga"
  )

  private val SHORTENER_DOMAINS = setOf(
    "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "buff.ly", "ow.ly", "adf.ly", "bit.do"
  )

  /**
   * DNS Rebinding Protection:
   * Inspects every resolved IP before establishing TCP connection.
   * Prohibits loopback, RFC 1918 private subnets, link-local, multicast, and carrier-grade NAT.
   */
  private val antiRebindingDns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
      val addresses = Dns.SYSTEM.lookup(hostname)
      for (addr in addresses) {
        val (isSafe, reason) = isInetAddressSafe(addr)
        if (!isSafe) {
          Log.w(TAG, "[DNS REBINDING DETECTED] Host '$hostname' resolved to prohibited address '${addr.hostAddress}': $reason")
          throw SecurityException("SSRF / DNS Rebinding Shield: Prohibited IP address '${addr.hostAddress}' ($reason)")
        }
      }
      return addresses
    }
  }

  suspend fun inspectUrl(
    url: String,
    isGhost: Boolean = false,
    socksPort: Int? = CurrentTorRoute.currentSocksPort,
    expectedGeneration: Long = CurrentTorRoute.currentGeneration,
    actualBrowserLandedUrl: String? = null
  ): RedirectInspectionResult = withContext(Dispatchers.IO) {
    val hops = mutableListOf<RedirectHop>()
    var currentUrl = if (url.contains("://") || url.contains(":")) url else "https://$url"
    val originalUrl = currentUrl
    val extractedNested = extractNestedTargetUrl(originalUrl)
    val strippedOriginal = stripTrackingParameters(originalUrl)
    var hopCount = 0
    var inspectionError: String? = null
    var resolutionStatus = RedirectResolutionStatus.RESOLVED
    val insights = mutableListOf<String>()
    val visitedUrls = mutableSetOf<String>()
    val visitedDomains = mutableSetOf<String>()

    // Check scheme validity up front
    if (!currentUrl.startsWith("http://", ignoreCase = true) && !currentUrl.startsWith("https://", ignoreCase = true)) {
      return@withContext RedirectInspectionResult(
        originalUrl = originalUrl,
        finalUrl = null,
        status = RedirectResolutionStatus.UNSUPPORTED_SCHEME,
        hops = emptyList(),
        hasTrackingParams = false,
        strippedUrl = originalUrl,
        isSecure = false,
        safetyScore = 0,
        riskLevel = SecurityRiskLevel.BLOCKED,
        securityInsights = listOf("Unsupported URL scheme. Only HTTP and HTTPS are permitted for security."),
        error = "Unsupported URL scheme",
        extractedNestedUrl = extractedNested,
        actualBrowserLandedUrl = actualBrowserLandedUrl
      )
    }

    // Enforce Ghost Mode Tor routing strictly (Fail-Closed) to prevent clearnet ISP leak
    if (isGhost) {
      if (socksPort == null || socksPort <= 0 || CurrentTorRoute.currentSocksPort == null || CurrentTorRoute.currentSocksPort != socksPort) {
        return@withContext RedirectInspectionResult(
          originalUrl = originalUrl,
          finalUrl = null,
          status = RedirectResolutionStatus.TOR_ROUTE_LOST,
          hops = emptyList(),
          hasTrackingParams = hasTrackingParams(originalUrl),
          strippedUrl = strippedOriginal,
          isSecure = originalUrl.startsWith("https://", ignoreCase = true),
          safetyScore = 0,
          riskLevel = SecurityRiskLevel.BLOCKED,
          securityInsights = listOf("Ghost Mode requires active Tor proxy. Analysis blocked to prevent ISP IP leak."),
          error = "Tor routing required in Ghost Mode. Clearnet inspection prevented.",
          extractedNestedUrl = extractedNested,
          actualBrowserLandedUrl = actualBrowserLandedUrl
        )
      }
    }

    val client = try {
      NetworkRouteAuthority.createHttpClient(
        isGhost = isGhost,
        targetUrl = originalUrl,
        connectTimeoutSeconds = 5L,
        readTimeoutSeconds = 5L,
        followRedirects = false
      ).newBuilder()
        .followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS)
        .apply {
          if (!isGhost) {
            dns(antiRebindingDns)
          }
        }
        .build()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to create authorized client for redirect inspection: ${e.message}")
      return@withContext RedirectInspectionResult(
        originalUrl = originalUrl,
        finalUrl = null,
        status = RedirectResolutionStatus.TOR_ROUTE_LOST,
        hops = emptyList(),
        hasTrackingParams = hasTrackingParams(originalUrl),
        strippedUrl = strippedOriginal,
        isSecure = originalUrl.startsWith("https://", ignoreCase = true),
        safetyScore = 0,
        riskLevel = SecurityRiskLevel.BLOCKED,
        securityInsights = listOf("Failed to bind to network authority: ${e.message}"),
        error = "Network routing failure: ${e.message}",
        extractedNestedUrl = extractedNested,
        actualBrowserLandedUrl = actualBrowserLandedUrl
      )
    }

    while (hopCount < MAX_HOPS) {
      hopCount++

      // Re-verify Tor Route invariant before EVERY hop in Ghost Mode
      if (isGhost) {
        val currentActivePort = CurrentTorRoute.currentSocksPort
        val currentGen = CurrentTorRoute.currentGeneration
        val isReady = CurrentTorRoute.isReady
        if (!isReady || currentActivePort == null || currentActivePort <= 0 || currentActivePort != socksPort || currentGen != expectedGeneration) {
          Log.w(TAG, "[GHOST ROUTE LOST] Route terminated or generation changed mid-chain")
          inspectionError = "Tor SOCKS route lost or refreshed during redirect chain"
          resolutionStatus = RedirectResolutionStatus.TOR_ROUTE_LOST
          insights.add("Tor routing changed or disconnected mid-chain. Inspection stopped to prevent leak.")
          break
        }
      }

      // 1. SSRF & Protocol Safety Gate
      val (isSafe, ssrfReason) = isTargetSafeForInspection(currentUrl)
      if (!isSafe) {
        inspectionError = ssrfReason
        resolutionStatus = RedirectResolutionStatus.SSRF_BLOCKED
        hops.add(
          RedirectHop(
            step = hopCount,
            url = currentUrl,
            statusCode = 403,
            statusMessage = "Blocked by SSRF Shield ($ssrfReason)",
            locationHeader = null,
            domain = extractDomain(currentUrl),
            redirectType = "SSRF_BLOCKED"
          )
        )
        insights.add("Security Shield blocked prohibited destination: $ssrfReason")
        break
      }

      // 2. Loop Detection
      if (visitedUrls.contains(currentUrl)) {
        insights.add("Detected infinite circular redirect loop back to: $currentUrl")
        resolutionStatus = RedirectResolutionStatus.LOOP_DETECTED
        hops.add(
          RedirectHop(
            step = hopCount,
            url = currentUrl,
            statusCode = 310,
            statusMessage = "Circular Redirect Loop Detected",
            locationHeader = null,
            domain = extractDomain(currentUrl),
            redirectType = "LOOP"
          )
        )
        break
      }
      visitedUrls.add(currentUrl)

      val domain = extractDomain(currentUrl)
      visitedDomains.add(domain)

      val request = try {
        Request.Builder()
          .url(currentUrl)
          .header("User-Agent", "Mozilla/5.0 (Android 16; Mobile; rv:134.0) Gecko/134.0 Firefox/134.0")
          .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
          .build()
      } catch (e: Exception) {
        inspectionError = "Invalid URL format: ${e.message}"
        resolutionStatus = RedirectResolutionStatus.NETWORK_ERROR
        break
      }

      var response: Response? = null
      try {
        response = client.newCall(request).execute()
        val statusCode = response.code
        val statusMessage = response.message.ifEmpty { "HTTP $statusCode" }
        val locationHeader = response.header("Location")
        val contentType = response.header("Content-Type")?.lowercase() ?: ""

        // Check if content type is inspectable HTML/text
        val isHtmlOrText = contentType.isBlank() || contentType.contains("text/html") ||
          contentType.contains("application/xhtml+xml") || contentType.contains("text/plain")

        // Read bounded response body only for HTML pages to prevent decompression bombs
        val responseBody = if (isHtmlOrText) {
          response.body?.source()?.let { source ->
            try {
              source.request(MAX_RESPONSE_BODY_BYTES)
              val buffer = source.buffer
              val toRead = minOf(buffer.size, MAX_RESPONSE_BODY_BYTES)
              buffer.clone().readUtf8(toRead)
            } catch (_: Exception) {
              ""
            }
          }.orEmpty()
        } else {
          ""
        }

        // 1. Check for standard HTTP 301, 302, 303, 307, 308 redirects
        if (statusCode in listOf(301, 302, 303, 307, 308) && !locationHeader.isNullOrBlank()) {
          hops.add(
            RedirectHop(
              step = hopCount,
              url = currentUrl,
              statusCode = statusCode,
              statusMessage = statusMessage,
              locationHeader = locationHeader,
              domain = domain,
              redirectType = "HTTP_REDIRECT_RESOLVED"
            )
          )

          val nextUrl = resolveNextUrl(currentUrl, domain, locationHeader)
          if (nextUrl == currentUrl) {
            resolutionStatus = RedirectResolutionStatus.LOOP_DETECTED
            break
          }
          currentUrl = nextUrl
          continue
        }

        // 2. Check for HTML <meta http-equiv="refresh"> redirect
        val metaMatcher = META_REFRESH_PATTERN.matcher(responseBody)
        if (metaMatcher.find()) {
          val targetMetaUrl = metaMatcher.group(1)?.trim().orEmpty()
          if (targetMetaUrl.isNotBlank()) {
            hops.add(
              RedirectHop(
                step = hopCount,
                url = currentUrl,
                statusCode = statusCode,
                statusMessage = "Meta-Refresh Redirect",
                locationHeader = targetMetaUrl,
                domain = domain,
                redirectType = "META_REFRESH_DETECTED"
              )
            )
            val nextUrl = resolveNextUrl(currentUrl, domain, targetMetaUrl)
            if (nextUrl != currentUrl) {
              currentUrl = nextUrl
              insights.add("Discovered hidden HTML <meta> refresh redirect")
              continue
            }
          }
        }

        // 3. Check for Inline JavaScript redirects
        var foundJsRedirect = false
        for (jsPattern in JS_REDIRECT_PATTERNS) {
          val jsMatcher = jsPattern.matcher(responseBody)
          if (jsMatcher.find()) {
            val targetJsUrl = jsMatcher.group(1)?.trim().orEmpty()
            if (targetJsUrl.isNotBlank() && (targetJsUrl.startsWith("http") || targetJsUrl.startsWith("/"))) {
              hops.add(
                RedirectHop(
                  step = hopCount,
                  url = currentUrl,
                  statusCode = statusCode,
                  statusMessage = "JavaScript Redirect",
                  locationHeader = targetJsUrl,
                  domain = domain,
                  redirectType = "STATIC_REDIRECT_DETECTED"
                )
              )
              val nextUrl = resolveNextUrl(currentUrl, domain, targetJsUrl)
              if (nextUrl != currentUrl) {
                currentUrl = nextUrl
                insights.add("Discovered client-side JavaScript redirect")
                foundJsRedirect = true
                break
              }
            }
          }
        }
        if (foundJsRedirect) continue

        // Reached terminal destination
        hops.add(
          RedirectHop(
            step = hopCount,
            url = currentUrl,
            statusCode = statusCode,
            statusMessage = statusMessage,
            locationHeader = null,
            domain = domain,
            redirectType = "TERMINAL"
          )
        )
        resolutionStatus = RedirectResolutionStatus.RESOLVED
        break

      } catch (e: Exception) {
        Log.w(TAG, "Redirect inspection hop error at $currentUrl: ${e.message}")
        val isTimeout = e is java.net.SocketTimeoutException || e.message?.contains("timeout", ignoreCase = true) == true
        resolutionStatus = if (isTimeout) RedirectResolutionStatus.TIMEOUT else RedirectResolutionStatus.NETWORK_ERROR
        inspectionError = e.message ?: "Connection error"
        hops.add(
          RedirectHop(
            step = hopCount,
            url = currentUrl,
            statusCode = 0,
            statusMessage = if (isTimeout) "Connection Timeout" else "Unreachable / Error (${e.javaClass.simpleName})",
            locationHeader = null,
            domain = domain,
            redirectType = if (isTimeout) "TIMEOUT" else "ERROR"
          )
        )
        break
      } finally {
        try { response?.close() } catch (_: Exception) {}
      }
    }

    if (hopCount >= MAX_HOPS && resolutionStatus == RedirectResolutionStatus.RESOLVED && hops.lastOrNull()?.redirectType != "TERMINAL") {
      resolutionStatus = RedirectResolutionStatus.PARTIAL
      insights.add("Exceeded maximum limit of $MAX_HOPS redirect hops")
    }

    val finalDestinationUrl = if (resolutionStatus == RedirectResolutionStatus.RESOLVED) currentUrl else null
    val effectiveUrlForStripping = finalDestinationUrl ?: originalUrl
    val strippedUrl = stripTrackingParameters(effectiveUrlForStripping)
    val hasTracking = (finalDestinationUrl != null && finalDestinationUrl != strippedUrl) || hasTrackingParams(originalUrl)

    // Security Insights & Risk Evaluation
    if (hasTracking) {
      insights.add("Identified and stripped invasive tracking query parameters")
    }
    if (hops.size > 3) {
      insights.add("Extended redirect chain (${hops.size} hops) detected")
    }
    if (visitedDomains.size > 2) {
      insights.add("Cross-domain redirect chain across ${visitedDomains.size} different origins")
    }
    if (originalUrl.startsWith("https://", ignoreCase = true) && finalDestinationUrl?.startsWith("http://", ignoreCase = true) == true) {
      insights.add("CRITICAL: Connection downgraded from HTTPS to plaintext HTTP")
    }
    if (SHORTENER_DOMAINS.contains(extractDomain(originalUrl).lowercase())) {
      insights.add("URL shortener service identified at origin")
    }
    val finalDomain = finalDestinationUrl?.let { extractDomain(it) } ?: extractDomain(originalUrl)
    val tld = finalDomain.substringAfterLast('.', "")
    if (SUSPICIOUS_TLDS.contains(tld.lowercase())) {
      insights.add("Suspicious top-level domain (.$tld) detected")
    }
    if (finalDomain.startsWith("xn--")) {
      insights.add("Internationalized Domain Name (Punycode homograph candidate): $finalDomain")
    }

    // Calculate Risk Score & Risk Level
    var score = 100
    if (resolutionStatus == RedirectResolutionStatus.SSRF_BLOCKED) {
      score = 0
    } else if (resolutionStatus == RedirectResolutionStatus.TOR_ROUTE_LOST) {
      score = 0
    } else {
      if (hasTracking) score -= 15
      if (hops.size > 3) score -= 15
      if (visitedDomains.size > 2) score -= 15
      if (finalDestinationUrl?.startsWith("http://", ignoreCase = true) == true) score -= 35
      if (SUSPICIOUS_TLDS.contains(tld.lowercase())) score -= 20
      if (finalDomain.startsWith("xn--")) score -= 25
      if (resolutionStatus != RedirectResolutionStatus.RESOLVED) score -= 20
    }
    score = score.coerceIn(0, 100)

    val riskLevel = when {
      resolutionStatus == RedirectResolutionStatus.SSRF_BLOCKED -> SecurityRiskLevel.BLOCKED
      resolutionStatus == RedirectResolutionStatus.TOR_ROUTE_LOST -> SecurityRiskLevel.BLOCKED
      score < 40 -> SecurityRiskLevel.HIGH
      score < 75 -> SecurityRiskLevel.MEDIUM
      else -> SecurityRiskLevel.LOW
    }

    RedirectInspectionResult(
      originalUrl = originalUrl,
      finalUrl = finalDestinationUrl,
      status = resolutionStatus,
      hops = hops,
      hasTrackingParams = hasTracking,
      strippedUrl = strippedUrl,
      isSecure = (finalDestinationUrl ?: originalUrl).startsWith("https://", ignoreCase = true),
      safetyScore = score,
      riskLevel = riskLevel,
      securityInsights = insights,
      error = inspectionError,
      extractedNestedUrl = extractedNested,
      requestedUrl = originalUrl,
      actualBrowserLandedUrl = actualBrowserLandedUrl
    )
  }

  fun extractDomain(url: String): String {
    return try {
      val uri = URI(url)
      uri.host?.lowercase() ?: url.substringAfter("://").substringBefore('/').substringBefore(':')
    } catch (_: Exception) {
      url.substringAfter("://").substringBefore('/').substringBefore(':')
    }
  }

  private fun resolveNextUrl(currentUrl: String, domain: String, target: String): String {
    return try {
      val baseUri = URI(currentUrl)
      baseUri.resolve(target).toString()
    } catch (_: Exception) {
      if (target.startsWith("http://") || target.startsWith("https://")) {
        target
      } else {
        val prefix = if (currentUrl.startsWith("https://")) "https://" else "http://"
        "$prefix$domain/${target.removePrefix("/")}"
      }
    }
  }

  /**
   * Validates if a resolved InetAddress is safe or an SSRF / private network target.
   */
  fun isInetAddressSafe(addr: InetAddress): Pair<Boolean, String?> {
    if (addr.isLoopbackAddress) return Pair(false, "Loopback interface (${addr.hostAddress})")
    if (addr.isSiteLocalAddress) return Pair(false, "Private RFC 1918 LAN (${addr.hostAddress})")
    if (addr.isLinkLocalAddress) return Pair(false, "Link-Local address (${addr.hostAddress})")
    if (addr.isAnyLocalAddress) return Pair(false, "Wildcard local interface (${addr.hostAddress})")
    if (addr.isMulticastAddress) return Pair(false, "Multicast address (${addr.hostAddress})")

    val raw = addr.address ?: return Pair(false, "Invalid network address")

    if (raw.size == 4) {
      val o0 = raw[0].toInt() and 0xFF
      val o1 = raw[1].toInt() and 0xFF
      val o2 = raw[2].toInt() and 0xFF

      // 0.0.0.0/8 (Current network)
      if (o0 == 0) return Pair(false, "Current network address (${addr.hostAddress})")
      // 10.0.0.0/8 (Private Class A)
      if (o0 == 10) return Pair(false, "Private RFC 1918 Class A subnet (${addr.hostAddress})")
      // 100.64.0.0/10 (Carrier Grade NAT)
      if (o0 == 100 && (o1 in 64..127)) return Pair(false, "Carrier-grade NAT subnet (${addr.hostAddress})")
      // 127.0.0.0/8 (Loopback)
      if (o0 == 127) return Pair(false, "Loopback interface (${addr.hostAddress})")
      // 169.254.0.0/16 (Link-Local & Cloud Metadata)
      if (o0 == 169 && o1 == 254) return Pair(false, "Cloud instance metadata / Link-Local (${addr.hostAddress})")
      // 172.16.0.0/12 (Private Class B)
      if (o0 == 172 && (o1 in 16..31)) return Pair(false, "Private RFC 1918 Class B subnet (${addr.hostAddress})")
      // 192.0.0.0/24 (IETF Protocol Assignments)
      if (o0 == 192 && o1 == 0 && o2 == 0) return Pair(false, "IETF protocol subnet (${addr.hostAddress})")
      // 192.0.2.0/24 (TEST-NET-1)
      if (o0 == 192 && o1 == 0 && o2 == 2) return Pair(false, "Documentation TEST-NET-1 (${addr.hostAddress})")
      // 192.168.0.0/16 (Private Class C)
      if (o0 == 192 && o1 == 168) return Pair(false, "Private RFC 1918 Class C subnet (${addr.hostAddress})")
      // 198.18.0.0/15 (Benchmarking)
      if (o0 == 198 && (o1 == 18 || o1 == 19)) return Pair(false, "Benchmark testing subnet (${addr.hostAddress})")
      // 198.51.100.0/24 (TEST-NET-2)
      if (o0 == 198 && o1 == 51 && o2 == 100) return Pair(false, "Documentation TEST-NET-2 (${addr.hostAddress})")
      // 203.0.113.0/24 (TEST-NET-3)
      if (o0 == 203 && o1 == 0 && o2 == 113) return Pair(false, "Documentation TEST-NET-3 (${addr.hostAddress})")
      // 224.0.0.0/4 (Multicast 224-239)
      if (o0 in 224..239) return Pair(false, "Multicast subnet (${addr.hostAddress})")
      // 240.0.0.0/4 (Reserved / Future Use 240-255)
      if (o0 in 240..255) return Pair(false, "Reserved subnet (${addr.hostAddress})")
    } else if (raw.size == 16) {
      val cleanHost = addr.hostAddress?.lowercase() ?: ""
      if (cleanHost == "::1" || cleanHost == "0:0:0:0:0:0:0:1") {
        return Pair(false, "IPv6 loopback interface")
      }
      val b0 = raw[0].toInt() and 0xFF
      val b1 = raw[1].toInt() and 0xFF

      // Check IPv4-mapped IPv6 (::ffff:0:0/96)
      val isIpv4Mapped = (0..9).all { raw[it] == 0.toByte() } && raw[10] == (-1).toByte() && raw[11] == (-1).toByte()
      if (isIpv4Mapped) {
        val ipv4Bytes = byteArrayOf(raw[12], raw[13], raw[14], raw[15])
        try {
          val mappedIpv4 = InetAddress.getByAddress(ipv4Bytes)
          val (mappedSafe, mappedReason) = isInetAddressSafe(mappedIpv4)
          if (!mappedSafe) {
            return Pair(false, "IPv4-mapped IPv6 prohibited address: $mappedReason")
          }
        } catch (_: Exception) {}
      }

      // fc00::/7 Unique Local Address (b0 is 0xfc or 0xfd)
      if ((b0 and 0xFE) == 0xFC) {
        return Pair(false, "IPv6 Unique Local Address (ULA)")
      }
      // fe80::/10 Link Local Address (b0 == 0xFE and (b1 & 0xC0) == 0x80)
      if (b0 == 0xFE && (b1 and 0xC0) == 0x80) {
        return Pair(false, "IPv6 Link-Local address")
      }
      // ff00::/8 Multicast
      if (b0 == 0xFF) {
        return Pair(false, "IPv6 Multicast address")
      }
    }

    return Pair(true, null)
  }

  /**
   * SSRF & Protocol Safety Gate.
   * Blocks access to loopback, private IPv4/IPv6 subnets, local internal domains, and unauthorized URI schemes.
   */
  fun isTargetSafeForInspection(url: String): Pair<Boolean, String?> {
    if (url.length > 2048) {
      return Pair(false, "URL exceeds maximum length limit of 2048 characters.")
    }

    val uri = try {
      URI(url)
    } catch (e: Exception) {
      return Pair(false, "Malformed URL: ${e.message}")
    }

    val scheme = uri.scheme?.lowercase() ?: ""
    if (scheme != "http" && scheme != "https") {
      return Pair(false, "Forbidden protocol: '$scheme'. Only HTTP and HTTPS are permitted for security.")
    }

    val host = uri.host?.lowercase()?.trim() ?: return Pair(false, "URL is missing a valid host.")

    // Check localhost & local network domain suffixes
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") ||
      host.endsWith(".lan") || host.endsWith(".test") || host.endsWith(".corp") ||
      host.endsWith(".home.arpa") || host == "169.254.169.254"
    ) {
      return Pair(false, "SSRF Block: Access to local network, metadata endpoint or internal hostname '$host' is denied.")
    }

    // Try parsing as InetAddress if host is IP literal
    try {
      val ipLiteral = host.removePrefix("[").removeSuffix("]")
      val isNumeric = ipLiteral.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")) ||
        ipLiteral.contains(":") ||
        (try { android.net.InetAddresses.isNumericAddress(ipLiteral) } catch (_: Throwable) { false })
      if (isNumeric) {
        val parsedAddr = InetAddress.getByName(ipLiteral)
        val (safe, reason) = isInetAddressSafe(parsedAddr)
        if (!safe) {
          return Pair(false, "SSRF Block: $reason")
        }
      }
    } catch (_: Exception) {}

    return Pair(true, null)
  }

  private val REDIRECT_PARAM_KEYS = setOf(
    "url", "u", "target", "redirect", "redirect_url", "redirect_uri",
    "dest", "destination", "continue", "next", "link", "r", "to",
    "out", "ref", "go", "q", "src"
  )

  fun hasTrackingParams(url: String): Boolean {
    val query = try {
      URI(url).query
    } catch (_: Exception) {
      url.substringAfter('?', "")
    } ?: return false

    val params = query.split('&')
    return params.any { pair ->
      val key = pair.substringBefore('=').lowercase()
      key.startsWith("utm_") || TRACKING_PARAMS.contains(key)
    }
  }

  /**
   * Extracts candidate destination URLs from masked redirect wrappers, affiliate hops, or encoded parameters.
   * e.g., https://tracker.com/click?url=https%3A%2F%2Fdestination.com%2Ftarget -> https://destination.com/target
   */
  fun extractNestedTargetUrl(url: String): String? {
    if (!url.contains("?") && !url.contains("&")) return null

    val query = try {
      URI(url).rawQuery ?: url.substringAfter('?', "")
    } catch (_: Exception) {
      url.substringAfter('?', "")
    }
    if (query.isBlank()) return null

    val pairs = query.split('&')
    for (pair in pairs) {
      val parts = pair.split('=', limit = 2)
      val key = parts[0].lowercase().trim()
      val rawVal = if (parts.size > 1) parts[1].trim() else ""
      if (rawVal.isBlank()) continue

      if (REDIRECT_PARAM_KEYS.contains(key) || key.contains("url") || key.contains("redirect") || key.contains("dest")) {
        // Try standard URL decoding
        val decoded = try {
          URLDecoder.decode(rawVal, "UTF-8")
        } catch (_: Exception) {
          rawVal
        }

        if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
          return decoded
        }

        // Try Base64 decoding
        try {
          val base64Decoded = String(Base64.decode(rawVal, Base64.URL_SAFE or Base64.NO_PADDING or Base64.DEFAULT), StandardCharsets.UTF_8)
          if (base64Decoded.startsWith("http://") || base64Decoded.startsWith("https://")) {
            return base64Decoded
          }
        } catch (_: Exception) {}
      }
    }
    return null
  }

  fun stripTrackingParameters(url: String): String {
    return try {
      val parsed = URI(url)
      val query = parsed.query ?: return url
      val filteredParams = query.split('&').filterNot { pair ->
        val key = pair.substringBefore('=').lowercase()
        key.startsWith("utm_") || TRACKING_PARAMS.contains(key)
      }

      val newQuery = if (filteredParams.isNotEmpty()) "?${filteredParams.joinToString("&")}" else ""
      val fragment = if (!parsed.fragment.isNullOrBlank()) "#${parsed.fragment}" else ""
      val portStr = if (parsed.port != -1 && parsed.port != 80 && parsed.port != 443) ":${parsed.port}" else ""

      "${parsed.scheme}://${parsed.host}$portStr${parsed.rawPath ?: ""}$newQuery$fragment"
    } catch (e: Exception) {
      url
    }
  }
}
