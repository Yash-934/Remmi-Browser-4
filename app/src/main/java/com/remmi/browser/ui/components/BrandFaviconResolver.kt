package com.remmi.browser.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.R
import com.remmi.browser.ui.theme.ThemeCyber

data class BrandInfo(
  @DrawableRes val iconRes: Int? = null,
  val isVectorLogo: Boolean = false,
  val iconTint: Color? = null, // null means use original multi-color drawable
  val bgColor: Color = Color(0xFF2563EB),
  val textColor: Color = Color.White,
  val letterFallback: String = "W",
  val brandName: String = ""
)

object BrandLogoRegistry {

  fun extractDomain(url: String?): String {
    if (url.isNullOrBlank()) return ""
    return try {
      val parsed = android.net.Uri.parse(url)
      val host = parsed.host
      if (!host.isNullOrBlank()) {
        host.removePrefix("www.")
      } else {
        url.removePrefix("https://").removePrefix("http://").substringBefore("/").substringBefore("?")
      }
    } catch (_: Exception) {
      url.substringBefore("/").substringBefore("?")
    }
  }

  fun resolveBrand(
    url: String?,
    domain: String? = null,
    iconKey: String? = null,
    title: String? = null
  ): BrandInfo {
    val cleanUrl = (url ?: "").trim().lowercase()
    val cleanDomain = (domain?.ifBlank { null } ?: extractDomain(cleanUrl)).lowercase()
    val cleanKey = (iconKey ?: "").trim().lowercase()
    val cleanTitle = (title ?: "").trim().lowercase()

    // 1. Check Tor / Onion
    if (cleanDomain.contains("torproject.org") || cleanUrl.contains("torproject.org") ||
      cleanDomain.endsWith(".onion") || cleanUrl.contains(".onion") ||
      cleanKey == "vpn" || cleanKey == "tor" || cleanTitle.contains("tor project")
    ) {
      return BrandInfo(
        iconRes = R.drawable.ic_tor,
        isVectorLogo = true,
        iconTint = Color(0xFFA855F7), // Tor Purple
        bgColor = Color(0xFF7D4698),
        textColor = Color.White,
        letterFallback = "🧅",
        brandName = "Tor"
      )
    }

    // 2. DuckDuckGo
    if (cleanDomain.contains("duckduckgo.com") || cleanUrl.contains("duckduckgo") ||
      cleanKey == "search" && cleanTitle.contains("duck") || cleanKey == "ddg" || cleanTitle.contains("duckduckgo")
    ) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_duckduckgo,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFFDE5833),
        textColor = Color.White,
        letterFallback = "D",
        brandName = "DuckDuckGo"
      )
    }

    // 3. GitHub
    if (cleanDomain.contains("github.com") || cleanDomain.contains("github.io") ||
      cleanKey == "code" || cleanKey == "github" || cleanTitle.contains("github")
    ) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_github,
        isVectorLogo = true,
        iconTint = Color.White,
        bgColor = Color(0xFF1E293B),
        textColor = Color.White,
        letterFallback = "G",
        brandName = "GitHub"
      )
    }

    // 4. Wikipedia / Wikimedia
    if (cleanDomain.contains("wikipedia.org") || cleanDomain.contains("wikimedia.org") ||
      cleanKey == "wiki" || cleanKey == "wikipedia" || cleanTitle.contains("wikipedia")
    ) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_wikipedia,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFF334155),
        textColor = Color.White,
        letterFallback = "W",
        brandName = "Wikipedia"
      )
    }

    // 5. Reddit
    if (cleanDomain.contains("reddit.com") || cleanDomain.contains("redd.it") ||
      cleanKey == "forum" || cleanKey == "reddit" || cleanTitle.contains("reddit")
    ) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_reddit,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFFFF4500),
        textColor = Color.White,
        letterFallback = "R",
        brandName = "Reddit"
      )
    }

    // 6. Hacker News / Y Combinator
    if (cleanDomain.contains("news.ycombinator.com") || cleanDomain.contains("ycombinator.com") ||
      cleanKey == "news" || cleanKey == "hackernews" || cleanTitle.contains("hacker news") || cleanTitle.contains("hackernews")
    ) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_hackernews,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFFFF6600),
        textColor = Color.White,
        letterFallback = "Y",
        brandName = "Hacker News"
      )
    }

    // 7. Proton
    if (cleanDomain.contains("proton.me") || cleanDomain.contains("protonmail.com") || cleanDomain.contains("protonvpn.com") ||
      cleanKey == "proton" || (cleanKey == "shield" && cleanTitle.contains("proton")) || cleanTitle.contains("proton")
    ) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_proton,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFF6D4AFF),
        textColor = Color.White,
        letterFallback = "P",
        brandName = "Proton"
      )
    }

    // 8. Electronic Frontier Foundation (EFF)
    if (cleanDomain.contains("eff.org") || cleanKey == "policy" || cleanKey == "eff" || cleanTitle.contains("eff")) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_eff,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFFDC2626),
        textColor = Color.White,
        letterFallback = "E",
        brandName = "EFF"
      )
    }

    // 9. YouTube
    if (cleanDomain.contains("youtube.com") || cleanDomain.contains("youtu.be") || cleanKey == "youtube" || cleanTitle.contains("youtube")) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_youtube,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFFEF4444),
        textColor = Color.White,
        letterFallback = "▶",
        brandName = "YouTube"
      )
    }

    // 10. Twitter / X
    if (cleanDomain.contains("twitter.com") || cleanDomain == "x.com" || cleanDomain.endsWith(".x.com") ||
      cleanKey == "twitter" || cleanKey == "x" || cleanTitle.contains("twitter") || cleanTitle == "x"
    ) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_twitter,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFF0F172A),
        textColor = Color.White,
        letterFallback = "𝕏",
        brandName = "X"
      )
    }

    // 11. Google
    if (cleanDomain.contains("google.com") || cleanDomain.contains("google.co.") || cleanKey == "google" || cleanTitle.contains("google")) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_google,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFF4285F4),
        textColor = Color.White,
        letterFallback = "G",
        brandName = "Google"
      )
    }

    // 12. Startpage
    if (cleanDomain.contains("startpage.com") || cleanTitle.contains("startpage")) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_startpage,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFF082A82),
        textColor = Color.White,
        letterFallback = "S",
        brandName = "Startpage"
      )
    }

    // 13. Brave
    if (cleanDomain.contains("brave.com") || cleanTitle.contains("brave")) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_brave,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFFFB542B),
        textColor = Color.White,
        letterFallback = "B",
        brandName = "Brave"
      )
    }

    // 14. SearXNG
    if (cleanDomain.contains("searx") || cleanTitle.contains("searx")) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_searxng,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFF3B82F6),
        textColor = Color.White,
        letterFallback = "S",
        brandName = "SearXNG"
      )
    }

    // 15. Mullvad
    if (cleanDomain.contains("mullvad.net") || cleanTitle.contains("mullvad")) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_mullvad,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFF112233),
        textColor = Color.White,
        letterFallback = "M",
        brandName = "Mullvad"
      )
    }

    // 16. Kagi
    if (cleanDomain.contains("kagi.com") || cleanKey == "star" || cleanTitle.contains("kagi")) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_kagi,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFFEAB308),
        textColor = Color(0xFF1E293B),
        letterFallback = "★",
        brandName = "Kagi"
      )
    }

    // 17. Ecosia
    if (cleanDomain.contains("ecosia.org") || cleanKey == "eco" || cleanTitle.contains("ecosia")) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_ecosia,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFF15803D),
        textColor = Color.White,
        letterFallback = "🌱",
        brandName = "Ecosia"
      )
    }

    // 18. Stack Overflow
    if (cleanDomain.contains("stackoverflow.com") || cleanDomain.contains("stackexchange.com") || cleanTitle.contains("stack overflow")) {
      return BrandInfo(
        iconRes = R.drawable.ic_brand_stackoverflow,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFFF48024),
        textColor = Color.White,
        letterFallback = "S",
        brandName = "Stack Overflow"
      )
    }

    // 19. Internal / About pages
    if (cleanUrl.startsWith("about:") || cleanUrl.startsWith("remmi:") || cleanDomain.startsWith("about:")) {
      return BrandInfo(
        iconRes = R.drawable.ic_remmi_panda,
        isVectorLogo = true,
        iconTint = null,
        bgColor = Color(0xFF0284C7),
        textColor = Color.White,
        letterFallback = "🐼",
        brandName = "Remmi"
      )
    }

    // 20. Generic Domain fallback with deterministic color palette and clean monogram
    val effectiveName = if (cleanDomain.isNotBlank()) cleanDomain else cleanTitle
    val initial = effectiveName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "W"
    val hash = Math.abs(effectiveName.hashCode())
    val palette = listOf(
      Color(0xFF0284C7), // Light Blue
      Color(0xFF7C3AED), // Purple
      Color(0xFF059669), // Emerald
      Color(0xFFD97706), // Amber
      Color(0xFFDB2777), // Pink
      Color(0xFF4F46E5), // Indigo
      Color(0xFF0891B2), // Cyan
      Color(0xFF9333EA), // Violet
      Color(0xFFDC2626), // Red
      Color(0xFF0D9488)  // Teal
    )
    val chosenBg = palette[hash % palette.size]

    return BrandInfo(
      iconRes = null,
      isVectorLogo = false,
      bgColor = chosenBg,
      textColor = Color.White,
      letterFallback = initial,
      brandName = cleanDomain
    )
  }
}

/**
 * Universal Brand Favicon Icon component for Quick Links, Bookmarks, and History.
 */
@Composable
fun BrandFaviconIcon(
  url: String,
  modifier: Modifier = Modifier,
  domain: String? = null,
  iconKey: String? = null,
  title: String? = null,
  size: Dp = 26.dp
) {
  val brand = remember(url, domain, iconKey, title) {
    BrandLogoRegistry.resolveBrand(url = url, domain = domain, iconKey = iconKey, title = title)
  }

  if (brand.iconRes != null) {
    if (brand.iconTint != null) {
      Icon(
        painter = painterResource(brand.iconRes),
        contentDescription = brand.brandName.ifBlank { title },
        tint = brand.iconTint,
        modifier = modifier.size(size)
      )
    } else {
      Icon(
        painter = painterResource(brand.iconRes),
        contentDescription = brand.brandName.ifBlank { title },
        tint = Color.Unspecified,
        modifier = modifier.size(size)
      )
    }
  } else {
    Box(
      modifier = modifier
        .size(size)
        .clip(RoundedCornerShape(6.dp))
        .background(brand.bgColor),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = brand.letterFallback,
        color = brand.textColor,
        fontWeight = FontWeight.Bold,
        fontSize = (size.value * 0.52f).sp
      )
    }
  }
}
