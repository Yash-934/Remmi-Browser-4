package com.remmi.browser.storage

import android.content.Context
import android.content.SharedPreferences
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.ui.theme.BrowserFont
import com.remmi.browser.ui.theme.CyberTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SpeedDialItem(
  val id: String = UUID.randomUUID().toString(),
  val title: String,
  val url: String,
  val category: String = "TACTICAL",
  val iconKey: String = "globe",
)

enum class SearchEngine(
  val id: String,
  val displayName: String,
  val subtitle: String,
  val baseUrl: String,
  val searchUrlFormat: String,
  val iconKey: String,
) {
  DUCK_DUCK_GO(
    id = "ddg",
    displayName = "DuckDuckGo",
    subtitle = "Privacy search • Zero user profiling",
    baseUrl = "https://duckduckgo.com",
    searchUrlFormat = "https://duckduckgo.com/?q=%s",
    iconKey = "search",
  ),
  STARTPAGE(
    id = "startpage",
    displayName = "Startpage",
    subtitle = "Google index results • Zero tracking & anonymous view",
    baseUrl = "https://www.startpage.com",
    searchUrlFormat = "https://www.startpage.com/sp/search?query=%s",
    iconKey = "shield",
  ),
  SEARXNG(
    id = "searxng",
    displayName = "SearXNG",
    subtitle = "Decentralized metasearch • Anti-censorship",
    baseUrl = "https://searx.be",
    searchUrlFormat = "https://searx.be/search?q=%s",
    iconKey = "public",
  ),
  BRAVE(
    id = "brave",
    displayName = "Brave Search",
    subtitle = "Independent web index • Complete privacy",
    baseUrl = "https://search.brave.com",
    searchUrlFormat = "https://search.brave.com/search?q=%s",
    iconKey = "security",
  ),
  MULLVAD_LETA(
    id = "mullvad",
    displayName = "Mullvad Leta",
    subtitle = "Zero-log proxy search • Direct Tor/VPN companion",
    baseUrl = "https://leta.mullvad.net",
    searchUrlFormat = "https://leta.mullvad.net/?q=%s",
    iconKey = "vpn",
  ),
  KAGI(
    id = "kagi",
    displayName = "Kagi Search",
    subtitle = "Ad-free, telemetry-free premium search engine",
    baseUrl = "https://kagi.com",
    searchUrlFormat = "https://kagi.com/search?q=%s",
    iconKey = "star",
  ),
  ECOSIA(
    id = "ecosia",
    displayName = "Ecosia",
    subtitle = "Eco-friendly privacy search • Tree planting",
    baseUrl = "https://www.ecosia.org",
    searchUrlFormat = "https://www.ecosia.org/search?q=%s",
    iconKey = "eco",
  );

  companion object {
    fun fromId(id: String): SearchEngine {
      return entries.find { it.id.equals(id, ignoreCase = true) || it.displayName.equals(id, ignoreCase = true) } ?: DUCK_DUCK_GO
    }
  }

  fun buildSearchUrl(query: String): String {
    return try {
      val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
      searchUrlFormat.format(encoded)
    } catch (e: Exception) {
      searchUrlFormat.format(query.trim())
    }
  }
}

val DEFAULT_SPEED_DIALS = listOf(
  SpeedDialItem(
    id = "ddg",
    title = "DuckDuckGo",
    url = "https://duckduckgo.com",
    category = "Privacy Search",
    iconKey = "search"
  ),
  SpeedDialItem(
    id = "tor",
    title = "Tor Project",
    url = "https://www.torproject.org",
    category = "Onion Core",
    iconKey = "vpn"
  ),
  SpeedDialItem(
    id = "github",
    title = "GitHub",
    url = "https://github.com",
    category = "Open Source",
    iconKey = "code"
  ),
  SpeedDialItem(
    id = "eff",
    title = "EFF",
    url = "https://www.eff.org",
    category = "Digital Rights",
    iconKey = "policy"
  ),
  SpeedDialItem(
    id = "proton",
    title = "Proton",
    url = "https://proton.me",
    category = "Encrypted Mail",
    iconKey = "shield"
  ),
  SpeedDialItem(
    id = "wikipedia",
    title = "Wikipedia",
    url = "https://en.wikipedia.org",
    category = "Knowledge Base",
    iconKey = "wiki"
  ),
  SpeedDialItem(
    id = "hackernews",
    title = "HackerNews",
    url = "https://news.ycombinator.com",
    category = "Tech Terminal",
    iconKey = "news"
  ),
  SpeedDialItem(
    id = "reddit",
    title = "Reddit",
    url = "https://www.reddit.com",
    category = "Net Community",
    iconKey = "forum"
  ),
)

data class BrowserSettings(
  val defaultProfile: PrivacyProfile = PrivacyProfile.SHIELD,
  val cyberTheme: CyberTheme = CyberTheme.NORMAL_DEFAULT,
  val browserFont: BrowserFont = BrowserFont.CHROME_SANS,
  val pureBlackOled: Boolean = false,
  val defaultDesktopMode: Boolean = false,
  val httpsOnlyMode: Boolean = true,
  val glitchAnimationEnabled: Boolean = false,
  val biometricLockEnabled: Boolean = false,
  val restoreLastSession: Boolean = true,
  val clearDataOnExit: Boolean = false,
  val forcedWebRender: Boolean = true,
  val blockWebRTC: Boolean = true,
  val antiFingerprintingFPP: Boolean = true,
  val antiFingerprintingRFP: Boolean = true,
  val cookieIsolation: Boolean = true,
  val clipboardAutoClearSec: Int = 30,
  val searchEngineName: String = "DuckDuckGo",
  val readerFontSize: Int = 1, // 0 = Small (14sp), 1 = Medium (17sp), 2 = Large (21sp)
  val readerCyberFont: Boolean = false,
  val customWallpaperUri: String? = null,
  val backgroundAnimation: String = "LIGHT_AURA_MESH",
  val wallpaperDimLevel: Float = 0.0f, // 0.0f = 100% Full Visibility / Crystal Clear (No fade)
  val fullscreenWallpaperEnabled: Boolean = true, // Edge-to-Edge full screen background
  val wallpaperScaleMode: String = "CROP", // "CROP", "FIT", "FILL"
  val dnsProvider: com.remmi.browser.security.DnsProvider = com.remmi.browser.security.DnsProvider.CLOUDFLARE,
  val encryptedClientHelloEnabled: Boolean = true,
  val globalPrivacyControlEnabled: Boolean = true,
  val doNotTrackEnabled: Boolean = true,
  val strictReferrerPolicy: Boolean = true,
  val torBridgeMode: String = "DIRECT",
  val autoRouteOnionTabs: Boolean = true,
  val cosmeticFilteringEnabled: Boolean = true,
)

class SettingsRepository(context: Context) {

  private val prefs: SharedPreferences =
    context.getSharedPreferences("remmi_sec_prefs", Context.MODE_PRIVATE)

  private val _settings = MutableStateFlow(loadSettings())
  val settings: StateFlow<BrowserSettings> = _settings.asStateFlow()

  private val _speedDials = MutableStateFlow(loadSpeedDials())
  val speedDials: StateFlow<List<SpeedDialItem>> = _speedDials.asStateFlow()

  private fun loadSettings(): BrowserSettings {
    val profileStr = prefs.getString("default_profile", PrivacyProfile.SHIELD.name)
    val profile = try {
      PrivacyProfile.valueOf(profileStr ?: PrivacyProfile.SHIELD.name)
    } catch (e: Exception) {
      PrivacyProfile.SHIELD
    }

    val themeStr = prefs.getString("cyber_theme", CyberTheme.NORMAL_DEFAULT.id)
    val theme = CyberTheme.fromId(themeStr)

    val fontStr = prefs.getString("browser_font_choice", BrowserFont.CHROME_SANS.id)
    val font = BrowserFont.fromId(fontStr)

    val clearOnExit = prefs.getBoolean("clear_on_exit", false)
    val restoreSession = if (clearOnExit) false else prefs.getBoolean("restore_last_session", true)

    val dnsStr = prefs.getString("dns_provider", com.remmi.browser.security.DnsProvider.CLOUDFLARE.id)
    val dnsProvider = com.remmi.browser.security.DnsProvider.fromId(dnsStr ?: "cloudflare")

    return BrowserSettings(
      defaultProfile = profile,
      cyberTheme = theme,
      browserFont = font,
      pureBlackOled = prefs.getBoolean("pure_black_oled", false),
      defaultDesktopMode = prefs.getBoolean("default_desktop_mode", false),
      httpsOnlyMode = prefs.getBoolean("https_only", true),
      glitchAnimationEnabled = prefs.getBoolean("glitch_enabled", false),
      biometricLockEnabled = prefs.getBoolean("biometric_lock", false),
      restoreLastSession = restoreSession,
      clearDataOnExit = clearOnExit,
      forcedWebRender = prefs.getBoolean("forced_webrender", true),
      blockWebRTC = prefs.getBoolean("block_webrtc", true),
      antiFingerprintingFPP = prefs.getBoolean("fpp_enabled", true),
      antiFingerprintingRFP = prefs.getBoolean("rfp_enabled", true),
      cookieIsolation = prefs.getBoolean("cookie_isolation", true),
      clipboardAutoClearSec = prefs.getInt("clipboard_timeout", 30),
      searchEngineName = prefs.getString("search_engine", "DuckDuckGo") ?: "DuckDuckGo",
      readerFontSize = prefs.getInt("reader_font_size", 1),
      readerCyberFont = prefs.getBoolean("reader_cyber_font", false),
      customWallpaperUri = prefs.getString("custom_wallpaper_uri", null),
      backgroundAnimation = prefs.getString("bg_animation_type", "LIGHT_AURA_MESH") ?: "LIGHT_AURA_MESH",
      wallpaperDimLevel = prefs.getFloat("wallpaper_dim_level", 0.0f),
      fullscreenWallpaperEnabled = prefs.getBoolean("fullscreen_wallpaper_enabled", true),
      wallpaperScaleMode = prefs.getString("wallpaper_scale_mode", "CROP") ?: "CROP",
      dnsProvider = dnsProvider,
      encryptedClientHelloEnabled = prefs.getBoolean("ech_enabled", true),
      globalPrivacyControlEnabled = prefs.getBoolean("gpc_enabled", true),
      doNotTrackEnabled = prefs.getBoolean("dnt_enabled", true),
      strictReferrerPolicy = prefs.getBoolean("strict_referrer", true),
      torBridgeMode = prefs.getString("tor_bridge_mode", "DIRECT") ?: "DIRECT",
      autoRouteOnionTabs = prefs.getBoolean("auto_route_onion", true),
      cosmeticFilteringEnabled = prefs.getBoolean("cosmetic_filtering_enabled", true),
    )
  }

  private fun loadSpeedDials(): List<SpeedDialItem> {
    val rawJson = prefs.getString("speed_dials_json", null) ?: return DEFAULT_SPEED_DIALS
    return try {
      val array = JSONArray(rawJson)
      val list = mutableListOf<SpeedDialItem>()
      for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        list.add(
          SpeedDialItem(
            id = obj.optString("id", UUID.randomUUID().toString()),
            title = obj.optString("title", "Untitled"),
            url = obj.optString("url", "https://duckduckgo.com"),
            category = obj.optString("category", "Tactical"),
            iconKey = obj.optString("iconKey", "globe"),
          )
        )
      }
      if (list.isEmpty()) DEFAULT_SPEED_DIALS else list
    } catch (e: Exception) {
      DEFAULT_SPEED_DIALS
    }
  }

  private fun saveSpeedDials(items: List<SpeedDialItem>) {
    try {
      val array = JSONArray()
      for (item in items) {
        val obj = JSONObject().apply {
          put("id", item.id)
          put("title", item.title)
          put("url", item.url)
          put("category", item.category)
          put("iconKey", item.iconKey)
        }
        array.put(obj)
      }
      prefs.edit().putString("speed_dials_json", array.toString()).apply()
      _speedDials.value = items
    } catch (e: Exception) {
      // Fallback
    }
  }

  fun updateCyberTheme(theme: CyberTheme) {
    prefs.edit().putString("cyber_theme", theme.id).apply()
    _settings.value = _settings.value.copy(cyberTheme = theme)
  }

  fun updateBrowserFont(font: BrowserFont) {
    prefs.edit().putString("browser_font_choice", font.id).apply()
    _settings.value = _settings.value.copy(browserFont = font)
  }

  fun updatePureBlackOled(enabled: Boolean) {
    prefs.edit().putBoolean("pure_black_oled", enabled).apply()
    _settings.value = _settings.value.copy(pureBlackOled = enabled)
  }

  fun updateDefaultDesktopMode(enabled: Boolean) {
    prefs.edit().putBoolean("default_desktop_mode", enabled).apply()
    _settings.value = _settings.value.copy(defaultDesktopMode = enabled)
  }

  fun updateGlitchEnabled(enabled: Boolean) {
    prefs.edit().putBoolean("glitch_enabled", enabled).apply()
    _settings.value = _settings.value.copy(glitchAnimationEnabled = enabled)
  }

  fun updateHttpsOnly(enabled: Boolean) {
    prefs.edit().putBoolean("https_only", enabled).apply()
    _settings.value = _settings.value.copy(httpsOnlyMode = enabled)
  }

  fun updateBiometricLock(enabled: Boolean) {
    prefs.edit().putBoolean("biometric_lock", enabled).apply()
    _settings.value = _settings.value.copy(biometricLockEnabled = enabled)
  }

  fun updateDefaultProfile(profile: PrivacyProfile) {
    prefs.edit().putString("default_profile", profile.name).apply()
    _settings.value = _settings.value.copy(defaultProfile = profile)
  }

  fun updateReaderFontSize(sizeIndex: Int) {
    val clamped = sizeIndex.coerceIn(0, 2)
    prefs.edit().putInt("reader_font_size", clamped).apply()
    _settings.value = _settings.value.copy(readerFontSize = clamped)
  }

  fun updateReaderCyberFont(cyberFont: Boolean) {
    prefs.edit().putBoolean("reader_cyber_font", cyberFont).apply()
    _settings.value = _settings.value.copy(readerCyberFont = cyberFont)
  }

  fun updateSearchEngine(engineName: String) {
    prefs.edit().putString("search_engine", engineName).apply()
    _settings.value = _settings.value.copy(searchEngineName = engineName)
  }

  fun updateCustomWallpaper(uri: String?) {
    prefs.edit().putString("custom_wallpaper_uri", uri).apply()
    _settings.value = _settings.value.copy(customWallpaperUri = uri)
  }

  fun updateBackgroundAnimation(type: String) {
    prefs.edit().putString("bg_animation_type", type).apply()
    _settings.value = _settings.value.copy(backgroundAnimation = type)
  }

  fun updateWallpaperDimLevel(level: Float) {
    val clamped = level.coerceIn(0f, 1f)
    prefs.edit().putFloat("wallpaper_dim_level", clamped).apply()
    _settings.value = _settings.value.copy(wallpaperDimLevel = clamped)
  }

  fun updateFullscreenWallpaper(enabled: Boolean) {
    prefs.edit().putBoolean("fullscreen_wallpaper_enabled", enabled).apply()
    _settings.value = _settings.value.copy(fullscreenWallpaperEnabled = enabled)
  }

  fun updateWallpaperScaleMode(mode: String) {
    prefs.edit().putString("wallpaper_scale_mode", mode).apply()
    _settings.value = _settings.value.copy(wallpaperScaleMode = mode)
  }

  fun addSpeedDial(item: SpeedDialItem) {
    val current = _speedDials.value.toMutableList()
    current.add(item)
    saveSpeedDials(current)
  }

  fun editSpeedDial(updated: SpeedDialItem) {
    val current = _speedDials.value.map { if (it.id == updated.id) updated else it }
    saveSpeedDials(current)
  }

  fun removeSpeedDial(id: String) {
    val current = _speedDials.value.filter { it.id != id }
    saveSpeedDials(current)
  }

  fun resetSpeedDials() {
    saveSpeedDials(DEFAULT_SPEED_DIALS)
  }

  fun updateRestoreLastSession(enabled: Boolean) {
    val editor = prefs.edit()
    editor.putBoolean("restore_last_session", enabled)
    if (enabled) {
      editor.putBoolean("clear_on_exit", false)
      _settings.value = _settings.value.copy(
        restoreLastSession = true,
        clearDataOnExit = false
      )
    } else {
      _settings.value = _settings.value.copy(restoreLastSession = false)
    }
    editor.apply()
  }

  fun updateClearOnExit(enabled: Boolean) {
    val editor = prefs.edit()
    editor.putBoolean("clear_on_exit", enabled)
    _settings.value = _settings.value.copy(
      clearDataOnExit = enabled
    )
    editor.apply()
  }

  fun updateDnsProvider(provider: com.remmi.browser.security.DnsProvider) {
    prefs.edit().putString("dns_provider", provider.id).apply()
    _settings.value = _settings.value.copy(dnsProvider = provider)
  }

  fun updateEchEnabled(enabled: Boolean) {
    prefs.edit().putBoolean("ech_enabled", enabled).apply()
    _settings.value = _settings.value.copy(encryptedClientHelloEnabled = enabled)
  }

  fun updateGpcEnabled(enabled: Boolean) {
    prefs.edit().putBoolean("gpc_enabled", enabled).apply()
    _settings.value = _settings.value.copy(globalPrivacyControlEnabled = enabled)
  }

  fun updateDntEnabled(enabled: Boolean) {
    prefs.edit().putBoolean("dnt_enabled", enabled).apply()
    _settings.value = _settings.value.copy(doNotTrackEnabled = enabled)
  }

  fun updateStrictReferrerPolicy(enabled: Boolean) {
    prefs.edit().putBoolean("strict_referrer", enabled).apply()
    _settings.value = _settings.value.copy(strictReferrerPolicy = enabled)
  }

  fun updateTorBridgeMode(mode: String) {
    prefs.edit().putString("tor_bridge_mode", mode).apply()
    _settings.value = _settings.value.copy(torBridgeMode = mode)
  }

  fun updateAutoRouteOnionTabs(enabled: Boolean) {
    prefs.edit().putBoolean("auto_route_onion", enabled).apply()
    _settings.value = _settings.value.copy(autoRouteOnionTabs = enabled)
  }

  fun updateCosmeticFiltering(enabled: Boolean) {
    prefs.edit().putBoolean("cosmetic_filtering_enabled", enabled).apply()
    _settings.value = _settings.value.copy(cosmeticFilteringEnabled = enabled)
  }

  companion object {
    @Volatile
    private var INSTANCE: SettingsRepository? = null
    fun getInstance(context: Context): SettingsRepository {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
      }
    }
  }
}
