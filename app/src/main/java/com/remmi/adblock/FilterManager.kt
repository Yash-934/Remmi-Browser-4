package com.remmi.adblock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.NetworkRouteAuthority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FilterSubscription(
  val id: String,
  val title: String,
  val description: String,
  val ruleCount: Int,
  val enabled: Boolean,
  val url: String,
  val lastUpdated: Long = 0L,
  val isCustom: Boolean = false,
)

class FilterManager(
  private val adblockBridge: AdblockBridge,
  private val context: Context? = null,
) {

  private val prefs: SharedPreferences? = context?.getSharedPreferences("remmi_filter_subs", Context.MODE_PRIVATE)
  private val mutex = Mutex()
  private val filterDir: File? = context?.let { File(it.filesDir, "filters").apply { mkdirs() } }

  private val defaultList = listOf(
    FilterSubscription(
      id = "easylist",
      title = "EasyList Core",
      description = "Primary ad-blocking filter list for standard tracking and display ads.",
      ruleCount = 0,
      enabled = prefs?.getBoolean("filter_easylist", true) ?: true,
      url = "https://easylist.to/easylist/easylist.txt"
    ),
    FilterSubscription(
      id = "easyprivacy",
      title = "EasyPrivacy (Anti-Telemetry)",
      description = "Blocks behavioral trackers, web analytics, and telemetry beacons.",
      ruleCount = 0,
      enabled = prefs?.getBoolean("filter_easyprivacy", true) ?: true,
      url = "https://easylist.to/easylist/easyprivacy.txt"
    ),
    FilterSubscription(
      id = "fanboy_annoyance",
      title = "Fanboy's Annoyance List",
      description = "Removes popups, cookie consent overlays, and social media tracking widgets.",
      ruleCount = 0,
      enabled = prefs?.getBoolean("filter_fanboy_annoyance", true) ?: true,
      url = "https://easylist.to/easylist/fanboy-annoyance.txt"
    ),
    FilterSubscription(
      id = "brave_unbreak",
      title = "Brave Unbreak",
      description = "Fixes sites broken by adblockers.",
      ruleCount = 0,
      enabled = prefs?.getBoolean("filter_brave_unbreak", true) ?: true,
      url = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-unbreak.txt"
    ),
  )

  private val _subscriptions = MutableStateFlow<List<FilterSubscription>>(loadSubscriptions())
  val subscriptions: StateFlow<List<FilterSubscription>> = _subscriptions.asStateFlow()

  private val _isUpdating = MutableStateFlow(false)
  val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

  private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile
  private var activeLoadDeferred: Deferred<Int>? = null
  private val loadFlightLock = Any()

  init {
    loadPersistedRulesIntoBridge(source = "init")
  }

  private fun loadSubscriptions(): List<FilterSubscription> {
    val customJson = prefs?.getString("custom_subscriptions", null)
    val customList = mutableListOf<FilterSubscription>()
    if (!customJson.isNullOrBlank()) {
      try {
        val array = JSONArray(customJson)
        for (i in 0 until array.length()) {
          val obj = array.getJSONObject(i)
          customList.add(
            FilterSubscription(
              id = obj.getString("id"),
              title = obj.getString("title"),
              description = obj.optString("description", "Custom user filter subscription"),
              ruleCount = obj.optInt("ruleCount", 0),
              enabled = obj.optBoolean("enabled", true),
              url = obj.getString("url"),
              lastUpdated = obj.optLong("lastUpdated", 0L),
              isCustom = true
            )
          )
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed loading custom subscriptions: ${e.message}")
      }
    }

    val baseList = defaultList.map { sub ->
      val enabled = prefs?.getBoolean("filter_${sub.id}", sub.enabled) ?: sub.enabled
      val count = prefs?.getInt("count_${sub.id}", sub.ruleCount) ?: sub.ruleCount
      val updated = prefs?.getLong("updated_${sub.id}", 0L) ?: 0L
      sub.copy(enabled = enabled, ruleCount = count, lastUpdated = updated)
    }
    return baseList + customList
  }

  private fun saveCustomSubscriptions(customs: List<FilterSubscription>) {
    val array = JSONArray()
    for (sub in customs) {
      val obj = JSONObject().apply {
        put("id", sub.id)
        put("title", sub.title)
        put("description", sub.description)
        put("ruleCount", sub.ruleCount)
        put("enabled", sub.enabled)
        put("url", sub.url)
        put("lastUpdated", sub.lastUpdated)
      }
      array.put(obj)
    }
    prefs?.edit()?.putString("custom_subscriptions", array.toString())?.apply()
  }

  fun toggleSubscription(id: String) {
    _subscriptions.value = _subscriptions.value.map { sub ->
      if (sub.id == id) {
        val newState = !sub.enabled
        if (!sub.isCustom) {
          prefs?.edit()?.putBoolean("filter_${sub.id}", newState)?.apply()
        }
        sub.copy(enabled = newState)
      } else sub
    }
    saveCustomSubscriptions(_subscriptions.value.filter { it.isCustom })
    loadPersistedRulesIntoBridge(source = "toggleSubscription")
  }

  fun addCustomSubscription(url: String, title: String) {
    val id = "custom_${System.currentTimeMillis()}"
    val newSub = FilterSubscription(
      id = id,
      title = title.ifBlank { "Custom Filter" },
      description = "Custom user subscription ($url)",
      ruleCount = 0,
      enabled = true,
      url = url.trim(),
      isCustom = true
    )
    _subscriptions.value = _subscriptions.value + newSub
    saveCustomSubscriptions(_subscriptions.value.filter { it.isCustom })
    
    // Trigger download for newly added subscription
    CoroutineScope(Dispatchers.IO).launch {
      updateSubscription(newSub)
    }
  }

  fun removeCustomSubscription(id: String) {
    _subscriptions.value = _subscriptions.value.filter { it.id != id }
    saveCustomSubscriptions(_subscriptions.value.filter { it.isCustom })
    filterDir?.let { dir ->
      val file = File(dir, "$id.txt")
      if (file.exists()) file.delete()
    }
    loadPersistedRulesIntoBridge(source = "removeCustomSubscription")
  }

  fun getTotalActiveRules(): Int {
    return _subscriptions.value.filter { it.enabled }.sumOf { it.ruleCount }
  }

  fun logAdblockStatus() {
    val easylist = _subscriptions.value.find { it.id == "easylist" }
    val easyprivacy = _subscriptions.value.find { it.id == "easyprivacy" }
    val totalActive = getTotalActiveRules()
    val isNative = adblockBridge.isNativeAvailable()
    val gen = adblockBridge.getEngineGeneration()
    val blockedCount = adblockBridge.totalBlockedCount.get()

    Log.i(
      TAG,
      "[ADBLOCK_STATUS] native=$isNative easylistDownloaded=${(easylist?.lastUpdated ?: 0L) > 0L} easyprivacyDownloaded=${(easyprivacy?.lastUpdated ?: 0L) > 0L} easylistRules=${easylist?.ruleCount ?: 0} easyprivacyRules=${easyprivacy?.ruleCount ?: 0} totalActiveRules=$totalActive engineGeneration=$gen"
    )
    Log.i(TAG, "[ADBLOCK_ENGINE] generation=$gen activeRules=$totalActive native=$isNative")
    Log.i(TAG, "[ADBLOCK_METRICS] blocked=$blockedCount")
  }

  private suspend fun loadPersistedRulesIntoBridgeAsync(source: String = "unknown"): Int = withContext(Dispatchers.IO) {
    mutex.withLock {
      val dir = filterDir ?: return@withLock 0
      Log.d(TAG, "[ADBLOCK_FILTER_LOAD_START] source=$source")
      val defaultRules = StringBuilder()
      val additionalRules = StringBuilder()
      val rulesSummary = StringBuilder()
      for (sub in _subscriptions.value) {
        if (sub.enabled) {
          val file = File(dir, "${sub.id}.txt")
          if (file.exists() && file.length() > 0) {
            try {
              val content = file.readText()
              val lines = content.lines()
              val lineCount = lines.count { it.isNotBlank() && !it.startsWith("!") }
              Log.d(TAG, "[ADBLOCK_PARSE] name=${sub.id} inputLines=${lines.size} validRules=$lineCount")
              Log.d(TAG, "[ADBLOCK_FILTER_PARSE] list=${sub.id} rules=$lineCount")
              rulesSummary.append("${sub.id}=$lineCount ")
              if (sub.id == "easylist" || sub.id == "easyprivacy") {
                defaultRules.append(content).append("\n")
              } else {
                additionalRules.append(content).append("\n")
              }
            } catch (e: Exception) {
              Log.e(TAG, "Failed reading cached filter ${sub.id}: ${e.message}")
            }
          } else {
            Log.d(TAG, "[ADBLOCK_FILTER_PARSE] list=${sub.id} cached=false fallback_count=0")
            rulesSummary.append("${sub.id}=(cached:false) ")
          }
        }
      }
      if (defaultRules.isNotBlank() || additionalRules.isNotBlank()) {
        val compiled = adblockBridge.compileRules(defaultRules.toString(), additionalRules.toString(), source = source)
        Log.d(TAG, "[ADBLOCK_COMPILE] name=all compiled=$compiled source=$source")
        Log.d(TAG, "[ADBLOCK_RULES] $rulesSummary total_compiled=$compiled")
        logAdblockStatus()
        return@withLock compiled
      } else {
        Log.d(TAG, "[ADBLOCK_RULES] $rulesSummary total_compiled=0 (using default tracker rules)")
        logAdblockStatus()
      }
      return@withLock 0
    }
  }

  fun loadPersistedRulesIntoBridge(source: String = "unknown"): Deferred<Int> {
    synchronized(loadFlightLock) {
      val existing = activeLoadDeferred
      if (existing != null && !existing.isCompleted) {
        Log.i(TAG, "[COMPILE_WAIT_EXISTING] source=$source")
        return existing
      }
      val deferred = bootstrapScope.async {
        loadPersistedRulesIntoBridgeAsync(source)
      }
      activeLoadDeferred = deferred
      return deferred
    }
  }

  suspend fun ensureFiltersReady(): Boolean = withContext(Dispatchers.IO) {
    val cachedRulesCount = loadPersistedRulesIntoBridge(source = "ensureFiltersReady").await()
    val allDefaultCached = defaultList.all { sub ->
      val file = filterDir?.let { File(it, "${sub.id}.txt") }
      file != null && file.exists() && file.length() > 0
    }
    if (cachedRulesCount > 0 && allDefaultCached) {
      Log.i(TAG, "[ADBLOCK] Cached filters verified and loaded: total_compiled=$cachedRulesCount")
      logAdblockStatus()
      return@withContext true
    }
    Log.i(TAG, "[ADBLOCK] Missing or incomplete cached filters, starting background download without blocking...")
    bootstrapScope.launch {
      updateAllSubscriptions(force = false)
    }
    return@withContext false
  }

  suspend fun updateAllSubscriptions(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
    if (_isUpdating.value) return@withContext false
    _isUpdating.value = true
    var successAll = true
    try {
      val now = System.currentTimeMillis()
      val oneDayMs = 24 * 60 * 60 * 1000L
      for (sub in _subscriptions.value) {
        if (force || (now - sub.lastUpdated > oneDayMs)) {
          val res = updateSubscription(sub)
          if (!res) successAll = false
        }
      }
      val compiledCount = loadPersistedRulesIntoBridgeAsync(source = "updateAllSubscriptions")
      if (compiledCount <= 0) {
        Log.w(TAG, "[ADBLOCK] Subscriptions update completed but compiled count is $compiledCount")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error updating filter subscriptions: ${e.message}", e)
      successAll = false
    } finally {
      _isUpdating.value = false
    }
    return@withContext successAll
  }

  private suspend fun updateSubscription(sub: FilterSubscription): Boolean = withContext(Dispatchers.IO) {
    if (sub.url.isBlank()) return@withContext false
    Log.i(TAG, "Downloading filter subscription '${sub.title}' from ${sub.url}...")
    try {
      val isGhost = CurrentTorRoute.isGhostActive || NetworkRouteAuthority.isOnionDestination(sub.url)
      val client = NetworkRouteAuthority.createHttpClient(
        isGhost = isGhost,
        targetUrl = sub.url,
        connectTimeoutSeconds = 15L,
        readTimeoutSeconds = 30L
      )
      val request = Request.Builder()
        .url(sub.url)
        .header("User-Agent", "RemmiBrowser/1.0 (FilterUpdater)")
        .build()

      val response = client.newCall(request).execute()
      if (!response.isSuccessful) {
        Log.w(TAG, "Filter download failed for ${sub.id} with HTTP ${response.code}")
        return@withContext false
      }

      val body = response.body?.string() ?: return@withContext false
      Log.d(TAG, "[ADBLOCK_LIST] name=${sub.id} downloaded=true bytes=${body.length}")

      // Max size limit: 15 MB
      if (body.length > 15 * 1024 * 1024) {
        Log.e(TAG, "Filter list ${sub.id} exceeds maximum size limit (15MB), rejecting.")
        return@withContext false
      }

      // Basic validation: Check for adblock signatures or valid lines
      val lines = body.lines()
      Log.d(TAG, "[ADBLOCK_PARSE] name=${sub.id} inputLines=${lines.size}")
      val validRuleCount = lines.count { line ->
        val trimmed = line.trim()
        trimmed.isNotEmpty() && !trimmed.startsWith("!") && (trimmed.startsWith("||") || trimmed.startsWith("@@") || trimmed.startsWith("##") || trimmed.contains("/"))
      }

      if (validRuleCount == 0 && lines.size < 10) {
        Log.w(TAG, "Downloaded filter content for ${sub.id} is invalid or empty, keeping existing cache.")
        return@withContext false
      }

      Log.d(TAG, "[ADBLOCK_COMPILE] name=${sub.id} compiled=$validRuleCount")

      // Save to disk atomically
      filterDir?.let { dir ->
        val tempFile = File(dir, "${sub.id}.tmp")
        val targetFile = File(dir, "${sub.id}.txt")
        tempFile.writeText(body)
        if (tempFile.renameTo(targetFile) || (targetFile.delete() && tempFile.renameTo(targetFile))) {
          Log.i(TAG, "Successfully persisted filter list ${sub.id} ($validRuleCount rules)")
        }
      }

      // Update state
      val now = System.currentTimeMillis()
      _subscriptions.value = _subscriptions.value.map {
        if (it.id == sub.id) {
          it.copy(ruleCount = validRuleCount, lastUpdated = now)
        } else it
      }

      if (!sub.isCustom) {
        prefs?.edit()
          ?.putInt("count_${sub.id}", validRuleCount)
          ?.putLong("updated_${sub.id}", now)
          ?.apply()
      } else {
        saveCustomSubscriptions(_subscriptions.value.filter { it.isCustom })
      }

      return@withContext true
    } catch (e: Throwable) {
      Log.e(TAG, "Exception downloading filter ${sub.id}: ${e.message}")
      return@withContext false
    }
  }

  companion object {
    private const val TAG = "FilterManager"

    @Volatile
    private var INSTANCE: FilterManager? = null

    fun getInstance(context: Context, adblockBridge: AdblockBridge = AdblockBridge.getInstance()): FilterManager {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: FilterManager(adblockBridge, context.applicationContext).also { INSTANCE = it }
      }
    }
  }
}
