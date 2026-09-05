package com.remmi.browser.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CustomBlockRule(
  val id: String = UUID.randomUUID().toString(),
  val host: String,
  val selector: String,
  val timestamp: Long = System.currentTimeMillis(),
  val enabled: Boolean = true
)

class CustomBlockRuleManager private constructor(private val context: Context) {
  private val prefs: SharedPreferences = context.getSharedPreferences("remmi_custom_element_rules", Context.MODE_PRIVATE)
  private val _rules = MutableStateFlow<List<CustomBlockRule>>(loadRules())
  val rules: StateFlow<List<CustomBlockRule>> = _rules.asStateFlow()

  companion object {
    @Volatile
    private var instance: CustomBlockRuleManager? = null

    fun getInstance(context: Context): CustomBlockRuleManager {
      return instance ?: synchronized(this) {
        instance ?: CustomBlockRuleManager(context.applicationContext).also { instance = it }
      }
    }
  }

  private fun loadRules(): List<CustomBlockRule> {
    val jsonStr = prefs.getString("custom_rules", null) ?: return emptyList()
    val list = mutableListOf<CustomBlockRule>()
    try {
      val array = JSONArray(jsonStr)
      for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        list.add(
          CustomBlockRule(
            id = obj.optString("id", UUID.randomUUID().toString()),
            host = obj.getString("host"),
            selector = obj.getString("selector"),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            enabled = obj.optBoolean("enabled", true)
          )
        )
      }
    } catch (_: Exception) {}
    return list
  }

  private fun saveRules(list: List<CustomBlockRule>) {
    val array = JSONArray()
    list.forEach { rule ->
      array.put(JSONObject().apply {
        put("id", rule.id)
        put("host", rule.host)
        put("selector", rule.selector)
        put("timestamp", rule.timestamp)
        put("enabled", rule.enabled)
      })
    }
    prefs.edit().putString("custom_rules", array.toString()).apply()
    _rules.value = list
  }

  fun getSelectorsForHost(host: String): List<String> {
    val cleanHost = host.lowercase().trim()
    if (cleanHost.isEmpty()) return emptyList()
    return _rules.value
      .filter { it.enabled && (it.host == cleanHost || it.host.isEmpty() || cleanHost.endsWith(it.host)) }
      .map { it.selector }
  }

  fun addRule(host: String, selector: String): CustomBlockRule {
    val cleanHost = host.lowercase().trim()
    val cleanSelector = selector.trim()
    val rule = CustomBlockRule(host = cleanHost, selector = cleanSelector)
    val updated = _rules.value + rule
    saveRules(updated)
    return rule
  }

  fun removeRule(id: String) {
    saveRules(_rules.value.filter { it.id != id })
  }

  fun toggleRule(id: String) {
    saveRules(_rules.value.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
  }
}
