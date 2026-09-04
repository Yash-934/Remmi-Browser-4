package com.remmi.browser.security

/**
 * DNS-over-HTTPS (DoH) Provider Definitions for Encrypted DNS.
 */
enum class DnsProvider(
  val id: String,
  val displayName: String,
  val dohUri: String,
  val description: String,
  val blocksAds: Boolean = false,
  val blocksMalware: Boolean = false
) {
  CLOUDFLARE(
    id = "cloudflare",
    displayName = "Cloudflare (1.1.1.1)",
    dohUri = "https://cloudflare-dns.com/dns-query",
    description = "Ultra-fast anycast encrypted DNS with no-log privacy audit"
  ),
  QUAD9(
    id = "quad9",
    displayName = "Quad9 (9.9.9.9)",
    dohUri = "https://dns.quad9.net/dns-query",
    description = "Swiss non-profit DNS with threat intelligence & malware blocking",
    blocksMalware = true
  ),
  MULLVAD(
    id = "mullvad",
    displayName = "Mullvad DoH",
    dohUri = "https://dns.mullvad.net/dns-query",
    description = "Strict no-logs European privacy DNS resolver"
  ),
  ADGUARD(
    id = "adguard",
    displayName = "AdGuard DNS",
    dohUri = "https://dns.adguard-dns.com/dns-query",
    description = "Blocks ad networks, tracking telemetry, and malicious domains",
    blocksAds = true,
    blocksMalware = true
  ),
  NEXTDNS(
    id = "nextdns",
    displayName = "NextDNS",
    dohUri = "https://dns.nextdns.io/dns-query",
    description = "Cloud-based firewall with advanced privacy filters"
  ),
  SYSTEM(
    id = "system",
    displayName = "System Default DNS",
    dohUri = "",
    description = "Use underlying Android network interface resolver"
  );

  companion object {
    fun fromId(id: String): DnsProvider {
      return entries.find { it.id.equals(id, ignoreCase = true) } ?: CLOUDFLARE
    }
  }
}
