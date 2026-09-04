package com.remmi.browser.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class SiteSecuritySettings(
  val host: String,
  val javascriptEnabled: Boolean? = null, // null means use Tab's SecurityLevel default
  val blockPopups: Boolean = true,
  val autoplayAllowed: Boolean = false,
  val cookiePolicy: String = "ISOLATE", // ISOLATE, BLOCK, ALLOW
  val shieldsDown: Boolean = false,
  val cosmeticPolicy: String = "DEFAULT", // DEFAULT, ENABLED, DISABLED
  val customSecurityLevel: SecurityLevel? = null
)

class SiteSecurityPolicyManager private constructor(private val context: Context) {
  private val prefs: SharedPreferences = context.getSharedPreferences("remmi_site_security_prefs", Context.MODE_PRIVATE)

  private val _policies = MutableStateFlow<Map<String, SiteSecuritySettings>>(loadPolicies())
  val policies: StateFlow<Map<String, SiteSecuritySettings>> = _policies.asStateFlow()

  companion object {
    @Volatile
    private var instance: SiteSecurityPolicyManager? = null

    fun getInstance(context: Context): SiteSecurityPolicyManager {
      return instance ?: synchronized(this) {
        instance ?: SiteSecurityPolicyManager(context.applicationContext).also { instance = it }
      }
    }
  }

  private fun loadPolicies(): Map<String, SiteSecuritySettings> {
    val map = mutableMapOf<String, SiteSecuritySettings>()
    prefs.all.forEach { (host, jsonStr) ->
      if (jsonStr is String) {
        try {
          val json = JSONObject(jsonStr)
          val js = if (json.has("js")) json.getBoolean("js") else null
          val popups = json.optBoolean("popups", true)
          val autoplay = json.optBoolean("autoplay", false)
          val cookie = json.optString("cookie", "ISOLATE")
          val shieldsDown = json.optBoolean("shieldsDown", false)
          val cosmetic = json.optString("cosmetic", "DEFAULT")
          val secLevelStr = json.optString("secLevel", "")
          val secLevel = if (secLevelStr.isNotEmpty()) SecurityLevel.valueOf(secLevelStr) else null
          map[host] = SiteSecuritySettings(
            host = host,
            javascriptEnabled = js,
            blockPopups = popups,
            autoplayAllowed = autoplay,
            cookiePolicy = cookie,
            shieldsDown = shieldsDown,
            cosmeticPolicy = cosmetic,
            customSecurityLevel = secLevel
          )
        } catch (_: Exception) {}
      }
    }
    return map
  }

  fun getPolicyForHost(host: String): SiteSecuritySettings {
    val cleanHost = host.lowercase().trim()
    return _policies.value[cleanHost] ?: SiteSecuritySettings(host = cleanHost)
  }

  fun setPolicyForHost(settings: SiteSecuritySettings) {
    val cleanHost = settings.host.lowercase().trim()
    val json = JSONObject().apply {
      settings.javascriptEnabled?.let { put("js", it) }
      put("popups", settings.blockPopups)
      put("autoplay", settings.autoplayAllowed)
      put("cookie", settings.cookiePolicy)
      put("shieldsDown", settings.shieldsDown)
      put("cosmetic", settings.cosmeticPolicy)
      settings.customSecurityLevel?.let { put("secLevel", it.name) }
    }
    prefs.edit().putString(cleanHost, json.toString()).apply()
    _policies.value = _policies.value + (cleanHost to settings)

    // Dynamically apply to active sessions
    try {
      com.remmi.browser.engine.GeckoEngineManager.getInstance(context).applySiteSecurityPolicyToMatchingTabs(cleanHost)
    } catch (_: Exception) {}
  }

  fun removePolicy(host: String) {
    val cleanHost = host.lowercase().trim()
    prefs.edit().remove(cleanHost).apply()
    _policies.value = _policies.value - cleanHost

    // Dynamically re-apply default tab policy to active sessions
    try {
      com.remmi.browser.engine.GeckoEngineManager.getInstance(context).applySiteSecurityPolicyToMatchingTabs(cleanHost)
    } catch (_: Exception) {}
  }
}
